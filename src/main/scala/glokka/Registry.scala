package glokka

import scala.collection.mutable.{ArrayBuffer, Map => MMap}
import akka.actor.{Actor, ActorLogging, ActorRef, ActorSystem, Props, Terminated}
import com.typesafe.config.ConfigFactory

object Registry {
  case class Register(name: String, actorRef: ActorRef)
  case class RegisterResultOk(name: String, actorRef: ActorRef)
  case class RegisterResultConflict(name: String, actorRef: ActorRef)

  case class Lookup(name: String)
  case class LookupResultOk(name: String, actorRef: ActorRef)
  case class LookupResultNone(name: String)

  case class LookupOrCreate(name: String, timeout: Int)
  case class CancelCreate(name: String)

  def start(system: ActorSystem, proxyName: String): ActorRef = {
    val config   = ConfigFactory.load()
    val provider = config.getString("akka.actor.provider")

    if (provider == "akka.cluster.ClusterActorRefProvider")
      system.actorOf(Props(classOf[RegistryClusterSingletonProxy], proxyName))
    else
      system.actorOf(Props(classOf[Registry], true, MMap[String, ActorRef](), MMap[ActorRef, ArrayBuffer[String]]()))
  }
}

//------------------------------------------------------------------------------

private sealed trait PendingMsg
private case class PendingRegister(sender: ActorRef, actorRef: ActorRef) extends PendingMsg
private case class PendingLookup(sender: ActorRef) extends PendingMsg
private case class PendingLookupOrCreate(sender: ActorRef) extends PendingMsg

private case class PendingCreateReqData(creator: ActorRef, msgs: ArrayBuffer[PendingMsg])

//------------------------------------------------------------------------------

// May need to make these immutable so that they can be serializable:
// name2Ref:  the main lookup table
// ref2Names: the reverse lookup table to quickly unregister dead actors
class Registry(
    localMode: Boolean,
    name2Ref:  MMap[String, ActorRef],
    ref2Names: MMap[ActorRef, ArrayBuffer[String]]
) extends Actor with ActorLogging {
  import Registry._

  //                                   name    data
  private val pendingCreateReqs = MMap[String, PendingCreateReqData]()

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
    case Register(name, actorRef) =>
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

    case Lookup(name) =>
      name2Ref.get(name) match {
        case Some(actorRef) =>
          sender ! LookupResultOk(name, actorRef)

        case None =>
          sender ! LookupResultNone(name)
      }

    case LookupOrCreate(name, timeout) =>
      pendingCreateReqs.get(name) match {
        case None =>
          name2Ref.get(name) match {
            case Some(actorRef) =>
              sender ! LookupResultOk(name, actorRef)

            case None =>
              sender ! LookupResultNone(name)
              pendingCreateReqs(name) = PendingCreateReqData(sender, ArrayBuffer())
          }

        case Some(PendingCreateReqData(_, msgs)) =>
          // There's pending create request for this name, process later
          msgs.append(PendingLookupOrCreate(sender))
      }

    case CancelCreate(name) =>
      pendingCreateReqs.get(name).foreach { case PendingCreateReqData(creator, msgs) =>
        if (creator == sender) {
          pendingCreateReqs.remove(name)
          processPendingMsgsOnCancel(name, msgs)
        }
      }

    case Terminated(actorRef) =>
      ref2Names.get(actorRef).foreach { names =>
        names.foreach { name => name2Ref.remove(name) }
      }
      ref2Names.remove(actorRef)

    // Only used in cluster mode
    case HandOver =>
      // Reply to ClusterSingletonManager with hand over data,
      // which will be passed as parameter to new consumer singleton
      context.parent ! (name2Ref, ref2Names)
      context.stop(self)
  }

  private def processPendingMsgsOnCancel(name: String, msgs: ArrayBuffer[PendingMsg]) {
    if (msgs.isEmpty) return

    val head = msgs.head
    val tail = msgs.tail

    head match {
      case PendingRegister(sender, actorRef) =>
        if (tail.nonEmpty) pendingCreateReqs(name) = PendingCreateReqData(sender, tail)
        self ! Register(name, actorRef)

      case PendingLookup(sender) =>
        sender ! LookupResultNone(name)
        processPendingMsgsOnCancel(name, tail)

      case PendingLookupOrCreate(sender) =>

    }
  }
}
