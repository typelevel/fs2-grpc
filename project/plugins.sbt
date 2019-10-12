addSbtPlugin("io.get-coursier" % "sbt-coursier" % "1.0.3")
addSbtPlugin("org.xerial.sbt"   % "sbt-sonatype" % "3.7")
addSbtPlugin("com.jsuereth"     % "sbt-pgp"      % "2.0.0")
addSbtPlugin("com.typesafe.sbt" % "sbt-git"      % "1.0.0")

addSbtPlugin("com.eed3si9n"     % "sbt-buildinfo" % "0.9.0")
addSbtPlugin("org.scalameta"     % "sbt-scalafmt"  % "2.0.5")
addSbtPlugin("com.timushev.sbt" % "sbt-updates"   % "0.4.2")

addSbtPlugin("com.typesafe" % "sbt-mima-plugin" % "0.6.1")

addSbtPlugin("io.github.davidgregory084" % "sbt-tpolecat" % "0.1.8")

libraryDependencies += "com.thesamet.scalapb" %% "compilerplugin" % "0.9.2"
