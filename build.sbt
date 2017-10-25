
import sbt.Keys._
import sbt._

lazy val ourLicense = Seq("Apache-2.0" -> url("http://opensource.org/licenses/Apache-2.0"))

lazy val publishSettings = Seq(
  organization := "ee.cone",
  version := "0.A.7",
  //name := "c4proto",
  //description := "Protobuf scalameta macros",
  publishMavenStyle := false,
  //publishArtifact in Test := false,
  bintrayOrganization := Some("conecenter2b"),  
  //bintrayOrganization in bintray.Keys.bintray := None,
  licenses := ourLicense,
  fork := true, //looks like sbt hangs for a minute on System.exit
  mainClass in Compile := Some("ee.cone.c4actor.ServerMain")
)

scalaVersion in ThisBuild := "2.11.8"

//dockerBaseImage := "openjdk:8"

////////////////////////////////////////////////////////////////////////////////
// from https://github.com/scalameta/sbt-macro-example/blob/master/build.sbt

lazy val metaMacroSettings: Seq[Def.Setting[_]] = Seq(
  ivyConfigurations += config("compileonly").hide,
  libraryDependencies += "org.scalameta" %% "scalameta" % "1.6.0" % "compileonly",
  unmanagedClasspath in Compile ++= update.value.select(configurationFilter("compileonly")),
  // New-style macro annotations are under active development.  As a result, in
  // this build we'll be referring to snapshot versions of both scala.meta and
  // macro paradise.
  resolvers += Resolver.sonatypeRepo("releases"),
  resolvers += Resolver.bintrayIvyRepo("scalameta", "maven"),
  // A dependency on macro paradise 3.x is required to both write and expand
  // new-style macros.  This is similar to how it works for old-style macro
  // annotations and a dependency on macro paradise 2.x.
  addCompilerPlugin("org.scalameta" % "paradise" % "3.0.0-M7" cross CrossVersion.full),
  scalacOptions += "-Xplugin-require:macroparadise",
  // temporary workaround for https://github.com/scalameta/paradise/issues/10
  scalacOptions in (Compile, console) := Seq(), // macroparadise plugin doesn't work in repl yet.
  // temporary workaround for https://github.com/scalameta/paradise/issues/55
  sources in (Compile, doc) := Nil // macroparadise doesn't work with scaladoc yet.
)

////////////////////////////////////////////////////////////////////////////////



lazy val descr = "C4 framework"

lazy val `c4proto-macros` = project.settings(publishSettings ++ metaMacroSettings)
  .settings(description := s"$descr / scalameta macros to generate Protobuf adapters for case classes")
lazy val `c4proto-api` = project.settings(publishSettings)
  .settings(description := s"$descr / runtime dependency for generated Protobuf adapters")
  .settings(libraryDependencies += "com.squareup.wire" % "wire-runtime" % "2.2.0")

lazy val `c4proto-types` = project.settings(publishSettings)
  .settings(description := s"$descr / additional data types to use in messages")
  .settings(metaMacroSettings).dependsOn(`c4proto-macros`,`c4proto-api`)

lazy val `c4assemble-macros` = project.settings(publishSettings ++ metaMacroSettings)
  .settings(description := s"$descr")
lazy val `c4assemble-runtime` = project.settings(publishSettings)
  .settings(description := s"$descr")

lazy val `c4gate-proto` = project.settings(publishSettings)
  .settings(description := s"$descr / http message definitions")
  .settings(metaMacroSettings)
  .dependsOn(`c4proto-macros`,`c4proto-api`)

lazy val `c4actor-base` = project.settings(publishSettings)
  .settings(description := s"$descr")
  .settings(metaMacroSettings)
  .dependsOn(`c4proto-macros`,`c4assemble-macros`,`c4proto-api`,`c4assemble-runtime`)

