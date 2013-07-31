package glokka

import java.net.URLEncoder
import akka.actor.{ActorSystem, ActorRef, Props}
import com.typesafe.config.ConfigFactory

object ActorRegistry {
  /**
   * Send this from inside an actor.
   * The sender actor will receive Option[ActorRef].
   */
  case class Lookup(name: String)

  /**
   * Send this from inside an actor.
   * The sender actor will receive tuple (newlyCreated: Boolean, actorRef: ActorRef).
   * propsMaker is used to create the actor if it does not exist.
   */
  case class LookupOrCreate(name: String, propsMaker: () => Props)

  /** Result of "Lookup", used internally by Glokka */
  case class IdentifyForLookup(sed: ActorRef)

  /** Result of "LookupOrCreate", used internally by Glokka */
  case class IdentifyForLookupOrCreate(sed: ActorRef, propsMaker: () => Props, escapedName: String)

  //----------------------------------------------------------------------------

  val system = ActorSystem("glokka")

  val actorRef = {
    val config   = ConfigFactory.load()
    val provider = config.getString("akka.actor.provider")

//    if (provider == "akka.actor.LocalActorRefProvider")
//      system.actorOf(Props[LocalActorRegistry], LocalActorRegistry.ACTOR_NAME)
//    else if (provider == "akka.cluster.ClusterActorRefProvider")
//      system.actorOf(Props[ClusterActorRegistry], ClusterActorRegistry.ACTOR_NAME)
//    else
//      throw new Exception(
//          "akka.actor.provider not supported: " + provider +
//          ". Glokka only supports akka.actor.LocalActorRefProvider or akka.cluster.ClusterActorRefProvider")
    if (provider == "akka.actor.LocalActorRefProvider")
      system.actorOf(Props[LocalActorRegistry], LocalActorRegistry.ACTOR_NAME)
    else
      throw new Exception(
          "akka.actor.provider not supported: " + provider +
          ". Glokka only supports akka.actor.LocalActorRefProvider")

  }

  /** Should be called at application start. */
  def start() {
    // actorRef above should have been started
  }

  def escape(name: String) = URLEncoder.encode(name, "UTF-8")
}
