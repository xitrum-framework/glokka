package glokka

import java.net.URLEncoder

import scala.collection.immutable.SortedSet

import akka.actor.{
  Actor, ActorRef, PoisonPill, Props, Stash,
  ActorSelection, RootActorPath, Identify, ActorIdentity
}
import akka.cluster.{Cluster, ClusterEvent, Member}
import akka.cluster.singleton.{ClusterSingletonManager, ClusterSingletonManagerSettings}

private object ClusterSingletonProxy {
  // This must be URL-escaped
  val SINGLETON_NAME: String = URLEncoder.encode("GlokkaActorRegistry", "UTF-8")
}

private class ClusterSingletonProxy(proxyName: String) extends Actor with Stash {
  import Registry._
  import ClusterSingletonProxy._
  import ClusterRegistry._

  private[this] val escapedProxyName = URLEncoder.encode(proxyName, "UTF-8")

  // Sort by age, oldest first
  private[this] val ageOrdering = Ordering.fromLessThan[Member] { (a, b) => a.isOlderThan(b) }
  private[this] var membersByAge: SortedSet[Member] = SortedSet.empty(ageOrdering)

  private[this] var clusterSingletonRegistryRef: ActorRef = _

  private[this] var tellMsg: Option[Any] = None

  //----------------------------------------------------------------------------
  // Subscribe to MemberEvent, re-subscribe when restart

  override def preStart(): Unit = {
    super.preStart()

    Cluster(context.system).subscribe(self, classOf[ClusterEvent.ClusterDomainEvent])

    val proxyProps = ClusterSingletonManager.props(
      singletonProps     = Props(classOf[ClusterSingletonRegistry], self),
      terminationMessage = PoisonPill,
      settings           = ClusterSingletonManagerSettings(context.system).withSingletonName(SINGLETON_NAME)
    )

    // Must use context.system.actorOf instead of context.actorOf, so that the
    // created actor is attached to the system, so that we have a stable path
    // to select later (see below)
    context.system.actorOf(proxyProps, escapedProxyName)
  }

  override def postStop(): Unit = {
    super.postStop()
    Cluster(context.system).unsubscribe(self)
  }

  // Giving cluster events more priority might make clusterSingletonRegistryOpt
  // more reliable
  override def receive: Receive = receiveClusterEvents orElse receiveClusterSingletonRegistryIdentity

  //----------------------------------------------------------------------------

  private def receiveClusterEvents: Actor.Receive = {
    case clusterState: ClusterEvent.CurrentClusterState =>
      membersByAge = SortedSet.empty(ageOrdering) ++ clusterState.members
      clusterSingletonRegistryOpt.foreach(_ ! Identify(None))

    case ClusterEvent.MemberUp(m) =>
      val opt1 = membersByAge.headOption
      membersByAge += m
      val opt2 = membersByAge.headOption
      if (opt1 != opt2) {
        clusterSingletonRegistryOpt.foreach(_ ! Identify(None))
        context.become(receiveClusterEvents orElse receiveClusterSingletonRegistryIdentity)
      }

    case ClusterEvent.MemberRemoved(m, _) =>
      val opt1 = membersByAge.headOption
      membersByAge -= m
      val opt2 = membersByAge.headOption
      if (opt1 != opt2) {
        clusterSingletonRegistryOpt.foreach(_ ! Identify(None))
        context.become(receiveClusterEvents orElse receiveClusterSingletonRegistryIdentity)
      }
  }

  private def receiveClusterSingletonRegistryIdentity: Actor.Receive = {
    case ActorIdentity(_, Some(ref)) =>
      clusterSingletonRegistryRef = ref
      context.become(receiveClusterEvents orElse receiveGlokkaEvents)
      unstashAll()

    case ActorIdentity(_, None) =>
      // Try again
      clusterSingletonRegistryOpt.foreach(_ ! Identify(None))

    case _: Register => stash()
    case _: Lookup   => stash()
    case _: Tell     => stash()

    case _ =>
      // Ignore all other messages, like cluster events not handled by
      // receiveClusterEvents
  }

