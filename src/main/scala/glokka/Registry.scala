package glokka

import scala.collection.mutable.{ArrayBuffer, HashMap => MHashMap, MultiMap => MMultiMap, Set => MSet}
import scala.concurrent.duration.{FiniteDuration, SECONDS}

import akka.actor.{Actor, ActorLogging, ActorRef, ActorSystem, Cancellable, Props, Terminated}
import com.typesafe.config.ConfigFactory

object Registry {
  case class RegisterByRef(name: String, ref: ActorRef)
  case class RegisterByProps(name: String, props: Props)
  case class RegisterBySystemAndProps(name: String, system: ActorSystem, props: Props)
  case class RegisterResultOk(name: String, ref: ActorRef)
  case class RegisterResultConflict(name: String, ref: ActorRef)

  case class Lookup(name: String)
  case class LookupResultOk(name: String, ref: ActorRef)
  case class LookupResultNone(name: String)

  case class LookupOrCreate(name: String, timeoutInSeconds: Int = 5)
  case class CancelCreate(name: String)

  def start(system: ActorSystem, proxyName: String): ActorRef = {
    val config   = ConfigFactory.load()
    val provider = config.getString("akka.actor.provider")

    if (provider == "akka.cluster.ClusterActorRefProvider") {
      system.actorOf(Props(classOf[ClusterSingletonProxy], proxyName))
    } else {
      system.actorOf(Props(classOf[Registry], true))
    }
  }
}

//------------------------------------------------------------------------------

// Avoid using stash feature in Akka because it requires the user of Glokka to
// config non-default mailbox. It's a little tedious.

// Using mutable data structures internally in actor is OK (for speed).

private case class PendingMsg(sender: ActorRef, msg: Any)

// Key-value, key is actor name
private case class PendingCreateValue(creator: ActorRef, msgs: ArrayBuffer[PendingMsg], cancellable: Cancellable)

private case class TimeoutCreate(name: String, creator: ActorRef)

private class Registry(localMode: Boolean) extends Actor with ActorLogging {
  import Registry._

  // The main lookup table
  private val name2Ref = new MHashMap[String, ActorRef]

  // The reverse lookup table to quickly unregister dead actors
  private val ref2Names = new MHashMap[ActorRef, MSet[String]] with MMultiMap[ActorRef, String]

  // Key is actor name
  private val pendingCreateReqs = new MHashMap[String, PendingCreateValue]

  /** This constructor is for data handover in cluster mode. */
  def this(
    localMode: Boolean,
    immutableName2Ref: Map[String, ActorRef], immutableRef2Names: Map[ActorRef, Set[String]]
  ) {
    this(localMode)

    // Convert immutable to mutable
    name2Ref ++= immutableName2Ref
    immutableRef2Names.foreach { case (k, set) =>
      set.foreach { v => ref2Names.addBinding(k, v) }
    }
  }

  override def preStart() {
    if (localMode)
      log.info("Glokka actor registry starts in local mode")
    else
      log.info("Glokka actor registry starts in cluster mode")

    super.preStart()
  }

  override def preRestart(reason: Throwable, message: Option[Any]) {
    // Reset state on restart
    name2Ref.clear()
    ref2Names.clear()
    pendingCreateReqs.clear()

    super.preRestart(reason, message)
  }

