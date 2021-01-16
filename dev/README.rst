Test
----

See build.sbt:

::

  // Uncomment the following line to test in cluster mode (with only one node)
  //unmanagedClasspath in Test <+= (baseDirectory) map { bd => Attributed.blank(bd / "config_example") }

Publish to local
----------------

While developing, you may need do local publish. Run
``sbt publishLocal``.
Alternatively you can run ``sbt`` then from SBT command prompt run
``+ publishLocal``.

To delete the local publish:

::

  $ find ~/.ivy2 -name *glokka* -delete

Publish to Sonatype
-------------------

See:
https://github.com/sbt/sbt.github.com/blob/gen-master/src/jekyll/using_sonatype.md

Create ~/.sbt/1.0/sonatype.sbt file:

::

  credentials += Credentials("Sonatype Nexus Repository Manager",
                             "oss.sonatype.org",
                             "<your username>",
                             "<your password>")

Then:

1. Copy content of
     dev/build.sbt.end   to the end of build.sbt
     dev/plugins.sbt.end to the end of project/plugins.sbt
2. Run ``sbt publishSigned``. Alternatively you can run ``sbt`` then from SBT
   command prompt run ``+ publishSigned``.
3. Login at https://oss.sonatype.org/ and from "Staging Repositories" select the
   newly published item, click "Close" then "Release".
