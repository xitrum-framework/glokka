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

  // Need to start once
  ActorRegistry.start()

  // Send this from inside an actor. The sender actor will receive:
  // ActorRegistry.RegisterResultOK("name", actorRef) or
  // ActorRegistry.RegisterResultConflict("name", actorRef registered before with the same name).
  //
  // When the registered actor dies, it will be unregistered automatically.
  ActorRegistry.actorRef ! ActorRegistry.Register("name", actorRef)

  // Send this from inside an actor. The sender actor will receive:
  // ActorRegistry.LookupResultOK("name", actorRef) or
  // ActorRegistry.LookupResultNone("name")
  ActorRegistry.actorRef ! ActorRegistry.Lookup("name")

Notes
-----

Glokka can run in Akka non-cluster mode (local or remote). While developing, you
can run Akka in local mode, then later config Akka to run in cluster mode.

In cluster mode, Glokka uses Akka's Cluster Singleton Pattern to maintain an
actor that stores the name -> actorRef lookup table.
