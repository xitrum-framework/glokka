package glokka

import scala.collection.mutable.{ArrayBuffer, Map => MMap}
import scala.concurrent.duration.{FiniteDuration, SECONDS}

import akka.actor.{Actor, ActorLogging, ActorRef, ActorSystem, Cancellable, Props, Terminated}
import com.typesafe.config.ConfigFactory

object Registry {
  case class Register(name: String, actorRef: ActorRef)
  case class RegisterResultOk(name: String, actorRef: ActorRef)
  case class RegisterResultConflict(name: String, actorRef: ActorRef)

  case class Lookup(name: String)
  case class LookupResultOk(name: String, actorRef: ActorRef)
  case class LookupResultNone(name: String)

  case class LookupOrCreate(name: String, timeoutInSeconds: Int = 5)
  case class CancelCreate(name: String)

  def start(system: ActorSystem, proxyName: String): ActorRef = {
    val config   = ConfigFactory.load()
    val provider = config.getString("akka.actor.provider")

    if (provider == "akka.cluster.ClusterActorRefProvider")
      system.actorOf(Props(classOf[ClusterSingletonProxy], proxyName))
    else
      system.actorOf(Props(classOf[Registry], true, MMap[String, ActorRef](), MMap[ActorRef, ArrayBuffer[String]]()))
  }
}

//------------------------------------------------------------------------------

// Avoid using stash feature in Akka because it requires the user of Glokka to
// config non-default mailbox. It's a little tedious.
//
// May have to make things in Registry immutable so that they can be serializable
// for cluster mode, when handover from a node to another occurs.

private case class PendingMsg(sender: ActorRef, msg: Any)

// Key-value, key is actor name
private case class PendingCreateValue(creator: ActorRef, msgs: ArrayBuffer[PendingMsg], cancellable: Cancellable)

private case class TimeoutCreate(name: String, creator: ActorRef)

/**
 * @param name2Ref  The main lookup table
 * @param ref2Names The reverse lookup table to quickly unregister dead actors
 */
private class Registry(
    localMode: Boolean,
    name2Ref:  MMap[String, ActorRef],
    ref2Names: MMap[ActorRef, ArrayBuffer[String]]
) extends Actor with ActorLogging {
  import Registry._

  // Key-value, key is actor name
  private val pendingCreateReqs = MMap[String, PendingCreateValue]()

  // Reset state on restart
  override def preStart() {
    if (localMode)
      log.info("ActorRegistry starts in local mode")
    else
      log.info("ActorRegistry starts in cluster mode")

    name2Ref.clear()
    ref2Names.clear()
    pendingCreateReqs.clear()
  }

  def receive = {
    case msg @ LookupOrCreate(name, timeout) =>
      pendingCreateReqs.get(name) match {
        case None                                 => doLookupOrCreate(name, timeout)
        case Some(PendingCreateValue(_, msgs, _)) => msgs.append(PendingMsg(sender, msg))
      }

    case CancelCreate(name) =>
      // Only the one who sent LookupOrCreate can now cancel
      doCancel(name, sender, false)

    case TimeoutCreate(name, maybeTheCreator) =>
      doCancel(name, maybeTheCreator, true)

    case msg @ Register(name, actorRef) =>
      pendingCreateReqs.get(name) match {
        case None =>
          doRegister(name, actorRef)

        case Some(PendingCreateValue(creator, msgs, cancellable)) =>
          if (sender == creator) {
            cancellable.cancel()  // Do this as soon as possible

            doRegister(name, actorRef)

            pendingCreateReqs.remove(name)
            msgs.foreach { msg => self.tell(msg.msg, msg.sender) }
          } else {
            msgs.append(PendingMsg(sender, msg))
          }
      }

    case msg @ Lookup(name) =>
      pendingCreateReqs.get(name) match {
        case None                                 => doLookup(name)
        case Some(PendingCreateValue(_, msgs, _)) => msgs.append(PendingMsg(sender, msg))
      }

    case Terminated(actorRef) =>
      ref2Names.remove(actorRef).foreach { names =>
        names.foreach { name => name2Ref.remove(name) }
      }

    // Only used in cluster mode, see ClusterSingletonProxy
    case HandOver =>
      // Reply to ClusterSingletonManager with hand over data,
      // which will be passed as parameter to new consumer singleton
      context.parent ! (name2Ref, ref2Names)
      context.stop(self)
  }

  //----------------------------------------------------------------------------

  private def doLookupOrCreate(name: String, timeoutInSeconds: Int) {
    name2Ref.get(name) match {
      case Some(actorRef) =>
        sender ! LookupResultOk(name, actorRef)

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

  private def doRegister(name: String, actorRef: ActorRef) {
    name2Ref.get(name) match {
      case Some(oldActorRef) =>
        if (oldActorRef == actorRef)
          sender ! RegisterResultOk(name, oldActorRef)
        else
          sender ! RegisterResultConflict(name, oldActorRef)

      case None =>
        sender ! RegisterResultOk(name, actorRef)

        name2Ref(name) = actorRef

        context.watch(actorRef)
        ref2Names.get(actorRef) match {
          case None =>
            ref2Names(actorRef) = ArrayBuffer(name)

          case Some(names) =>
            names.append(name)
        }
    }
  }

  private def doLookup(name: String) {
    name2Ref.get(name) match {
      case Some(actorRef) =>
        sender ! LookupResultOk(name, actorRef)

      case None =>
        sender ! LookupResultNone(name)
    }
  }
}