  def receive = {
    case msg @ LookupOrCreate(name, timeout) =>
      pendingCreateReqs.get(name) match {
        case None =>
          doLookupOrCreate(name, timeout)

        case Some(PendingCreateValue(_, msgs, _)) =>
          msgs.append(PendingMsg(sender, msg))
      }

    case CancelCreate(name) =>
      // Only the one who sent LookupOrCreate can now cancel
      doCancel(name, sender, false)

    case TimeoutCreate(name, maybeTheCreator) =>
      doCancel(name, maybeTheCreator, true)

    case msg @ RegisterByRef(name, ref) =>
      pendingCreateReqs.get(name) match {
        case None =>
          doRegister(name, ref)

        case Some(PendingCreateValue(creator, msgs, cancellable)) =>
          if (sender == creator) {
            cancellable.cancel()  // Do this as soon as possible

            doRegister(name, ref)

            pendingCreateReqs.remove(name)
            msgs.foreach { msg => self.tell(msg.msg, msg.sender) }
          } else {
            msgs.append(PendingMsg(sender, msg))
          }
      }

    case RegisterByProps(name, props) =>
      caseRegisterByProps(RegisterBySystemAndProps(name, context.system, props))

    case msg: RegisterBySystemAndProps =>
      caseRegisterByProps(msg)

    case msg @ Lookup(name) =>
      pendingCreateReqs.get(name) match {
        case None =>
          doLookup(name)

        case Some(PendingCreateValue(_, msgs, _)) =>
          msgs.append(PendingMsg(sender, msg))
      }

    case Terminated(ref) =>
      ref2Names.remove(ref).foreach { names =>
        names.foreach { name => name2Ref.remove(name) }
      }

    // Only used in cluster mode, see ClusterSingletonProxy
    case HandOver =>
      // Convert mutable to immutable
      val immutableName2Ref  = name2Ref.toMap
      val immutableRef2Names = ref2Names.toMap.mapValues { mset => mset.toSet }

      // Reply to ClusterSingletonManager with hand over data,
      // which will be passed as parameter to new consumer singleton
      context.parent ! (immutableName2Ref, immutableRef2Names)

      context.stop(self)
  }

  //----------------------------------------------------------------------------

  private def caseRegisterByProps(msg: RegisterBySystemAndProps) {
    pendingCreateReqs.get(msg.name) match {
      case None =>
        doRegister(msg.name, msg.system, msg.props)

      case Some(PendingCreateValue(creator, msgs, cancellable)) =>
        if (sender == creator) {
          cancellable.cancel()  // Do this as soon as possible

          doRegister(msg.name, msg.system, msg.props)

          pendingCreateReqs.remove(msg.name)
          msgs.foreach { msg => self.tell(msg.msg, msg.sender) }
        } else {
          msgs.append(PendingMsg(sender, msg))
        }
    }
  }

  private def doLookupOrCreate(name: String, timeoutInSeconds: Int) {
    name2Ref.get(name) match {
      case Some(ref) =>
        sender ! LookupResultOk(name, ref)

      case None =>
        val delay = FiniteDuration(timeoutInSeconds, SECONDS)
        val msg   = TimeoutCreate(name, sender)
        import context.dispatcher
        val cancellable = context.system.scheduler.scheduleOnce(delay, self, msg)

        sender ! LookupResultNone(name)
        pendingCreateReqs(name) = PendingCreateValue(sender, ArrayBuffer(), cancellable)
    }
  }

  private def doCancel(name: String, maybeTheCreator: ActorRef, becauseOfTimeout: Boolean) {
    pendingCreateReqs.get(name).foreach { case PendingCreateValue(creator, msgs, cancellable) =>
      if (maybeTheCreator == creator) {
        if (!becauseOfTimeout) cancellable.cancel()  // Do this as soon as possible

        pendingCreateReqs.remove(name)
        msgs.foreach { msg => self.tell(msg.msg, msg.sender) }
      }
    }
  }

  private def doRegister(name: String, ref: ActorRef) {
    name2Ref.get(name) match {
      case Some(oldRef) =>
        if (oldRef == ref)
          sender ! RegisterResultOk(name, oldRef)
        else
          sender ! RegisterResultConflict(name, oldRef)

      case None =>
        sender ! RegisterResultOk(name, ref)
        context.watch(ref)
        name2Ref(name) = ref
        ref2Names.addBinding(ref, name)
    }
  }

  private def doRegister(name: String, system: ActorSystem, props: Props) {
    name2Ref.get(name) match {
      case Some(oldRef) =>
        sender ! RegisterResultConflict(name, oldRef)

      case None =>
        val ref = system.actorOf(props)
        sender ! RegisterResultOk(name, ref)
        context.watch(ref)
        name2Ref(name) = ref
        ref2Names.addBinding(ref, name)
    }
  }

  private def doLookup(name: String) {
    name2Ref.get(name) match {
      case Some(ref) =>
        sender ! LookupResultOk(name, ref)

      case None =>
        sender ! LookupResultNone(name)
    }
  }
}
