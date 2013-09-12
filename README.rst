Glokka = Global + Akka

Glokka is a Scala library that provides registration of global "name to actor
mapping" for Akka cluster. See:

* http://erlang.org/doc/man/global.html
* http://doc.akka.io/docs/akka/2.2.1/scala/cluster-usage.html

Glokka is used `Xitrum <http://ngocdaothanh.github.io/xitrum/>`_ to implement
its distributed `SockJS <https://github.com/sockjs/sockjs-client>`_ feature.

Start registry
--------------

In your SBT project's build.sbt:

::

  libraryDependencies += "tv.cntt" %% "glokka" % "1.2"

In your Scala code:

::

  import glokka.Registry

  val system   = ActorSystem("ClusterSystem")
  val registry = Registry.start(system = system, proxyName = "my proxy name")

You can start multiple registry actors. They must have different ``proxyName``.
``proxyName`` can be any String, you don't have to URI-escape it.

Register
--------

Send:

::

  // "name" can be any String, you don't have to URI-escape it.
  registry ! Registry.Register("name", actorRef to register)

You will receive:

::

  Registry.RegisterResultOk("name", actorRef)

Or:

::

  Registry.RegisterResultConflict("name", actorRef registered before at the name)

When the registered actor dies, it will be unregistered automatically.

Lookup
------

Send:

::

  registry ! Registry.Lookup("name")

You will receive:

::

  Registry.LookupResultOk("name", actorRef)

Or:

::

  Registry.LookupResultNone("name")

Lookup or create
----------------

If you want to lookup a named actor, and when it does not exist, create and
register it:

::

  registry ! Registry.LookupOrCreate("name", timeoutInSeconds = 1)

``timeoutInSeconds`` defaults to 5:

::

  registry ! Registry.LookupOrCreate("name")

You will receive:

::

  Registry.LookupResultOk("name", actorRef)

Or:

::

  Registry.LookupResultNone("name")

In the latter case, you have 5s (see above) to create actor and register:

::

  registry ! Registry.Register("name", actorRef to register)

You will receive either ``Registry.RegisterResultOk`` or
``Registry.RegisterResultConflict`` (see the Register section above).

To cancel:

::

  registry ! Registry.CancelCreate("name")

During the wait time, if there are lookup or register messages sent to the registry
(e.g. from other places), they will be temporarily pended. They will be processed
after you send ``Register`` or ``CancelCreate`` or timeout occurs.

Cluster
-------

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
