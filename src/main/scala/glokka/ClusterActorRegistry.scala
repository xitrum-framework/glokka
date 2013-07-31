/*
package glokka

import akka.actor.{
  Actor, ActorLogging, ActorRef,
  Identify, ActorIdentity,
  Address
}

import akka.cluster.{Cluster, ClusterEvent, Member, MemberStatus}

object ClusterActorRegistry {
  val ACTOR_NAME = ActorRegistry.escape(getClass.getName)
}

class ClusterActorRegistry extends Actor with ActorLogging {
  import ActorRegistry._
  import ClusterActorRegistry._

  private var leader: Option[Address] = None
  private var otherRegistryRefs: Seq[ActorRef] = Seq.empty

  override def preStart() {
    Cluster(context.system).subscribe(self, classOf[ClusterEvent.ClusterDomainEvent])
    log.info("ActorRegistry started: " + self)
  }

  def receive = {
    case state: ClusterEvent.CurrentClusterState =>
      leader = state.leader
      otherRegistryRefs = if (leader.isDefined) {
        state.members.filter(_.status == MemberStatus.Up).map(_.address)
      } else {
        Seq.empty
      }

    case ClusterEvent.LeaderChanged(leader) =>
      this.leader = leader

    case _: ClusterEvent.ClusterDomainEvent =>
      // Ignore

    //--------------------------------------------------------------------------

    case Lookup(name) =>
      val sel = context.actorSelection(escape(name))
      val sed = sender
      sel ! Identify(IdentifyForLookup(sed))

    case LookupOrCreate(name, propsMaker) =>

    //--------------------------------------------------------------------------

    case ActorIdentity(IdentifyForLookup(sed), opt) =>
      if (opt.isDefined)
        sed ! opt
      else {

      }

    case ActorIdentity(IdentifyForLookupOrCreate(sed, _, _), Some(actorRef)) =>
      sed ! (false, actorRef)

    case ActorIdentity(IdentifyForLookupOrCreate(sed, propsMaker, escapedName), None) =>
      val actorRef = context.actorOf(propsMaker(), escapedName)
      sed ! (true, actorRef)
  }
}
*/