lazy val `c4actor-base-examples` = project.settings(publishSettings)
  .settings(description := s"$descr")
  .settings(metaMacroSettings)
  .dependsOn(`c4actor-base`,`c4proto-types`)

lazy val `c4actor-kafka` = project.settings(publishSettings)
  .settings(description := s"$descr")
  .settings(libraryDependencies += "org.apache.kafka" % "kafka-clients" % "0.10.2.0")
  .dependsOn(`c4actor-base`)

lazy val `c4gate-server` = project.settings(publishSettings)
  .settings(description := s"$descr / http/tcp gate server to kafka")
  .settings(libraryDependencies += "org.slf4j" % "slf4j-nop" % "1.7.21")
  .settings(metaMacroSettings,javaOptions in Universal ++= Seq("-J-Xmx640m","-J-Xms64m"))
  .dependsOn(`c4assemble-macros`, `c4actor-kafka`, `c4gate-client`)
  .enablePlugins(JavaServerAppPackaging/*,AshScriptPlugin*/)

lazy val `c4gate-consumer-example` = project.settings(publishSettings)
  .settings(description := s"$descr")
  .settings(metaMacroSettings)
  .dependsOn(`c4assemble-macros`, `c4actor-kafka`, `c4gate-client`)
  .enablePlugins(JavaServerAppPackaging)


lazy val `c4actor-branch` = project.settings(publishSettings)
  .settings(description := s"$descr")
  .settings(metaMacroSettings)
  .dependsOn(`c4actor-base`, `c4assemble-macros`)

lazy val `c4actor-rdb` = project.settings(publishSettings)
  .settings(description := s"$descr")
  .settings(metaMacroSettings)
  .dependsOn(`c4actor-base`, `c4assemble-macros`, `c4proto-types`)

lazy val `c4gate-publish` = project.settings(publishSettings)
  .settings(description := s"$descr")
  .dependsOn(`c4actor-kafka`, `c4gate-proto`)
  .enablePlugins(JavaServerAppPackaging)

lazy val `c4gate-sse-example` = project.settings(publishSettings)
  .settings(description := s"$descr")
  .settings(metaMacroSettings)
  .dependsOn(`c4proto-macros`, `c4proto-api`, `c4actor-kafka`, `c4ui-main`, `c4gate-publish`, `c4gate-client`, `c4vdom-canvas`)
  .enablePlugins(JavaServerAppPackaging)


lazy val `c4vdom-base` = project.settings(publishSettings)
  .settings(description := s"$descr")

lazy val `c4vdom-canvas` = project.settings(publishSettings)
  .settings(description := s"$descr")
  .dependsOn(`c4vdom-base`)

//lazy val `c4ui-canvas` = project.settings(publishSettings)
//  .settings(description := s"$descr")

lazy val `c4ui-main` = project.settings(publishSettings)
  .settings(description := s"$descr")
  .settings(metaMacroSettings)
  .dependsOn(`c4actor-branch`, `c4vdom-base`, `c4gate-client`)

lazy val `c4gate-client` = project.settings(publishSettings)
  .settings(description := s"$descr")
  .settings(metaMacroSettings)
  .dependsOn(`c4gate-proto`,`c4actor-base`)

//publishArtifact := false -- bintrayEnsureBintrayPackageExists fails if this
lazy val `c4proto-aggregate` = project.in(file(".")).settings(publishSettings).aggregate(
  `c4actor-base`,
  `c4actor-base-examples`,
  `c4actor-branch`,
  `c4actor-kafka`,
  `c4actor-rdb`,
  `c4assemble-macros`,
  `c4assemble-runtime`,
  `c4gate-consumer-example`,
  `c4gate-client`,
  `c4gate-proto`,
  `c4gate-publish`,
  `c4gate-server`,
  `c4gate-sse-example`,
  `c4proto-api`,
  `c4proto-macros`,
  `c4proto-types`,
  `c4vdom-base`,
  `c4vdom-canvas`,
  `c4ui-main`
)
