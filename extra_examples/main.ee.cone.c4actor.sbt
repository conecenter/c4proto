
libraryDependencies += "org.scalameta" %% "scalameta" % "4.2.3"

enablePlugins(JavaServerAppPackaging)
mainClass in Compile := Some("ee.cone.c4actor.ServerMain")