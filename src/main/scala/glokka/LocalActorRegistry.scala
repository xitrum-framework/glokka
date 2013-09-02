package glokka

import scala.collection.mutable.{ArrayBuffer, Map => MMap}
import akka.actor.{Actor, ActorLogging, ActorRef, Terminated}

object LocalActorRegistry {
  val ACTOR_NAME = ActorRegistry.escape(getClass.getName)
}

class LocalActorRegistry extends Actor with ActorLogging {
  import ActorRegistry._
  import LocalActorRegistry._

  // This is the main lookup table
  private val name2Ref  = MMap[String, ActorRef]()

  // This is the reverse lookup table to quickly unregister dead actors
  private val ref2Names = MMap[ActorRef, ArrayBuffer[String]]()

  override def preStart() {
    log.info("ActorRegistry starts in local mode: " + self)
    name2Ref.clear()
  }

  def receive = {
    case Register(name, actorRef) =>
      name2Ref.get(name) match {
        case Some(oldActorRef) =>
          if (oldActorRef == actorRef)
            sender ! RegisterResultOK(name, oldActorRef)
          else
            sender ! RegisterResultConflict(name, oldActorRef)

        case None =>
          sender ! RegisterResultOK(name, actorRef)

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
          sender ! LookupResultOK(name, actorRef)

        case None =>
          sender ! LookupResultNone
      }

    case Terminated(actorRef) =>
      ref2Names.get(actorRef).foreach { names =>
        names.foreach { name => name2Ref.remove(name) }
      }
      ref2Names.remove(actorRef)
  }
}
