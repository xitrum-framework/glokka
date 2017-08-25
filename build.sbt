organization       := "tv.cntt"
name               := "glokka"
version            := "2.5.0-SNAPSHOT"

//------------------------------------------------------------------------------

// Akka 2.4.0+ dropped Scala 2.10.x support
crossScalaVersions := Seq("2.12.3", "2.11.11")
scalaVersion       := "2.12.3"

// Akka 2.4.0+ requires Java 8
javacOptions ++= Seq("-source", "1.8", "-target", "1.8")
scalacOptions ++= Seq("-deprecation", "-feature", "-unchecked")

//------------------------------------------------------------------------------

libraryDependencies += "com.typesafe.akka" %% "akka-actor"   % "2.5.4" % "provided"
libraryDependencies += "com.typesafe.akka" %% "akka-cluster" % "2.5.4" % "provided"
libraryDependencies += "com.typesafe.akka" %% "akka-contrib" % "2.5.4" % "provided"

libraryDependencies += "org.specs2" %% "specs2-core" % "3.9.4" % "test"

//------------------------------------------------------------------------------

// For "sbt console", used while developing for cluster mode
unmanagedClasspath in Compile += { Attributed.blank(baseDirectory.value / "config_example") }

// Enable the following line to test in cluster mode (with only one node)
//unmanagedClasspath in Test += { Attributed.blank(baseDirectory.value / "config_example") }

//------------------------------------------------------------------------------

// Skip API doc generation to speedup "publish-local" while developing.
// Comment out this line when publishing to Sonatype.
//publishArtifact in (Compile, packageDoc) := false
