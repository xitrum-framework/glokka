package glokka

import java.net.URLEncoder

import scala.collection.immutable.SortedSet
import scala.collection.mutable.{ArrayBuffer, Map => MMap}

import akka.actor.{Actor, ActorLogging, ActorRef, ActorSelection, PoisonPill, Props, RootActorPath}
import akka.cluster.{Cluster, ClusterEvent, Member}
import akka.contrib.pattern.ClusterSingletonManager

object RegistryClusterSingletonProxy {
  // This should be URL escaped
  val SINGLETON_NAME = URLEncoder.encode("GlokkaActorRegistry", "UTF-8")
}

object HandOver

class RegistryClusterSingletonProxy(proxyName: String) extends Actor with ActorLogging {
  import RegistryClusterSingletonProxy._
  import Registry._

  private[this] val escapedProxyName = URLEncoder.encode(proxyName, "UTF-8")

  //----------------------------------------------------------------------------
  // Subscribe to MemberEvent, re-subscribe when restart

  override def preStart() {
    Cluster(context.system).subscribe(self, classOf[ClusterEvent.ClusterDomainEvent])

    val singletonPropsFactory: Option[Any] => Props = handOverData => {
      handOverData match {
        case None =>
          Props(classOf[Registry], false, MMap[String, ActorRef](), MMap[ActorRef, ArrayBuffer[String]]())

        case Some(any) =>
          val (name2Ref, ref2Names) = any.asInstanceOf[(MMap[String, ActorRef], MMap[ActorRef, ArrayBuffer[String]])]
          Props(classOf[Registry], false, name2Ref, ref2Names)
      }
    }
    val proxyProps = ClusterSingletonManager.props(
      singletonProps     = singletonPropsFactory,
      singletonName      = SINGLETON_NAME,
      terminationMessage = HandOver,
      role               = None)

    // Must use context.system.actorOf instead of context.actorOf, so that the
    // created actor is attached to the system, so that we have a stable path
    // to select later (see below)
    context.system.actorOf(proxyProps, escapedProxyName)
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
      context.actorSelection(RootActorPath(m.address) / "user" / escapedProxyName / SINGLETON_NAME)
    }
}
