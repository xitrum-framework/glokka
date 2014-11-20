package glokka

import scala.collection.mutable.{HashMap => MHashMap, MultiMap => MMultiMap, Set => MSet}
import akka.actor.{Actor, ActorRef, Terminated}

private class LocalRegistry extends Actor {
  import Registry._

  // The main lookup table
  private val name2Ref = new MHashMap[String, ActorRef]

  // The reverse lookup table to quickly unregister dead actors
  private val ref2Names = new MHashMap[ActorRef, MSet[String]] with MMultiMap[ActorRef, String]

  //----------------------------------------------------------------------------

  def receive = {
    case RegisterByProps(name, props) =>
      name2Ref.get(name) match {
        case Some(ref) =>
          sender() ! Found(name, ref)

        case None =>
          // Must use context.system.actorOf instead of context.actorOf, so that
          // refCreatedByMe is not attached as a child to the current actor; otherwise
          // when the current actor dies, refCreatedByMe will be forcefully killed
          val ref = context.system.actorOf(props)
          sender() ! Created(name, ref)

          name2Ref(name) = ref
          ref2Names.addBinding(ref, name)
          context.watch(ref)
      }

    case RegisterByRef(name, refToRegister) =>
      name2Ref.get(name) match {
        case Some(ref) =>
          if (ref == refToRegister)
            sender() ! Registered(name, ref)
          else
            sender() ! Conflict(name, ref, refToRegister)

        case None =>
          sender() ! Registered(name, refToRegister)

          name2Ref(name) = refToRegister
          ref2Names.addBinding(refToRegister, name)
          context.watch(refToRegister)
      }

    case Lookup(name) =>
      name2Ref.get(name) match {
        case None      => sender() ! NotFound(name)
        case Some(ref) => sender() ! Found(name, ref)
      }

    case Terminated(ref) =>
      ref2Names.remove(ref).foreach { names =>
        names.foreach { name => name2Ref.remove(name) }
      }
  }
}
