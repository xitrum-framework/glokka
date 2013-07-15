package glokka

import akka.actor.{Actor, ActorRef, Props, Identify, ActorIdentity, ActorLogging}

object LocalActorRegistry {
  val ACTOR_NAME = ActorRegistry.escape(getClass.getName)

  private case class IdentifyForLookup(sed: ActorRef)
  private case class IdentifyForLookupOrCreate(sed: ActorRef, propsMaker: () => Props, escapedName: String)
}

class LocalActorRegistry extends Actor with ActorLogging {
  import ActorRegistry._
  import LocalActorRegistry._

  override def preStart() {
    log.info("ActorRegistry started: " + self)
  }

  def receive = {
    case Lookup(name) =>
      val sel = context.actorSelection(escape(name))
      val sed = sender
      sel ! Identify(IdentifyForLookup(sed))

    case LookupOrCreate(name, propsMaker) =>
      val esc = escape(name)
      val sel = context.actorSelection(esc)
      val sed = sender
      sel ! Identify(IdentifyForLookupOrCreate(sed, propsMaker, esc))

    //--------------------------------------------------------------------------

    case ActorIdentity(IdentifyForLookup(sed), opt) =>
      sed ! opt

    case ActorIdentity(IdentifyForLookupOrCreate(sed, _, _), Some(actorRef)) =>
      sed ! (false, actorRef)

    case ActorIdentity(IdentifyForLookupOrCreate(sed, propsMaker, escapedName), None) =>
      val actorRef = context.actorOf(propsMaker(), escapedName)
      sed ! (true, actorRef)
  }
}
