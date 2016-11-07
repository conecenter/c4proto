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

lazy val macros = project.settings(metaMacroSettings)

lazy val app = project.settings(metaMacroSettings).dependsOn(macros)
