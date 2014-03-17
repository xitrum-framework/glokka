package glokka

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.event.Logging
import com.typesafe.config.ConfigFactory

// Only things in this file are public and appear in the Scaladoc.
// Other things are private.

object Registry {
  case class Register(name: String, props: Props)
  case class Lookup(name: String)

  abstract class FoundOrCreated { def name: String; def ref: ActorRef }

  case class Found(name: String, ref: ActorRef) extends FoundOrCreated
  case class Created(name: String, ref: ActorRef) extends FoundOrCreated

  case class NotFound(name: String)

  private val clusterMode: Boolean = {
    val config   = ConfigFactory.load()
    val provider = config.getString("akka.actor.provider")
    provider == "akka.cluster.ClusterActorRefProvider"
  }

  /** @return The registry */
  def start(system: ActorSystem, proxyName: String): ActorRef = {
    // Local mode:
    // localRegistry

    // Cluster mode:
    // clusterSingletonProxy -> clusterSingletonManager -> clusterSingletonRegistry
    //
    // When registering, actor ref is created from props locally at
    // clusterSingletonProxy.

    val log = Logging.getLogger(system, this)
    if (clusterMode) {
      log.info(s"""Glokka actor registry "$proxyName" starts in cluster mode""")
      system.actorOf(Props(classOf[ClusterSingletonProxy], proxyName))
    } else {
      log.info(s"""Glokka actor registry "$proxyName" starts in local mode""")
      system.actorOf(Props(classOf[LocalRegistry]))
    }
  }
}
