package glokka

import scala.collection.mutable.{HashMap => MHashMap, MultiMap => MMultiMap, Set => MSet}
import akka.actor.{Actor, ActorRef, Props, Terminated}

private class LocalRegistry extends Actor {
  import Registry._

  // The main lookup table
  private val name2Ref = new MHashMap[String, ActorRef]

  // The reverse lookup table to quickly unregister dead actors
  private val ref2Names = new MHashMap[ActorRef, MSet[String]] with MMultiMap[ActorRef, String]

  //----------------------------------------------------------------------------

  override def receive: Receive = {
    case Register(name, Left(props)) =>
      name2Ref.get(name) match {
        case Some(ref) =>
          sender() ! Found(name, ref)

        case None =>
          val ref = createActor(props)
          sender() ! Created(name, ref)
          registerActor(ref, name)
      }

    case Register(name, Right(refToRegister)) =>
      name2Ref.get(name) match {
        case Some(ref) =>
          if (ref == refToRegister)
            sender() ! Registered(name, ref)
          else
            sender() ! Conflict(name, ref, refToRegister)

        case None =>
          sender() ! Registered(name, refToRegister)
          registerActor(refToRegister, name)
      }

    case Lookup(name) =>
      name2Ref.get(name) match {
        case Some(ref) => sender() ! Found(name, ref)
        case None      => sender() ! NotFound(name)
      }

    case Tell(name, None, msg) =>
      name2Ref.get(name).foreach { ref => ref.tell(msg, sender()) }

    case Tell(name, Some(props), msg) =>
      name2Ref.get(name) match {
        case Some(ref) =>
          ref.tell(msg, sender())

        case None =>
          val ref = createActor(props)
          ref.tell(msg, sender())
          registerActor(ref, name)
      }

    case Terminated(ref) =>
      ref2Names.remove(ref).foreach { names =>
        names.foreach { name => name2Ref.remove(name) }
      }
  }

  private def createActor(props: Props): ActorRef = {
    // Must use context.system.actorOf instead of context.actorOf, so that
    // refCreatedByMe is not attached as a child to the current actor; otherwise
    // when the current actor dies, refCreatedByMe will be forcefully killed
    context.system.actorOf(props)
  }

  private def registerActor(ref: ActorRef, name: String) {
    name2Ref(name) = ref
    ref2Names.addBinding(ref, name)
    context.watch(ref)
  }
}
