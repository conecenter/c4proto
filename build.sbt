

import sbt.Keys._
import sbt._

Global / onChangedBuildSource := ReloadOnSourceChanges

licenses in ThisBuild := Seq("Apache-2.0" -> url("http://opensource.org/licenses/Apache-2.0"))

scalacOptions in ThisBuild ++= Seq("-unchecked", "-deprecation")

scalaVersion in ThisBuild := "2.13.0"

/*
lazy val base_lib = project
lazy val base_server = project.dependsOn(base_lib)
lazy val base_examples = project.dependsOn(base_server)
lazy val extra_lib = project.dependsOn(base_lib)
lazy val extra_examples = project.dependsOn(extra_lib)
lazy val `c4proto-aggregate` = project.in(file("."))
  .aggregate(base_lib,base_server,base_examples,extra_lib,extra_examples)
lazy val generator = project //.in(file("generator"))
*/

unmanagedSourceDirectories in Compile ++= Seq(
  "base_lib",
  "base_server",
  "base_examples",
  "extra_lib",
  "extra_examples"
)   .map(d=>baseDirectory.value / s"$d/src")

enablePlugins(JavaServerAppPackaging)

libraryDependencies += "com.typesafe.scala-logging" %% "scala-logging" % "3.9.2"
libraryDependencies += "org.apache.kafka" % "kafka-clients" % "2.3.0"
libraryDependencies += "ch.qos.logback" % "logback-classic" % "1.2.3"
libraryDependencies += "com.lihaoyi" % "ammonite-sshd" % "1.6.9" cross CrossVersion.full
libraryDependencies += "com.squareup.wire" % "wire-runtime" % "2.2.0"

libraryDependencies += "com.typesafe.akka" %% "akka-stream" % "2.5.25"
libraryDependencies += "com.typesafe.akka" %% "akka-http-core" % "10.1.10"

libraryDependencies += "org.scalameta" %% "scalameta" % "4.2.3"








//lazy val publishSettings = Seq(
//  organization := "ee.cone",
//  version := "0.F.3.1",
//  bintrayRepository := "c4proto",
//  description := descr,
//  licenses := ourLicense,
//  fork := true, //looks like sbt hangs for a minute on System.exit
//  mainClass in Compile := Some("ee.cone.c4actor.ServerMain"),
//scalacOptions += "-feature"//"-deprecation",
//  bintrayVcsUrl := Option("git@github.com:conecenter/c4proto.git")
//)
/*
lazy val `c4proto-di` = project.settings(publishSettings)
lazy val `c4proto-api` = project.settings(publishSettings).dependsOn(`c4proto-di`)
lazy val `c4assemble-runtime` = project.settings(publishSettings).dependsOn(`c4proto-di`)
lazy val `c4actor-base` = project.settings(publishSettings).dependsOn(`c4proto-api`,`c4assemble-runtime`)
lazy val `c4actor-kafka` = project.settings(publishSettings).dependsOn(`c4actor-base`)
lazy val `c4actor-branch` = project.settings(publishSettings).dependsOn(`c4actor-base`)
lazy val `c4vdom-base` = project.settings(publishSettings)
lazy val `c4ui-main` = project.settings(publishSettings).dependsOn(`c4actor-branch`, `c4vdom-base`, `c4gate-client`)
lazy val `c4gate-client` = project.settings(publishSettings).dependsOn(`c4actor-base`)
lazy val `c4gate-logback` = project.settings(publishSettings).dependsOn(`c4actor-base`)
lazy val `c4gate-repl` = project.settings(publishSettings).dependsOn(`c4actor-base`)

lazy val `c4gate-server` = project.settings(publishSettings).dependsOn(`c4actor-kafka`, `c4gate-client`, `c4gate-logback`)
lazy val `c4gate-akka` = project.settings(publishSettings).dependsOn(`c4gate-server`, `c4gate-repl`)

lazy val `c4actor-base-examples` = project.settings(publishSettings).dependsOn(`c4actor-base`,`c4gate-logback`)
lazy val `c4gate-consumer-example` = project.settings(publishSettings).dependsOn(`c4actor-kafka`, `c4gate-client`, `c4gate-logback`)
lazy val `c4gate-server-example` = project.settings(publishSettings).dependsOn(`c4gate-server`)
lazy val `c4gate-sse-example` = project.settings(publishSettings).dependsOn(`c4proto-api`, `c4actor-kafka`,
  `c4ui-main`, `c4gate-client`, `c4vdom-canvas`, `c4gate-logback`, `c4gate-repl`)
lazy val `c4vdom-canvas` = project.settings(publishSettings).dependsOn(`c4vdom-base`) //seems examples only

lazy val `c4actor-extra` = project.settings(publishSettings).dependsOn(`c4actor-base`)
lazy val `c4gate-extra` = project.settings(publishSettings).dependsOn(`c4actor-extra`, `c4gate-client`, `c4actor-base`)
lazy val `c4actor-rdb` = project.settings(publishSettings).dependsOn(`c4actor-base`)
lazy val `c4actor-extra-rdb` = project.settings(publishSettings).dependsOn(`c4actor-rdb`,`c4actor-extra`)
lazy val `c4ui-extra` = project.settings(publishSettings).dependsOn(`c4ui-main`, `c4actor-extra`, `c4gate-extra`)

lazy val `c4actor-extra-examples` = project.settings(publishSettings).dependsOn(`c4gate-logback`, `c4actor-extra`, `c4actor-kafka`)
lazy val `c4gate-extra-examples` = project.settings(publishSettings).dependsOn(`c4gate-extra`) // `c4gate-logback`, `c4actor-kafka`,

//

lazy val `c4all-examples` = project.settings(publishSettings).dependsOn(
  `c4actor-base-examples`, `c4gate-consumer-example`, `c4gate-server-example`, `c4gate-sse-example`,
  `c4vdom-canvas`,
  `c4actor-extra-examples`, `c4gate-extra-examples`
)
//lazy val `c4all-server` = project.settings(publishSettings).dependsOn(`c4gate-akka`)

lazy val `c4proto-aggregate` = project.in(file(".")).settings(publishSettings).aggregate(
  // gate
  `c4gate-akka`,
  // lib
  `c4proto-di`,`c4proto-api`,`c4assemble-runtime`,
  `c4actor-base`,`c4actor-kafka`,`c4actor-branch`,`c4actor-rdb`,
  `c4gate-client`,`c4gate-logback`,`c4gate-repl`,
  `c4vdom-base`,`c4ui-main`,
  `c4actor-extra`,`c4gate-extra`,`c4actor-extra-rdb`,`c4ui-extra`,
  // examples
  `c4all-examples`
)


lazy val `c4proto-aggregate` = project.in(file(".")).settings(publishSettings).aggregate(
  `c4gate-akka`,
  // opt lib:
  `c4actor-extra-rdb`,
  `c4gate-logback-static`,
  `c4gate-repl`,
  `c4ui-main`,
  `c4ui-extra`,
  //
  `c4actor-extra-examples`,
  `c4gate-extra-examples`,
  `c4all-examples`
)*/


