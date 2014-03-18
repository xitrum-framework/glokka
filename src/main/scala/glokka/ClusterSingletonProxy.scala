package glokka

import java.net.URLEncoder
import scala.collection.immutable.SortedSet

import akka.actor.{
  Actor, ActorRef, PoisonPill, Props, Stash,
  ActorSelection, RootActorPath, Identify, ActorIdentity
}
import akka.cluster.{Cluster, ClusterEvent, Member}
import akka.contrib.pattern.ClusterSingletonManager

private object ClusterSingletonProxy {
  // This must be URL-escaped
  val SINGLETON_NAME = URLEncoder.encode("GlokkaActorRegistry", "UTF-8")
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

  //----------------------------------------------------------------------------
  // Subscribe to MemberEvent, re-subscribe when restart

  override def preStart() {
    super.preStart()

    Cluster(context.system).subscribe(self, classOf[ClusterEvent.ClusterDomainEvent])

    val proxyProps = ClusterSingletonManager.props(
      singletonProps     = Props(classOf[ClusterSingletonRegistry], self),
      singletonName      = SINGLETON_NAME,
      terminationMessage = PoisonPill,
      role               = None
    )

    // Must use context.system.actorOf instead of context.actorOf, so that the
    // created actor is attached to the system, so that we have a stable path
    // to select later (see below)
    context.system.actorOf(proxyProps, escapedProxyName)
  }

  override def postStop() {
    super.postStop()
    Cluster(context.system).unsubscribe(self)
  }

  // Giving cluster events more priority might make clusterSingletonRegistryOpt
  // more reliable
  def receive = receiveClusterEvents orElse receiveClusterSingletonRegistryIdentity

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
      context.become(receiveClusterEvents orElse receiveRegisterAndLookup)
      unstashAll()

    case ActorIdentity(_, None) =>
      // Try again
      clusterSingletonRegistryOpt.foreach(_ ! Identify(None))

    case msg: Register => stash()
    case msg: Lookup   => stash()

    case _ =>
      // Ignore all other messages, like cluster events not handled by
      // receiveClusterEvents
  }

  private def receiveRegisterAndLookup: Actor.Receive = {
    case Register(name, props) =>
      clusterSingletonRegistryRef ! LookupOrCreate(name)
      context.become(receiveClusterEvents orElse receiveLookupOrCreateResult(sender(), name, props))

    case lookup: Lookup =>
      clusterSingletonRegistryRef.tell(lookup, sender())

    case _ =>
      // Ignore all other messages, like cluster events not handled by
      // receiveClusterEvents
  }

  private def receiveLookupOrCreateResult(requester: ActorRef, name: String, props: Props): Actor.Receive = {
    case msg @ Found(`name`, ref) =>
      replyAndDumpStash(requester, msg)

    case NotFound(`name`) =>
      // Must use context.system.actorOf instead of context.actorOf, so that
      // refCreatedByMe is not attached as a child to the current actor; otherwise
      // when the current actor dies, refCreatedByMe will be forcefully killed
      val refCreatedByMe = context.system.actorOf(props)

      sender() ! RegisterByRef(name, refCreatedByMe)
      context.become(receiveClusterEvents orElse receiveRegisterResult(requester, name, refCreatedByMe))

    case _ =>
      stash()
  }

  private def receiveRegisterResult(requester: ActorRef, name: String, refCreatedByMe: ActorRef): Actor.Receive = {
    case msg @ Found(`name`, otherRef) =>
      // Must use context.system.stop because context.system.actorOf was used
      // to create the actor
      context.system.stop(refCreatedByMe)
      replyAndDumpStash(requester, msg)

    case msg @ Created(`name`, `refCreatedByMe`) =>
      replyAndDumpStash(requester, msg)

    case _ =>
      stash()
  }

  private def replyAndDumpStash(requester: ActorRef, msg: Any) {
    requester ! msg
    context.become(receiveClusterEvents orElse receiveRegisterAndLookup)
    unstashAll()
  }

  /** @return Leader */
  private def clusterSingletonRegistryOpt: Option[ActorSelection] =
    membersByAge.headOption.map { m =>
      context.actorSelection(RootActorPath(m.address) / "user" / escapedProxyName / SINGLETON_NAME)
    }
}
