package glokka

import scala.collection.mutable.{ArrayBuffer, Map => MMap}
import akka.actor.{Actor, ActorLogging, ActorRef, Terminated}

// May need to make these immutable so that they can be serializable:
// name2Ref:  the main lookup table
// ref2Names: the reverse lookup table to quickly unregister dead actors
class LocalActorRegistry(
    name2Ref:  MMap[String, ActorRef],
    ref2Names: MMap[ActorRef, ArrayBuffer[String]]
) extends Actor with ActorLogging {
  import ActorRegistry._

  // Reset state on restart
  override def preStart() {
    log.info("ActorRegistry starts in local mode")
    name2Ref.clear()
    ref2Names.clear()
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
          sender ! LookupResultNone
      }

    case Terminated(actorRef) =>
      ref2Names.get(actorRef).foreach { names =>
        names.foreach { name => name2Ref.remove(name) }
      }
      ref2Names.remove(actorRef)

    case HandOver =>
      // Reply to ClusterSingletonManager with hand over data,
      // which will be passed as parameter to new consumer singleton
      context.parent ! (name2Ref, ref2Names)
      context.stop(self)
  }
}
