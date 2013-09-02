package glokka

import scala.collection.mutable.{ArrayBuffer, Map => MMap}
import akka.actor.{ActorRef, ActorSystem, Props}
import com.typesafe.config.ConfigFactory

object ActorRegistry {
  object HandOver

  case class Register(name: String, actorRef: ActorRef)
  case class RegisterResultOk(name: String, actorRef: ActorRef)
  case class RegisterResultConflict(name: String, actorRef: ActorRef)

  case class Lookup(name: String)
  case class LookupResultOk(name: String, actorRef: ActorRef)
  case class LookupResultNone(name: String)

  def start(system: ActorSystem): ActorRef = {
    val config   = ConfigFactory.load()
    val provider = config.getString("akka.actor.provider")

    if (provider == "akka.cluster.ClusterActorRefProvider")
      system.actorOf(Props[ClusterActorRegistrySingletonProxy])
    else
      system.actorOf(Props(classOf[LocalActorRegistry], MMap[String, ActorRef](), MMap[ActorRef, ArrayBuffer[String]]()))
  }
}
