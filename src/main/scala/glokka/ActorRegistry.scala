package glokka

import java.net.URLEncoder

import scala.collection.mutable.{Map => MMap}
import scala.concurrent.duration._
import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.control.NonFatal

import akka.actor.{Actor, ActorRef, Props, Identify, ActorIdentity}
import akka.pattern.ask

object ActorRegistry extends Logger {
  case class Lookup(name: String)
  case class LookupOrCreate(name: String, propsMaker: () => Props)

  //----------------------------------------------------------------------------

  private val actorRef = {
    if (Config.application.getString("akka.actor.provider") == "akka.remote.RemoteActorRefProvider")
      Config.actorSystem.actorOf(Props[RemoteActorRegistry], RemoteActorRegistry.ACTOR_NAME)
    else
      Config.actorSystem.actorOf(Props[LocalActorRegistry], LocalActorRegistry.ACTOR_NAME)
  }

  /** Should be called at application start. */
  def start() {
    logger.info("ActorRegistry started: " + actorRef)
  }

  /** The calling actor will receive Option[ActorRef] */
  def lookup(name: String) {
    actorRef ! Lookup(name)
  }

  /**
   * The calling actor will receive tuple (newlyCreated, actorRef).
   *
   * @param propsMaker Used to create the actor if it does not exist
   */
  def lookupOrCreate(name: String, propsMaker: () => Props) {
    actorRef ! LookupOrCreate(name, propsMaker)
  }

  def escape(name: String) = URLEncoder.encode(name, "UTF-8")
}
