package glokka

import java.net.URLEncoder
import scala.collection.immutable.SortedSet
import scala.collection.mutable.ArrayBuffer

import akka.actor.{Actor, ActorRef, ActorSelection, Props, RootActorPath}
import akka.cluster.{Cluster, ClusterEvent, Member}
import akka.contrib.pattern.ClusterSingletonManager

private object ClusterSingletonProxy {
  // This must be URL-escaped
  val SINGLETON_NAME = URLEncoder.encode("GlokkaActorRegistry", "UTF-8")
}

private class ClusterSingletonProxy(proxyName: String) extends Actor {
  import Registry._
  import ClusterSingletonProxy._
  import ClusterRegistry._

  private[this] val escapedProxyName = URLEncoder.encode(proxyName, "UTF-8")

  // Sort by age, oldest first
  private[this] val ageOrdering = Ordering.fromLessThan[Member] { (a, b) => a.isOlderThan(b) }

  private[this] var membersByAge: SortedSet[Member] = SortedSet.empty(ageOrdering)

  private[this] val stashMsgs = ArrayBuffer[StashMsg]()

  //----------------------------------------------------------------------------
  // Subscribe to MemberEvent, re-subscribe when restart

  override def preStart() {
    Cluster(context.system).subscribe(self, classOf[ClusterEvent.ClusterDomainEvent])

    val proxyProps = ClusterSingletonManager.props(
      singletonProps     = Props(classOf[ClusterSingletonRegistry]),
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

  def receive = receiveClusterEvents orElse receiveRegister

  //----------------------------------------------------------------------------

  private def receiveClusterEvents: Actor.Receive = {
    case clusterState: ClusterEvent.CurrentClusterState =>
      membersByAge = SortedSet.empty(ageOrdering) ++ clusterState.members

    case ClusterEvent.MemberUp(m) =>
      membersByAge += m

    case ClusterEvent.MemberRemoved(m, _) =>
      membersByAge -= m
  }

  private def receiveRegister: Actor.Receive = {
    case Register(name, props) =>
      clusterSingletonRegistryOpt.foreach { leader =>
        leader ! LookupOrCreate(name)
        context.become(
          receiveClusterEvents                               orElse
          receiveLookupOrCreateResult(sender(), name, props) orElse
          stashOther
        )
      }

    case other =>
      // Forward everything else to the registry
      clusterSingletonRegistryOpt.foreach { _.tell(other, sender()) }
  }

  private def receiveLookupOrCreateResult(requester: ActorRef, name: String, props: Props): Actor.Receive = {
    case msg @ Found(`name`, ref) =>
      requester ! msg
      context.become(
        receiveClusterEvents orElse
        receiveRegister      orElse
        stashOther
      )

    case NotFound(`name`) =>
      // Must use context.system.actorOf instead of context.actorOf, so that
      // refCreatedByMe is not attached as a child to the current actor; otherwise
      // when the current actor dies, refCreatedByMe will be forcefully killed
      val refCreatedByMe = context.system.actorOf(props)

      sender() ! RegisterByRef(name, refCreatedByMe)
      context.become(
        receiveClusterEvents                                   orElse
        receiveRegisterResult(requester, name, refCreatedByMe) orElse
        stashOther
      )
  }

  private def receiveRegisterResult(requester: ActorRef, name: String, refCreatedByMe: ActorRef): Actor.Receive = {
    case msg @ Found(`name`, otherRef) =>
      // Must use context.system.stop because context.system.actorOf was used
      // to create the actor
      context.system.stop(refCreatedByMe)

      requester ! msg
      context.become(
        receiveClusterEvents orElse
        receiveRegister      orElse
        stashOther
      )

    case msg @ Created(`name`, `refCreatedByMe`) =>
      requester ! refCreatedByMe
      context.become(
        receiveClusterEvents orElse
        receiveRegister      orElse
        stashOther
      )
  }

  private def stashOther: Actor.Receive = {
    case other => stashMsgs.append(StashMsg(sender(), other))
  }

  private def dumpStash() {
    stashMsgs.foreach { msg => self.tell(msg.msg, msg.requester) }
    stashMsgs.clear()
  }

  /** @return Leader */
  private def clusterSingletonRegistryOpt: Option[ActorSelection] =
    membersByAge.headOption.map { m =>
      context.actorSelection(RootActorPath(m.address) / "user" / escapedProxyName / SINGLETON_NAME)
    }
}
