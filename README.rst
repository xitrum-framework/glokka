Glokka = Global + Akka

Glokka is a Scala library that provides registration of global names for Akka
cluster. See:

* http://erlang.org/doc/man/global.html
* http://doc.akka.io/docs/akka/2.2.1/scala/cluster-usage.html

Usage
-----

In your SBT project's build.sbt:

::

  libraryDependencies += "tv.cntt" %% "glokka" % "1.1"

In your Scala code:

::

  import glokka.ActorRegistry

  val system   = ActorSystem("ClusterSystem")
  val registry = ActorRegistry.start(system = system, proxyName = "my proxy name")

  // Send this from inside an actor. The sender actor will receive:
  // ActorRegistry.RegisterResultOk("name", actorRef) or
  // ActorRegistry.RegisterResultConflict("name", actorRef registered before with the same name).
  //
  // "name" can be any String, you don't have to URI-escape it.
  //
  // When the registered actor dies, it will be unregistered automatically.
  registry ! ActorRegistry.Register("name", actorRef)

  // Send this from inside an actor. The sender actor will receive:
  // ActorRegistry.LookupResultOk("name", actorRef) or
  // ActorRegistry.LookupResultNone("name")
  registry ! ActorRegistry.Lookup("name")

You can start multiple registry actors. They must have different ``proxyName``.

You can also use `future <http://doc.akka.io/docs/akka/2.2.1/scala/futures.html>`_
instead of running inside an actor. See src/test for examples.

Notes
-----

Glokka can run in Akka non-cluster mode (local or remote). While developing, you
can run Akka in local mode, then later config Akka to run in cluster mode.

In cluster mode, Glokka uses
`Akka's Cluster Singleton Pattern <http://doc.akka.io/docs/akka/2.2.1/contrib/cluster-singleton.html>`_
to maintain an actor that stores the name -> actorRef lookup table.

Akka config file for a node should look like this (note "ClusterSystem" in the
source code example above and the config below):

::

  akka {
    actor {
      provider = "akka.cluster.ClusterActorRefProvider"
    }

    remote {
      log-remote-lifecycle-events = off
      netty.tcp {
        hostname = "127.0.0.1"
        port = 2551
      }
    }

    cluster {
      seed-nodes = [
        "akka.tcp://ClusterSystem@127.0.0.1:2551",
        "akka.tcp://ClusterSystem@127.0.0.1:2552"]

      auto-down = on
    }
  }
