Glokka = Global + Akka

Glokka is a Scala library that provides registration of global "name to actor
mapping" for Akka cluster. See:

* `Erlang's "global" module <http://erlang.org/doc/man/global.html>`_
* `Akka's cluster feature <http://doc.akka.io/docs/akka/2.2.3/scala/cluster-usage.html>`_

Glokka is used in `Xitrum <http://ngocdaothanh.github.io/xitrum/>`_ to implement
its distributed `SockJS <https://github.com/sockjs/sockjs-client>`_ feature.

See `Glokka's Scaladoc <http://ngocdaothanh.github.io/glokka>`_.

Start registry
--------------

In your SBT project's build.sbt:

::

  libraryDependencies += "tv.cntt" %% "glokka" % "1.5"

In your Scala code:

::

  import glokka.Registry

  val system   = ActorSystem("ClusterSystem")
  val registry = Registry.start(system = system, proxyName = "my proxy name")

You can start multiple registry actors. They must have different ``proxyName``.
For convenience, ``proxyName`` can be any String, you don't have to URI-escape it.

Register
--------

Send:

::

  // For convenience, "name" can be any String, you don't have to URI-escape it.
  registry ! Registry.RegisterByRef("name", actor ref to register)

Or:

::

  // The registry may its own actor system to create the actor ref to register.
  registry ! Registry.RegisterByProps("name", props)

Or:

::

  // The registry may use the specified actor system to create the actor ref to register.
  registry ! Registry.RegisterBySystemAndProps("name", actor system, props)

You will receive:

::

  Registry.RegisterResultOk("name", actor ref)

Or:

::

  Registry.RegisterResultConflict("name", actor ref that's already been registered before your call)

When the registered actor dies, it will be unregistered automatically.

Lookup
------

Send:

::

  registry ! Registry.Lookup("name")

You will receive:

::

  Registry.LookupResultOk("name", actor ref)

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

  Registry.LookupResultOk("name", actor ref)

Or:

::

  Registry.LookupResultNone("name")

In case of ``LookupResultNone``, you have 5s (see above) to register (see the
Register section). You will receive either ``Registry.RegisterResultOk`` or
``Registry.RegisterResultConflict``.

If you don't want to register any more:

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
`Akka's Cluster Singleton Pattern <http://doc.akka.io/docs/akka/2.2.3/contrib/cluster-singleton.html>`_
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
