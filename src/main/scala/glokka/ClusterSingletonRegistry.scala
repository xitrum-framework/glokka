package glokka

import scala.collection.mutable.{ArrayBuffer, HashMap => MHashMap, MultiMap => MMultiMap, Set => MSet}
import scala.concurrent.duration.{FiniteDuration, SECONDS}
import akka.actor.{Actor, ActorRef, PoisonPill, Terminated}

private object ClusterRegistry {
  case class LookupOrCreate(name: String, timeoutInSeconds: Int = 60)
}

private object ClusterSingletonRegistry {
  // Messages used by ClusterSingletonRegistry internally.
  // Using mutable data structures internally in actor is OK (for speed).

  // Handle stash manually instead of using Akka's stash feature, because we want
  // to handle timeout per actor creation (per registry name)
  private case class StashMsg(msg: Any, requester: ActorRef)

  // See pendingCreateReqs below
  private case class PendingCreateValue(creator: ActorRef, msgs: ArrayBuffer[StashMsg])

  private case class TimeoutCreate(name: String, creator: ActorRef)
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

In case of ``NotFound``, you have 60s (see above) to register using ``Register``
by ref.

After sending ``Register`` by ref, you will receive ``Conflict`` or ``Registered``.

During the wait time, if there are lookup or register messages sent to the registry
(e.g. from other places), they will be stashed. They will be processed
after you send ``Register`` or timeout occurs.
*/
private class ClusterSingletonRegistry(clusterSingletonProxyRef: ActorRef) extends Actor {
  import Registry._
  import ClusterRegistry._
  import ClusterSingletonRegistry._

  //----------------------------------------------------------------------------

  // The main lookup table
  private val name2Ref = new MHashMap[String, ActorRef]

  // The reverse lookup table to quickly unregister dead actors
  private val ref2Names = new MHashMap[ActorRef, MSet[String]] with MMultiMap[ActorRef, String]

  // Keys are actor names
  private val pendingCreateReqs = new MHashMap[String, PendingCreateValue]

  //----------------------------------------------------------------------------

  override def postStop() {
    // For consistency, tell all actors in this registry to stop
    ref2Names.keys.foreach(_ ! PoisonPill)
  }

  override def preRestart(reason: Throwable, message: Option[Any]) {
    super.preRestart(reason, message)

    // Reset state on restart
    name2Ref.clear()
    ref2Names.clear()
    pendingCreateReqs.clear()
  }

  override def receive: Receive = {
    case lookupOrCreate @ LookupOrCreate(name, timeoutInSeconds) =>
      pendingCreateReqs.get(name) match {
        case None =>
          doLookupOrCreate(sender(), name, timeoutInSeconds)

        case Some(PendingCreateValue(_, msgs)) =>
          msgs.append(StashMsg(lookupOrCreate, sender()))
      }

    case TimeoutCreate(name, maybeTheCreator) =>
      pendingCreateReqs.get(name).foreach { case PendingCreateValue(creator, msgs) =>
        if (maybeTheCreator == creator) {
          pendingCreateReqs.remove(name)
          msgs.foreach { msg => self.tell(msg.msg, msg.requester) }
          msgs.clear()
        }
      }

    case register @ Register(name, Right(ref)) =>
      pendingCreateReqs.get(name) match {
        case None =>
          doRegister(sender(), name, ref)

        case Some(PendingCreateValue(creator, msgs)) =>
          val s = sender()
          if (s == creator) {
            doRegister(s, name, ref)

            pendingCreateReqs.remove(name)
            msgs.foreach { msg => self.tell(msg.msg, msg.requester) }
            msgs.clear()
          } else {
            msgs.append(StashMsg(register, s))
          }
      }

    case lookup @ Lookup(name) =>
      pendingCreateReqs.get(name) match {
        case None =>
          name2Ref.get(name) match {
            case None      => sender() ! NotFound(name)
            case Some(ref) => sender() ! Found(name, ref)
          }

        case Some(PendingCreateValue(_, msgs)) =>
          msgs.append(StashMsg(lookup, sender()))
      }

    case tell @ Tell(name, None, msg) =>
      pendingCreateReqs.get(name) match {
        case None =>
          name2Ref.get(name).foreach { ref => ref.tell(msg, sender()) }

        case Some(PendingCreateValue(_, msgs)) =>
          msgs.append(StashMsg(tell, sender()))
      }

    case Terminated(ref) =>
      ref2Names.remove(ref).foreach { names =>
        names.foreach { name => name2Ref.remove(name) }
      }
  }

  //----------------------------------------------------------------------------

  private def doLookupOrCreate(requester: ActorRef, name: String, timeoutInSeconds: Int) {
    name2Ref.get(name) match {
      case Some(ref) =>
        requester ! Found(name, ref)

      case None =>
        // Wait for named actor to be created
        val delay = FiniteDuration(timeoutInSeconds, SECONDS)
        val msg   = TimeoutCreate(name, requester)
        context.system.scheduler.scheduleOnce(delay, self, msg)(context.dispatcher)

        requester ! NotFound(name)
        pendingCreateReqs(name) = PendingCreateValue(requester, ArrayBuffer())
    }
  }

  private def doRegister(requester: ActorRef, name: String, refToRegister: ActorRef) {
    name2Ref.get(name) match {
      case Some(ref) =>
        if (ref == refToRegister)
          requester ! Registered(name, ref)
        else
          requester ! Conflict(name, ref, refToRegister)

      case None =>
        requester ! Registered(name, refToRegister)
        context.watch(refToRegister)
        name2Ref(name) = refToRegister
        ref2Names.addBinding(refToRegister, name)
    }
  }
}
