package glokka

import scala.collection.mutable.{ArrayBuffer, HashMap => MHashMap, MultiMap => MMultiMap, Set => MSet}
import scala.concurrent.duration.{FiniteDuration, SECONDS}
import akka.actor.{Actor, ActorRef, ActorSystem, PoisonPill, Terminated}

private object ClusterRegistry {
  case class LookupOrCreate(name: String, timeoutInSeconds: Int = 60)
  case class RegisterByRef(name: String, ref: ActorRef)

  case object HaveClusterSingletonRegistryStarted
  case object ClusterSingletonRegistryStarted
  case object TerminateRegistry
}

/**
This works hand in hand with ClusterSingletonProxy:

To lookup a named actor, and when it does not exist, create and register it:

::

  registry ! LookupOrCreate("name", timeoutInSeconds = 1)

``timeoutInSeconds`` defaults to 60:

::

  registry ! LookupOrCreate("name")

You will receive:

::

  Found("name", actorRef)

Or:

::

  NotFound("name")

In case of ``NotFound``, you have 60s (see above) to register using ``RegisterByRef``.

During the wait time, if there are lookup or register messages sent to the registry
(e.g. from other places), they will be temporarily pended. They will be processed
after you send ``Register`` or timeout occurs.
*/
private class ClusterSingletonRegistry(clusterSingletonProxyRef: ActorRef) extends Actor {
  import Registry._
  import ClusterRegistry._

  // Using mutable data structures internally in actor is OK (for speed) -------

  // Handle stash manually instead of using Akka's stash feature, because we want
  // to handle timeout per actor creation (per registry name)
  private case class StashMsg(msg: Any, requester: ActorRef)

  // See pendingCreateReqs below
  private case class PendingCreateValue(creator: ActorRef, msgs: ArrayBuffer[StashMsg])

  private case class TimeoutCreate(name: String, creator: ActorRef)

  //----------------------------------------------------------------------------

  // The main lookup table
  private val name2Ref = new MHashMap[String, ActorRef]

  // The reverse lookup table to quickly unregister dead actors
  private val ref2Names = new MHashMap[ActorRef, MSet[String]] with MMultiMap[ActorRef, String]

  // Key is actor name
  private val pendingCreateReqs = new MHashMap[String, PendingCreateValue]

  //----------------------------------------------------------------------------

  override def preStart() {
    super.preStart()
    clusterSingletonProxyRef ! ClusterSingletonRegistryStarted
  }

  override def preRestart(reason: Throwable, message: Option[Any]) {
    super.preRestart(reason, message)

    // Reset state on restart
    name2Ref.clear()
    ref2Names.clear()
    pendingCreateReqs.clear()
  }

  def receive = {
    case msg @ LookupOrCreate(name, timeout) =>
      pendingCreateReqs.get(name) match {
        case None =>
          doLookupOrCreate(name, timeout)

        case Some(PendingCreateValue(_, msgs)) =>
          msgs.append(StashMsg(msg, sender()))
      }

    case TimeoutCreate(name, maybeTheCreator) =>
      pendingCreateReqs.get(name).foreach { case PendingCreateValue(creator, msgs) =>
        if (maybeTheCreator == creator) {
          pendingCreateReqs.remove(name)
          msgs.foreach { msg => self.tell(msg.msg, msg.requester) }
          msgs.clear()
        }
      }

    case msg @ RegisterByRef(name, ref) =>
      pendingCreateReqs.get(name) match {
        case None =>
          doRegister(name, ref)

        case Some(PendingCreateValue(creator, msgs)) =>
          val s = sender()
          if (s == creator) {
            doRegister(name, ref)

            pendingCreateReqs.remove(name)
            msgs.foreach { msg => self.tell(msg.msg, msg.requester) }
            msgs.clear()
          } else {
            msgs.append(StashMsg(msg, s))
          }
      }

    case msg @ Lookup(name) =>
      pendingCreateReqs.get(name) match {
        case None =>
          doLookup(name)

        case Some(PendingCreateValue(_, msgs)) =>
          msgs.append(StashMsg(msg, sender()))
      }

    case Terminated(ref) =>
      ref2Names.remove(ref).foreach { names =>
        names.foreach { name => name2Ref.remove(name) }
      }

    // Only used in cluster mode, see ClusterSingletonProxy
    case TerminateRegistry =>
      // For consistency, tell all actors in this registry to stop
      ref2Names.keys.foreach(_ ! PoisonPill)

      context.stop(self)
  }

  //----------------------------------------------------------------------------

  private def doLookupOrCreate(name: String, timeoutInSeconds: Int) {
    val s = sender()
    name2Ref.get(name) match {
      case Some(ref) =>
        s ! Found(name, ref)

      case None =>
        val delay = FiniteDuration(timeoutInSeconds, SECONDS)
        val msg   = TimeoutCreate(name, s)
        context.system.scheduler.scheduleOnce(delay, self, msg)(context.dispatcher)

        s ! NotFound(name)
        pendingCreateReqs(name) = PendingCreateValue(s, ArrayBuffer())
    }
  }

  private def doRegister(name: String, ref: ActorRef) {
    name2Ref.get(name) match {
      case Some(ref) =>
        sender() ! Found(name, ref)

      case None =>
        sender() ! Created(name, ref)
        context.watch(ref)
        name2Ref(name) = ref
        ref2Names.addBinding(ref, name)
    }
  }

  private def doLookup(name: String) {
    name2Ref.get(name) match {
      case None      => sender() ! NotFound(name)
      case Some(ref) => sender() ! Found(name, ref)
    }
  }
}
