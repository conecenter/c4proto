
lazy val publishSettings = Seq(
  organization := "ee.cone",
  version := "0.1.4",
  fork := true //looks like sbt hangs for a minute on System.exit
)

scalaVersion in ThisBuild := "2.11.8"

// To find the latest version, see MetaVersion in https://github.com/scalameta/paradise/blob/master/build.sbt
lazy val metaVersion = "1.3.0.522"
// To find the latest PR number, see https://github.com/scalameta/paradise/commits/master
lazy val latestPullRequestNumber = 109
lazy val paradiseVersion = s"3.0.0.$latestPullRequestNumber"
lazy val compilerOptions = Seq[String]() // Include your favorite compiler flags here.
lazy val metaMacroSettings: Seq[Def.Setting[_]] = Seq(
  resolvers += Resolver.url(
    "scalameta",
    url("http://dl.bintray.com/scalameta/maven"))(Resolver.ivyStylePatterns),
  libraryDependencies += "org.scalameta" %% "scalameta" % metaVersion,
  sources in (Compile, doc) := Nil,
  addCompilerPlugin(
    "org.scalameta" % "paradise" % paradiseVersion cross CrossVersion.full),
  scalacOptions ++= compilerOptions,
  scalacOptions in (Compile, console) := compilerOptions :+ "-Yrepl-class-based", // necessary to use console
  scalacOptions += "-Xplugin-require:macroparadise"
)

lazy val `c4proto-macros` = project.settings(publishSettings ++ metaMacroSettings)
lazy val `c4proto-util` = project.settings(publishSettings ++ metaMacroSettings).settings(
  libraryDependencies += "com.squareup.wire" % "wire-runtime" % "2.2.0",
  libraryDependencies += "org.apache.kafka" % "kafka-clients" % "0.10.1.0"
).dependsOn(`c4proto-macros`)


lazy val `c4http-proto` = project.settings(publishSettings ++ metaMacroSettings).dependsOn(`c4proto-util`)
lazy val `c4http-server` = project.settings(publishSettings).dependsOn(`c4http-proto`)

lazy val root = project.in(file(".")).settings(publishArtifact := false).aggregate(
  `c4proto-macros`, `c4proto-util`,
  `c4http-proto`, `c4http-server`
)




