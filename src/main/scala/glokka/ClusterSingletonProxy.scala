package glokka

import java.net.URLEncoder
import scala.collection.immutable.SortedSet

import akka.actor.{Actor, ActorRef, ActorSelection, Props, RootActorPath, Stash}
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

  private var clusterSingletonRegistryStarted = false

  //----------------------------------------------------------------------------
  // Subscribe to MemberEvent, re-subscribe when restart

  override def preStart() {
    Cluster(context.system).subscribe(self, classOf[ClusterEvent.ClusterDomainEvent])

    val proxyProps = ClusterSingletonManager.props(
      singletonProps     = Props(classOf[ClusterSingletonRegistry], self),
      singletonName      = SINGLETON_NAME,
      terminationMessage = ClusterRegistry.TerminateRegistry,
      role               = None
    )

    // Must use context.system.actorOf instead of context.actorOf, so that the
    // created actor is attached to the system, so that we have a stable path
    // to select later (see below)
    context.system.actorOf(proxyProps, escapedProxyName)
  }

  override def postStop() {
    Cluster(context.system).unsubscribe(self)
  }

  // Giving cluster events more priority might make clusterSingletonRegistryOpt
  // more reliable
  def receive = receiveClusterEvents orElse receiveClusterSingletonRegistryStarted

  //----------------------------------------------------------------------------

  private def receiveClusterEvents: Actor.Receive = {
    case clusterState: ClusterEvent.CurrentClusterState =>
      membersByAge = SortedSet.empty(ageOrdering) ++ clusterState.members

    case ClusterEvent.MemberUp(m) =>
      membersByAge += m

    case ClusterEvent.MemberRemoved(m, _) =>
      membersByAge -= m
  }

  private def receiveClusterSingletonRegistryStarted: Actor.Receive = {
    case ClusterSingletonRegistryStarted =>
      context.become(receiveClusterEvents orElse receiveRegisterAndLookup)
      unstashAll()

    case _ =>
      stash()
  }

  private def receiveRegisterAndLookup: Actor.Receive = {
    case Register(name, props) =>
      clusterSingletonRegistryOpt.foreach { leader =>
        leader ! LookupOrCreate(name)
        context.become(receiveClusterEvents orElse receiveLookupOrCreateResult(sender(), name, props))
      }

    case lookup: Lookup =>
      clusterSingletonRegistryOpt.foreach { _.tell(lookup, sender()) }

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
