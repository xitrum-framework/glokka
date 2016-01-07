organization       := "tv.cntt"
name               := "glokka"
version            := "2.4-SNAPSHOT"

//------------------------------------------------------------------------------

// Akka 2.4.0+ dropped Scala 2.10.x support
scalaVersion       := "2.11.7"
crossScalaVersions := Seq("2.11.7")

scalacOptions ++= Seq("-deprecation", "-feature", "-unchecked")

// Akka 2.4.0+ requires Java 8
javacOptions ++= Seq("-source", "1.8", "-target", "1.8")

//------------------------------------------------------------------------------

libraryDependencies += "com.typesafe.akka" %% "akka-actor"   % "2.4.1" % "provided"
libraryDependencies += "com.typesafe.akka" %% "akka-cluster" % "2.4.1" % "provided"
libraryDependencies += "com.typesafe.akka" %% "akka-contrib" % "2.4.1" % "provided"

libraryDependencies += "org.specs2" %% "specs2-core" % "2.4.11" % "test"

//------------------------------------------------------------------------------

// For "sbt console", used while developing for cluster mode
unmanagedClasspath in Compile <+= (baseDirectory) map { bd => Attributed.blank(bd / "config_example") }

// Enable the following line to test in cluster mode (with only one node)
//unmanagedClasspath in Test <+= (baseDirectory) map { bd => Attributed.blank(bd / "config_example") }

//------------------------------------------------------------------------------

// Skip API doc generation to speedup "publish-local" while developing.
// Comment out this line when publishing to Sonatype.
publishArtifact in (Compile, packageDoc) := false
