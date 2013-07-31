glokka = global + akka

This Scala library provides registration of global names for cluster Akka.
See http://erlang.org/doc/man/global.html.

Your ``akka.actor.provider`` should be ``akka.actor.LocalActorRefProvider``.
``akka.cluster.ClusterActorRefProvider`` will be supported in the future.

Usage
-----

In your SBT project's build.sbt:

::

  libraryDependencies += "tv.cntt" %% "glokka" % "1.0"

In your Scala code:

::

  import glokka.ActorRegistry

  // Need to start once
  ActorRegistry.start()

  // Send this from inside an actor.
  // The sender actor will receive Option[ActorRef].
  ActorRegistry.actorRef ! ActorRegistry.Lookup("registeredName")

  // Send this from inside an actor.
  // The sender actor will receive tuple (newlyCreated: Boolean, actorRef: ActorRef).
  //
  // propsMaker is of type "() => akka.actor.Props". It is a function that returns
  // Props used to create the actor if it does not exist.
  ActorRegistry.actorRef ! ActorRegistry.LookupOrCreate("registeredName", propsMaker)
