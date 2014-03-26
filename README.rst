Glokka = Global + Akka

Glokka is a Scala library that provides registration of global "name to actor
mapping" for Akka cluster. See:

* `Erlang's "global" module <http://erlang.org/doc/man/global.html>`_
* `Akka's cluster feature <http://doc.akka.io/docs/akka/2.3.0/scala/cluster-usage.html>`_

Glokka is used in `Xitrum <http://ngocdaothanh.github.io/xitrum/>`_ to implement
its distributed `SockJS <https://github.com/sockjs/sockjs-client>`_ feature.

See `Glokka's Scaladoc <http://ngocdaothanh.github.io/glokka>`_.

Start registry
--------------

In your SBT project's build.sbt:

::

  libraryDependencies += "tv.cntt" %% "glokka" % "1.8"

In your Scala code:

::

  import akka.actor.ActorSystem
  import glokka.Registry

  val system    = ActorSystem("MyClusterSystem")
  val proxyName = "my proxy name"
  val registry  = Registry.start(system, proxyName)

* You can start multiple registry actors. They must have different ``proxyName``.
* For convenience, ``proxyName`` can be any String, you don't have to URI-escape it.

Register actor to the registry under a name
-------------------------------------------

Send:

::

  // For convenience, ``actorName`` can be any String, you don't have to URI-escape it.
  val actorName = "my actor name"

  // Props to create the actor you want to register.
  val props = ...

  registry ! Registry.Register(actorName, props)

If the named actor exists, the registry will just return it. You will receive:

::

  Registry.Found(actorName, actorRef)

Otherwise ``props`` will be used to create the actor locally (when the actor dies,
it will be unregistered automatically). You will receive:

::

  Registry.Created(actorName, actorRef)

If you don't need to differentiate ``Found`` and ``Created``:

::

  registry ! Registry.Register(actorName, props)
  context.become {
    case msg: Registry.FoundOrCreated =>
      val actorName = msg.name
      val actorRef  = msg.ref
  }

Lookup named actor in the registry
----------------------------------

Send:

::

  registry ! Lookup(actorName)

You will receive:

::

  Found(actorName, actorRef)

Or:

::

  NotFound(actorName)

Cluster
-------

Glokka can run in Akka non-cluster mode (local or remote). While developing, you
can run Akka in local mode, then later config Akka to run in cluster mode.

In cluster mode, Glokka uses
`Akka's Cluster Singleton Pattern <http://doc.akka.io/docs/akka/2.3.0/contrib/cluster-singleton.html>`_
to maintain an actor that stores the name -> actorRef lookup table.

Akka config file for a node should look like this (note "ClusterSystem" in the
source code example above and the config below):

::

  akka {
    actor {
      provider = "akka.cluster.ClusterActorRefProvider"
    }

    # This node
    remote {
      log-remote-lifecycle-events = off
      netty.tcp {
        hostname = "127.0.0.1"
        port = 2551  # 0 means random port
      }
    }

    cluster {
      seed-nodes = [
        "akka.tcp://MyClusterSystem@127.0.0.1:2551",
        "akka.tcp://MyClusterSystem@127.0.0.1:2552"]

      auto-down-unreachable-after = 10s
    }
  }
