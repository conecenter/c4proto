

import sbt.Keys._
import sbt._

Global / onChangedBuildSource := ReloadOnSourceChanges

lazy val ourLicense = Seq("Apache-2.0" -> url("http://opensource.org/licenses/Apache-2.0"))
lazy val descr = "C4 framework"
     
lazy val publishSettings = Seq(
  organization := "ee.cone",
  version := "0.F.3.1",
  bintrayRepository := "c4proto",
  description := descr,
  licenses := ourLicense,
  fork := true, //looks like sbt hangs for a minute on System.exit
  mainClass in Compile := Some("ee.cone.c4actor.ServerMain"),
  //scalacOptions += "-feature"//"-deprecation",
  bintrayVcsUrl := Option("git@github.com:conecenter/c4proto.git")
)

scalacOptions in ThisBuild ++= Seq("-unchecked", "-deprecation")

scalaVersion in ThisBuild := "2.13.0"

//dockerBaseImage := "openjdk:8"

lazy val `c4proto-di` = project.settings(publishSettings)
lazy val `c4proto-api` = project.settings(publishSettings).dependsOn(`c4proto-di`)
lazy val `c4assemble-runtime` = project.settings(publishSettings).dependsOn(`c4proto-di`)
lazy val `c4actor-base` = project.settings(publishSettings).dependsOn(`c4proto-api`,`c4assemble-runtime`)
lazy val `c4actor-base-examples` = project.settings(publishSettings).dependsOn(`c4actor-base`,`c4gate-logback`)
lazy val `c4actor-extra` = project.settings(publishSettings).dependsOn(`c4actor-base`)
lazy val `c4gate-extra` = project.settings(publishSettings).dependsOn(`c4actor-extra`, `c4gate-client`, `c4actor-base`)
lazy val `c4actor-extra-examples` = project.settings(publishSettings).dependsOn(`c4gate-logback`, `c4actor-extra`, `c4actor-kafka`)
lazy val `c4gate-extra-examples` = project.settings(publishSettings).dependsOn(`c4gate-extra`) // `c4gate-logback`, `c4actor-kafka`,
lazy val `c4actor-kafka` = project.settings(publishSettings).dependsOn(`c4actor-base`)
lazy val `c4gate-server` = project.settings(publishSettings).dependsOn(`c4actor-kafka`, `c4gate-client`, `c4gate-logback`)
lazy val `c4gate-consumer-example` = project.settings(publishSettings).dependsOn(`c4actor-kafka`, `c4gate-client`, `c4gate-logback`)
lazy val `c4gate-server-example` = project.settings(publishSettings).dependsOn(`c4gate-server`)
lazy val `c4actor-branch` = project.settings(publishSettings).dependsOn(`c4actor-base`)
lazy val `c4actor-rdb` = project.settings(publishSettings).dependsOn(`c4actor-base`)
lazy val `c4actor-extra-rdb` = project.settings(publishSettings).dependsOn(`c4actor-rdb`,`c4actor-extra`)
lazy val `c4gate-sse-example` = project.settings(publishSettings).dependsOn(`c4proto-api`, `c4actor-kafka`, `c4ui-main`, `c4gate-client`, `c4vdom-canvas`, `c4gate-logback`, `c4gate-repl`)
lazy val `c4vdom-base` = project.settings(publishSettings)
lazy val `c4vdom-canvas` = project.settings(publishSettings).dependsOn(`c4vdom-base`) //seems examples only
lazy val `c4ui-main` = project.settings(publishSettings).dependsOn(`c4actor-branch`, `c4vdom-base`, `c4gate-client`)
lazy val `c4ui-extra` = project.settings(publishSettings).dependsOn(`c4ui-main`, `c4actor-extra`, `c4gate-extra`)
lazy val `c4gate-client` = project.settings(publishSettings).dependsOn(`c4actor-base`)
lazy val `c4gate-logback-static` = project.settings(publishSettings)
lazy val `c4gate-logback` = project.settings(publishSettings).dependsOn(`c4actor-base`)
lazy val `c4gate-repl` = project.settings(publishSettings).dependsOn(`c4actor-base`)

//lazy val `c4gate-sun` = project.settings(publishSettings).dependsOn(`c4gate-server`)
//lazy val `c4gate-finagle` = project.settings(publishSettings).dependsOn(`c4gate-server`, `c4gate-sun`)
lazy val `c4gate-akka` = project.settings(publishSettings).dependsOn(`c4gate-server`, `c4gate-repl`)


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
  `c4gate-client`,`c4gate-logback-static`,`c4gate-logback`,`c4gate-repl`,
  `c4vdom-base`,`c4ui-main`,
  `c4actor-extra`,`c4gate-extra`,`c4actor-extra-rdb`,`c4ui-extra`,
  // examples
  `c4all-examples`
)
/*

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

lazy val `generator` = project.in(file("generator"))
