// Most Scala projects are published to Sonatype, but Sonatype is not default
// and it takes several hours to sync from Sonatype to Maven Central
resolvers += "SonatypeReleases" at "http://oss.sonatype.org/content/repositories/releases/"

// Run sbt eclipse to create Eclipse project file
addSbtPlugin("com.typesafe.sbteclipse" % "sbteclipse-plugin" % "2.3.0")

// Run sbt gen-idea to create IntelliJ project file
addSbtPlugin("com.github.mpeltonen" % "sbt-idea" % "1.5.1")
