addSbtPlugin("org.typelevel" % "sbt-typelevel" % "0.7.0")

addSbtPlugin("com.eed3si9n" % "sbt-buildinfo" % "0.12.0")
addSbtPlugin("com.eed3si9n" % "sbt-projectmatrix" % "0.10.0")

addSbtPlugin("com.timushev.sbt" % "sbt-updates" % "0.6.4")

addSbtPlugin("com.thesamet" % "sbt-protoc" % "1.0.7") // Because sbt-protoc-gen-project brings in 1.0.4
addSbtPlugin("com.thesamet" % "sbt-protoc-gen-project" % "0.1.8")
libraryDependencies += "com.thesamet.scalapb" %% "compilerplugin" % "0.11.15"
