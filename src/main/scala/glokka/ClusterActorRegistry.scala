package gloakka

object RemoteActorRegistry {
  val ACTOR_NAME = ActorRegistry.escape(getClass.getName)

  private case class LookupLocal(name: String)
  private val LOOKUP_TIMEOUT = 5.seconds
}

class RemoteActorRegistry extends Actor with Logger {
  import ActorRegistry._
  import RemoteActorRegistry._

  private var addrs:     IMap[String, String] = _
  private var localAddr: String               = _

  override def preStart() {
    addrs = Config.hazelcastInstance.getMap("xitrum/RemoteActorRegistry")

    val h    = Config.hazelcastInstance
    val lock = h.getLock(ACTOR_NAME)
    lock.lock()
    try {
      // Register local node
      val cluster = h.getCluster
      val id      = cluster.getLocalMember.getUuid
      val nc      = Config.application.getConfig("akka.remote.netty.tcp")
      val host    = nc.getString("hostname")
      val port    = nc.getInt("port")
      localAddr   = host + ":" + port
      addrs.put(id, localAddr)

      cluster.addMembershipListener(new MembershipListener {
        // See "Register local node" above. The added member will update the
        // distributed map addrs. No need to do anything here.
        def memberAdded(membershipEvent: MembershipEvent) {}

        // Unregister remote dead node.
        def memberRemoved(membershipEvent: MembershipEvent) {
          val id = membershipEvent.getMember.getUuid
          addrs.remove(id)
        }
      })
    } finally {
      lock.unlock()
    }
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
