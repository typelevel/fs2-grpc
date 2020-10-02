addSbtPlugin("io.get-coursier" % "sbt-coursier" % "2.0.0")
addSbtPlugin("org.xerial.sbt" % "sbt-sonatype" % "3.9.4")
addSbtPlugin("com.jsuereth" % "sbt-pgp" % "2.0.1")
addSbtPlugin("com.typesafe.sbt" % "sbt-git" % "1.0.0")

addSbtPlugin("com.eed3si9n" % "sbt-buildinfo" % "0.10.0")
addSbtPlugin("org.scalameta" % "sbt-scalafmt" % "2.4.2")
addSbtPlugin("com.timushev.sbt" % "sbt-updates" % "0.5.1")

addSbtPlugin("com.typesafe" % "sbt-mima-plugin" % "0.8.0")

addSbtPlugin("io.github.davidgregory084" % "sbt-tpolecat" % "0.1.13")

libraryDependencies += "com.thesamet.scalapb" %% "compilerplugin" % "0.10.8"
