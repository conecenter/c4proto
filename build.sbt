
import sbt.Keys._
import sbt._

lazy val ourLicense = Seq("Apache-2.0" -> url("http://opensource.org/licenses/Apache-2.0"))
lazy val descr = "C4 framework"
     
lazy val publishSettings = Seq(
  organization := "ee.cone",
  version := "0.E.F",
  bintrayRepository := "c4proto",
  description := descr,
  licenses := ourLicense,
  fork := true, //looks like sbt hangs for a minute on System.exit
  mainClass in Compile := Some("ee.cone.c4actor.ServerMain"),
  //scalacOptions += "-feature"//"-deprecation",
  bintrayVcsUrl := Option("git@github.com:conecenter/c4proto.git")
)

scalaVersion in ThisBuild := "2.12.8"

//dockerBaseImage := "openjdk:8"

lazy val `c4proto-api` = project.settings(publishSettings)
lazy val `c4proto-types` = project.settings(publishSettings).dependsOn(`c4proto-api`)
lazy val `c4assemble-runtime` = project.settings(publishSettings)
lazy val `c4gate-proto` = project.settings(publishSettings).dependsOn(`c4proto-api`)
lazy val `c4actor-base` = project.settings(publishSettings).dependsOn(`c4proto-api`,`c4assemble-runtime`)
lazy val `c4actor-base-examples` = project.settings(publishSettings).dependsOn(`c4actor-base`,`c4proto-types`, `c4gate-logback`)
lazy val `c4actor-extra` = project.settings(publishSettings).dependsOn(`c4actor-base`,`c4proto-types`)
lazy val `c4gate-extra` = project.settings(publishSettings).dependsOn(`c4actor-extra`, `c4gate-client`, `c4actor-base`, `c4proto-types`)
lazy val `c4actor-extra-examples` = project.settings(publishSettings).dependsOn(`c4actor-base`,`c4proto-types`, `c4gate-logback`, `c4actor-extra`, `c4external-base`, `c4actor-kafka`)
lazy val `c4actor-kafka` = project.settings(publishSettings).dependsOn(`c4actor-base`)
lazy val `c4gate-server` = project.settings(publishSettings).dependsOn(`c4actor-kafka`, `c4gate-client`, `c4gate-logback`, `c4gate-publish`)
lazy val `c4gate-consumer-example` = project.settings(publishSettings).dependsOn(`c4actor-kafka`, `c4gate-client`, `c4gate-logback`)
lazy val `c4gate-server-example` = project.settings(publishSettings).dependsOn(`c4gate-server`)
lazy val `c4actor-branch` = project.settings(publishSettings).dependsOn(`c4actor-base`)
lazy val `c4actor-rdb` = project.settings(publishSettings).dependsOn(`c4actor-base`, `c4proto-types`)
lazy val `c4gate-publish` = project.settings(publishSettings).dependsOn(`c4actor-base`, `c4gate-proto`)
lazy val `c4gate-sse-example` = project.settings(publishSettings).dependsOn(`c4proto-api`, `c4actor-kafka`, `c4ui-main`, `c4gate-publish`, `c4gate-client`, `c4vdom-canvas`, `c4gate-logback`, `c4gate-repl`)
lazy val `c4vdom-base` = project.settings(publishSettings)
lazy val `c4vdom-canvas` = project.settings(publishSettings).dependsOn(`c4vdom-base`)
lazy val `c4ui-main` = project.settings(publishSettings).dependsOn(`c4actor-branch`, `c4vdom-base`, `c4gate-client`)
lazy val `c4ui-extra` = project.settings(publishSettings).dependsOn(`c4ui-main`, `c4actor-extra`, `c4gate-extra`)
lazy val `c4gate-client` = project.settings(publishSettings).dependsOn(`c4gate-proto`,`c4actor-base`)
lazy val `c4gate-logback` = project.settings(publishSettings)
lazy val `c4gate-repl` = project.settings(publishSettings).dependsOn(`c4actor-base`)
lazy val `c4external-base` = project.settings(publishSettings).dependsOn(`c4actor-base`, `c4proto-types`, `c4actor-extra`)

lazy val `c4proto-aggregate` = project.in(file(".")).settings(publishSettings).aggregate(
  `c4actor-base`,
  `c4actor-base-examples`,
  `c4actor-branch`,
  `c4actor-kafka`,
  `c4actor-rdb`,
  `c4assemble-runtime`,
  `c4gate-consumer-example`,
  `c4gate-server-example`,
  `c4gate-client`,
  `c4gate-logback`,
  `c4gate-proto`,
  `c4gate-publish`,
  `c4gate-server`,
  `c4gate-sse-example`,
  `c4gate-repl`,
  `c4proto-api`,
  `c4proto-types`,
  `c4vdom-base`,
  `c4vdom-canvas`,
  `c4actor-extra`,
  `c4gate-extra`,
  `c4actor-extra-examples`,
  `c4ui-main`,
  `c4ui-extra`,
  `c4external-base`
)