  private def receiveGlokkaEvents: Actor.Receive = {
    case Register(name, Left(props)) =>
      clusterSingletonRegistryRef ! LookupOrCreate(name)
      context.become(receiveClusterEvents orElse receiveLookupOrCreateResultByProps(sender(), name, props))

    case Register(name, Right(ref)) =>
      clusterSingletonRegistryRef ! LookupOrCreate(name)
      context.become(receiveClusterEvents orElse receiveLookupOrCreateResultByRef(sender(), name, ref))

    case lookup: Lookup =>
      clusterSingletonRegistryRef.tell(lookup, sender())

    case tell @ Tell(_, None, _) =>
      clusterSingletonRegistryRef.tell(tell, sender())

    case Tell(name, Some(props), msg) =>
      // Some(0) is a marker so that clusterSingletonRegistryRef knows that
      // if the named actor does not exist, it should let the proxy create and
      // register the actor
      tellMsg = Some(msg)
      clusterSingletonRegistryRef ! LookupOrCreate(name)
      context.become(receiveClusterEvents orElse receiveLookupOrCreateResultByProps(sender(), name, props))

    case _ =>
      // Ignore all other messages, like cluster events not handled by
      // receiveClusterEvents
  }

  private def receiveLookupOrCreateResultByProps(requester: ActorRef, name: String, props: Props): Actor.Receive = {
    case msg @ Found(`name`, ref) =>
      if (tellMsg.isEmpty)
        replyAndDumpStash(requester, msg)
      else
        tellAndDumpStash(requester, ref)

    case NotFound(`name`) =>
      // Must use context.system.actorOf instead of context.actorOf, so that
      // refCreatedByMe is not a child to the current actor; otherwise
      // when the current actor dies, refCreatedByMe will be forcefully killed
      val refCreatedByMe = context.system.actorOf(props)

      clusterSingletonRegistryRef ! Register(name, refCreatedByMe)
      context.become(receiveClusterEvents orElse receiveRegisterResultByProps(requester, name, refCreatedByMe))

    case _ =>
      stash()
  }

  private def receiveRegisterResultByProps(requester: ActorRef, name: String, refCreatedByMe: ActorRef): Actor.Receive = {
    case Conflict(`name`, otherRef, `refCreatedByMe`) =>
      // Must use context.system.stop because context.system.actorOf was used
      // to create the actor
      context.system.stop(refCreatedByMe)

      if (tellMsg.isEmpty)
        replyAndDumpStash(requester, Found(name, otherRef))
      else
        tellAndDumpStash(requester, otherRef)

    case Registered(`name`, `refCreatedByMe`) =>
      if (tellMsg.isEmpty)
        replyAndDumpStash(requester, Created(name, refCreatedByMe))
      else
        tellAndDumpStash(requester, refCreatedByMe)

    case _ =>
      stash()
  }

  private def receiveLookupOrCreateResultByRef(requester: ActorRef, name: String, refToRegister: ActorRef): Actor.Receive = {
    case Found(`name`, ref) =>
      val msg = if (ref == refToRegister) Registered(name, ref) else Conflict(name, ref, refToRegister)
      replyAndDumpStash(requester, msg)

    case NotFound(`name`) =>
      sender() ! Register(name, refToRegister)
      context.become(receiveClusterEvents orElse receiveRegisterResultByRef(requester, name, refToRegister))

    case _ =>
      stash()
  }

  private def receiveRegisterResultByRef(requester: ActorRef, name: String, refToRegister: ActorRef): Actor.Receive = {
    case msg @ Registered(`name`, `refToRegister`) =>
      replyAndDumpStash(requester, msg)

    case msg @ Conflict(`name`, _, `refToRegister`) =>
      replyAndDumpStash(requester, msg)

    case _ =>
      stash()
  }

  private def replyAndDumpStash(requester: ActorRef, msg: Any): Unit = {
    requester ! msg
    context.become(receiveClusterEvents orElse receiveGlokkaEvents)
    unstashAll()
  }

  private def tellAndDumpStash(requester: ActorRef, target: ActorRef): Unit = {
    target.tell(tellMsg.get, requester)
    tellMsg = None
    context.become(receiveClusterEvents orElse receiveGlokkaEvents)
    unstashAll()
  }

  /** @return Leader */
  private def clusterSingletonRegistryOpt: Option[ActorSelection] =
    membersByAge.headOption.map { m =>
      context.actorSelection(RootActorPath(m.address) / "user" / escapedProxyName / SINGLETON_NAME)
    }
}
