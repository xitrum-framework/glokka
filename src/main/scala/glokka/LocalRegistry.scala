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
    case Register(name, props) =>
      name2Ref.get(name) match {
        case Some(ref) =>
          sender() ! Found(name, ref)

        case None =>
          val ref = context.system.actorOf(props)
          sender() ! Created(name, ref)

          name2Ref(name) = ref
          ref2Names.addBinding(ref, name)
          context.watch(ref)
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
