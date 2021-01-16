organization := "tv.cntt"
name         := "glokka"
version      := "2.6.2-SNAPSHOT"

//------------------------------------------------------------------------------

crossScalaVersions := Seq("2.13.4", "2.12.13")
scalaVersion       := "2.13.4"

javacOptions  ++= Seq("-source", "1.8", "-target", "1.8")
scalacOptions ++= Seq("-deprecation", "-feature", "-unchecked")

//------------------------------------------------------------------------------

libraryDependencies += "com.typesafe.akka" %% "akka-actor"         % "2.6.11"
libraryDependencies += "com.typesafe.akka" %% "akka-cluster"       % "2.6.11"
libraryDependencies += "com.typesafe.akka" %% "akka-cluster-tools" % "2.6.11"

libraryDependencies += "org.specs2" %% "specs2-core" % "4.10.5" % "test"

//------------------------------------------------------------------------------

// For "sbt console", used while developing for cluster mode
unmanagedClasspath in Compile += { Attributed.blank(baseDirectory.value / "config_example") }

// Uncomment the following line to test in cluster mode (with only one node)
//unmanagedClasspath in Test += { Attributed.blank(baseDirectory.value / "config_example") }

//------------------------------------------------------------------------------

// Skip API doc generation to speedup "publishLocal" while developing.
// Comment out this line when publishing to Sonatype.
publishArtifact in (Compile, packageDoc) := false
