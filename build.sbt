
lazy val publishSettings = Seq(
  organization := "ee.cone",
  version := "0.1.4",
  fork := true //looks like sbt hangs for a minute on System.exit
)

scalaVersion in ThisBuild := "2.11.8"

////////////////////////////////////////////////////////////////////////////////
// from https://github.com/scalameta/sbt-macro-example/blob/master/build.sbt

lazy val metaMacroSettings: Seq[Def.Setting[_]] = Seq(
  libraryDependencies += "org.scalameta" %% "scalameta" % "1.4.0.544",
  // New-style macro annotations are under active development.  As a result, in
  // this build we'll be referring to snapshot versions of both scala.meta and
  // macro paradise.
  resolvers += Resolver.url(
    "scalameta",
    url("http://dl.bintray.com/scalameta/maven"))(Resolver.ivyStylePatterns),
  // A dependency on macro paradise 3.x is required to both write and expand
  // new-style macros.  This is similar to how it works for old-style macro
  // annotations and a dependency on macro paradise 2.x.
  addCompilerPlugin(
    "org.scalameta" % "paradise" % "3.0.0.132" cross CrossVersion.full),
  scalacOptions += "-Xplugin-require:macroparadise",
  // temporary workaround for https://github.com/scalameta/paradise/issues/10
  scalacOptions in (Compile, console) := Seq(), // macroparadise plugin doesn't work in repl yet.
  // temporary workaround for https://github.com/scalameta/paradise/issues/55
  sources in (Compile, doc) := Nil // macroparadise doesn't work with scaladoc yet.
)

////////////////////////////////////////////////////////////////////////////////

lazy val `c4proto-macros` = project.settings(publishSettings ++ metaMacroSettings)
lazy val `c4proto-util` = project.settings(publishSettings ++ metaMacroSettings).settings(
  libraryDependencies += "com.squareup.wire" % "wire-runtime" % "2.2.0"
).dependsOn(`c4proto-macros`)
lazy val `c4proto-kafka` = project.settings(publishSettings).settings(
  libraryDependencies += "org.apache.kafka" % "kafka-clients" % "0.10.1.0"
).dependsOn(
  `c4proto-util`
)

lazy val `c4http-proto` = project.settings(publishSettings ++ metaMacroSettings).dependsOn(
  `c4proto-util`, `c4proto-kafka` % "test->compile"
)
lazy val `c4http-server` = project.settings(publishSettings).dependsOn(
  `c4http-proto`, `c4proto-kafka`
)

lazy val root = project.in(file(".")).settings(publishArtifact := false).aggregate(
  `c4proto-macros`, `c4proto-util`, `c4proto-kafka`,
  `c4http-proto`, `c4http-server`
)




