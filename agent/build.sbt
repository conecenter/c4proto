
scalaVersion := "2.13.8"

//Compile / unmanagedSourceDirectories := Seq(baseDirectory.value / "src")

Compile / unmanagedSources := Seq(baseDirectory.value / "server.scala")

libraryDependencies ++= Seq(
  "io.undertow" % "undertow-core" % "2.2.19.Final",
  "com.lihaoyi" %% "os-lib" % "0.8.1",
  "com.lihaoyi" %% "upickle" % "1.6.0",
  "com.auth0" % "java-jwt" % "3.19.1",
  "com.auth0" % "jwks-rsa" % "0.21.1",
)
