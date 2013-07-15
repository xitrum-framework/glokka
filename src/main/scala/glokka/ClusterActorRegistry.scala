package glokka

import akka.actor.{Actor, ActorLogging}

object ClusterActorRegistry {
  val ACTOR_NAME = ActorRegistry.escape(getClass.getName)

  private case class LookupLocal(name: String)
  private val LOOKUP_TIMEOUT = 5.seconds
}

class ClusterActorRegistry extends Actor with ActorLogging {
  import ActorRegistry._
  import ClusterActorRegistry._

  override def preStart() {
    log.info("ActorRegistry started: " + self)
  }

/*
  def receive = {
    case Lookup(name) =>
      val it = addrs.values().iterator
      while (it.hasNext()) {
        val addr = it.next()
        val refo = if (addr == localAddr) lookupLocal(name) else lookupRemote(addr, name)
        if (refo.isDefined) return refo
      }

    case LookupOrCreate(name, propsMaker) =>
      sender ! lookupOrCreate(name, propsMaker)

    //--------------------------------------------------------------------------

    case LookupLocal(name: String) =>
      sender ! lookupLocal(name)
  }
*/

  //----------------------------------------------------------------------------

  private def lookupLocal(name: String): Option[ActorRef] = {
    val sel    = context.actorSelection(escape(name))
    val future = sel.ask(Identify(None))(LOOKUP_TIMEOUT).mapTo[ActorIdentity].map(_.ref)

    try {
      Await.result(future, LOOKUP_TIMEOUT)
    } catch {
      case NonFatal(e) =>
        logger.warn("lookupLocal Await error, name: " + name, e)
        None
    }
  }
  /*

  private def lookupLocal(name: String): Option[ActorRef] = {
    val sel    = context.actorSelection(escape(name))
    sel ! Identify(None)
    context.become(receivex)
    None
  }

  def receivex: PartialFunction[Any, Unit] = {
    case ActorIdentity(_, Some(ref)) ‚áí
      println("---------- " + ref)
    case ActorIdentity(_, None) =>

      println("---------- " + None)

  }
*/
  private def lookupRemote(addr: String, name: String): Option[ActorRef] = {
    val url    = "akka.tcp://" + Config.ACTOR_SYSTEM_NAME + "@" + addr + "/user/" + ACTOR_NAME
    val sel    = context.actorSelection(url)
    val future = sel.ask(LookupLocal(name))(LOOKUP_TIMEOUT).mapTo[Option[ActorRef]]
    try {
      Await.result(future, LOOKUP_TIMEOUT)
    } catch {
      case NonFatal(e) =>
        logger.warn("lookupRemote Await error, addr: " + addr + ", name: " + name, e)
        None
    }
  }

  /** If the actor has not been created, it will be created locally. */
  private def lookupOrCreate(name: String, propsMaker: () => Props): (Boolean, ActorRef) = {
    val lock = Config.hazelcastInstance.getLock(ACTOR_NAME + name)
    lock.lock()
    try {
      val it = addrs.values().iterator
      while (it.hasNext()) {
        val addr = it.next()
        val refo = if (addr == localAddr) lookupLocal(name) else lookupRemote(addr, name)
        if (refo.isDefined) return (false, refo.get)
      }

      // Create local actor

      println("-----------Create local actor")
      (true, context.actorOf(propsMaker(), escape(name)))
    } finally {
      lock.unlock()
    }
  }
}
