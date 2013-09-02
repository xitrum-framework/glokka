package glokka

import scala.collection.immutable.SortedSet
import scala.collection.mutable.{ArrayBuffer, Map => MMap}

import akka.actor.{Actor, ActorLogging, ActorRef, ActorSelection, PoisonPill, Props, RootActorPath}
import akka.cluster.{Cluster, ClusterEvent, Member}
import akka.contrib.pattern.ClusterSingletonManager

object ClusterActorRegistrySingletonProxy {
  val PROXY_NAME     = "Proxy"
  val SINGLETON_NAME = "ActorRegistry"
}

class ClusterActorRegistrySingletonProxy extends Actor with ActorLogging {
  import ClusterActorRegistrySingletonProxy._
  import ActorRegistry._

  //----------------------------------------------------------------------------
  // Subscribe to MemberEvent, re-subscribe when restart

  override def preStart() {
    Cluster(context.system).subscribe(self, classOf[ClusterEvent.ClusterDomainEvent])
    log.info("ActorRegistry starts in cluster mode")

    val singletonPropsFactory: Option[Any] => Props = handOverData => {
      handOverData match {
        case None =>
          Props(classOf[LocalActorRegistry], MMap[String, ActorRef](), MMap[ActorRef, ArrayBuffer[String]]())

        case Some(any) =>
          val (name2Ref, ref2Names) = any.asInstanceOf[(MMap[String, ActorRef], MMap[ActorRef, ArrayBuffer[String]])]
          Props(classOf[LocalActorRegistry], name2Ref, ref2Names)
      }
    }
    val proxyProps = ClusterSingletonManager.props(
      singletonProps     = singletonPropsFactory,
      singletonName      = SINGLETON_NAME,
      terminationMessage = HandOver,
      role               = None)
    context.system.actorOf(proxyProps, PROXY_NAME)
  }

  override def postStop() {
    Cluster(context.system).unsubscribe(self)
  }

  //----------------------------------------------------------------------------
  // Sort by age, oldest first
  private val ageOrdering = Ordering.fromLessThan[Member] { (a, b) => a.isOlderThan(b) }
  private var membersByAge: SortedSet[Member] = SortedSet.empty(ageOrdering)

  //----------------------------------------------------------------------------

  def receive = {
    case clusterState: ClusterEvent.CurrentClusterState =>
      membersByAge = SortedSet.empty(ageOrdering) ++ clusterState.members

    case ClusterEvent.MemberUp(m) =>
      membersByAge += m

    case ClusterEvent.MemberRemoved(m, _) =>
      membersByAge -= m

    case other =>
      leader.foreach { _.tell(other, sender) }
  }

  private def leader: Option[ActorSelection] =
    membersByAge.headOption.map { m =>
      context.actorSelection(RootActorPath(m.address) / "user" / PROXY_NAME / SINGLETON_NAME)
    }
}
