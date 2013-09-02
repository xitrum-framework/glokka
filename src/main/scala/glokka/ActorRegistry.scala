package glokka

import java.net.URLEncoder
import akka.actor.{ActorRef, ActorSystem, Props}
import com.typesafe.config.ConfigFactory

object ActorRegistry {
  case class Register(name: String, actorRef: ActorRef)
  case class RegisterResultOK(name: String, actorRef: ActorRef)
  case class RegisterResultConflict(name: String, actorRef: ActorRef)

  case class Lookup(name: String)
  case class LookupResultOK(name: String, actorRef: ActorRef)
  case class LookupResultNone(name: String)

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
    if (provider == "akka.cluster.ClusterActorRefProvider")
      throw new Exception(
          "akka.actor.provider not supported: " + provider +
          ". Glokka only supports akka.actor.LocalActorRefProvider")
    else
      system.actorOf(Props[LocalActorRegistry], LocalActorRegistry.ACTOR_NAME)
  }

  /** Should be called at application start. */
  def start() {
    // actorRef above should have been started
  }

  def escape(name: String) = URLEncoder.encode(name, "UTF-8")
}
