organization       := "tv.cntt"

name               := "glokka"

version            := "2.1-SNAPSHOT"

scalaVersion       := "2.11.4"

crossScalaVersions := Seq("2.11.4", "2.10.4")

scalacOptions ++= Seq("-deprecation", "-feature", "-unchecked")

// http://www.scala-sbt.org/release/docs/Detailed-Topics/Java-Sources
// Avoid problem when this lib is built with Java 7 but the projects that use it
// are run with Java 6
// java.lang.UnsupportedClassVersionError: Unsupported major.minor version 51.0
javacOptions ++= Seq("-source", "1.6", "-target", "1.6")

libraryDependencies += "com.typesafe.akka" %% "akka-actor" % "2.3.7" % "provided"

libraryDependencies += "com.typesafe.akka" %% "akka-cluster" % "2.3.7" % "provided"

libraryDependencies += "com.typesafe.akka" %% "akka-contrib" % "2.3.7" % "provided"

libraryDependencies += "org.specs2" %% "specs2-core" % "2.4.11" % "test"

// For "sbt console", used while developing for cluster mode
unmanagedClasspath in Compile <+= (baseDirectory) map { bd => Attributed.blank(bd / "config_example") }

// Enable the following line to test in cluster mode (with only one node)
//unmanagedClasspath in Test <+= (baseDirectory) map { bd => Attributed.blank(bd / "config_example") }

//------------------------------------------------------------------------------

// Skip API doc generation to speedup "publish-local" while developing.
// Comment out this line when publishing to Sonatype.
publishArtifact in (Compile, packageDoc) := false
