package glokka

import java.net.URLEncoder
import akka.actor.{ActorSystem, Props}
import com.typesafe.config.ConfigFactory

object ActorRegistry {
  case class Lookup(name: String)
  case class LookupOrCreate(name: String, propsMaker: () => Props)

  //----------------------------------------------------------------------------

  private val system = ActorSystem("glokka")

  private val actorRef = {
    val config = ConfigFactory.load()
    if (config.getString("akka.actor.provider") == "akka.actor.ActorRefProvider")
      system.actorOf(Props[LocalActorRegistry], LocalActorRegistry.ACTOR_NAME)
    else if (config.getString("akka.actor.provider") == "akka.cluster.ClusterActorRefProvider")
      system.actorOf(Props[ClusterActorRegistry], ClusterActorRegistry.ACTOR_NAME)
    else
      throw new Exception("Clokka only supports akka.actor.ActorRefProvider or akka.cluster.ClusterActorRefProvider")
  }

  /** Should be called at application start. */
  def start() {
    // The registry is started at actorRef above
  }

  /** The calling actor will receive Option[ActorRef] */
  def lookup(name: String) {
    actorRef ! Lookup(name)
  }

  /**
   * The calling actor will receive tuple (newlyCreated: Boolean, actorRef: ActorRef).
   *
   * @param propsMaker Used to create the actor if it does not exist
   */
  def lookupOrCreate(name: String, propsMaker: () => Props) {
    actorRef ! LookupOrCreate(name, propsMaker)
  }

  def escape(name: String) = URLEncoder.encode(name, "UTF-8")
}
