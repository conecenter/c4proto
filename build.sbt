
import java.nio.file.{Files, Path, Paths}

import sbt.Keys._

lazy val ourLicense = Seq("Apache-2.0" -> url("http://opensource.org/licenses/Apache-2.0"))

lazy val publishSettings = Seq(
  organization := "ee.cone",
  version := "0.B.2",
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

lazy val descr = "C4 framework"

lazy val `c4proto-api` = project.settings(publishSettings)
  .settings(description := s"$descr / runtime dependency for generated Protobuf adapters")
  .settings(libraryDependencies += "com.squareup.wire" % "wire-runtime" % "2.2.0")

lazy val `c4proto-types` = project.settings(publishSettings)
  .settings(description := s"$descr / additional data types to use in messages")
  .dependsOn(`c4proto-api`)

lazy val `c4assemble-runtime` = project.settings(publishSettings)
  .settings(description := s"$descr")

lazy val `c4gate-proto` = project.settings(publishSettings)
  .settings(description := s"$descr / http message definitions")
  .dependsOn(`c4proto-api`)

lazy val `c4actor-base` = project.settings(publishSettings)
  .settings(description := s"$descr")
  .settings(libraryDependencies += "com.typesafe.scala-logging" %% "scala-logging" % "3.7.2")
  .dependsOn(`c4proto-api`,`c4assemble-runtime`)

lazy val `c4actor-base-examples` = project.settings(publishSettings)
  .settings(description := s"$descr")
  .dependsOn(`c4actor-base`,`c4proto-types`, `c4gate-logback`)

lazy val `c4actor-kafka` = project.settings(publishSettings)
  .settings(description := s"$descr")
  .settings(libraryDependencies += "org.apache.kafka" % "kafka-clients" % "0.10.2.1")
  .dependsOn(`c4actor-base`)

lazy val `c4gate-server` = project.settings(publishSettings)
  .settings(description := s"$descr / http/tcp gate server to kafka")
  //.settings(libraryDependencies += "org.slf4j" % "slf4j-nop" % "1.7.21")
  .settings(javaOptions in Universal ++= Seq("-J-Xmx6400m","-J-Xms64m"))
  .dependsOn(`c4actor-kafka`, `c4gate-client`, `c4gate-logback`)
  .enablePlugins(JavaServerAppPackaging/*,AshScriptPlugin*/)

lazy val `c4gate-consumer-example` = project.settings(publishSettings)
  .settings(description := s"$descr")
  .dependsOn(`c4actor-kafka`, `c4gate-client`, `c4gate-logback`)
  .enablePlugins(JavaServerAppPackaging)


lazy val `c4actor-branch` = project.settings(publishSettings)
  .settings(description := s"$descr")
  .dependsOn(`c4actor-base`)

lazy val `c4actor-rdb` = project.settings(publishSettings)
  .settings(description := s"$descr")
  .dependsOn(`c4actor-base`, `c4proto-types`)

lazy val `c4gate-publish` = project.settings(publishSettings)
  .settings(description := s"$descr")
  .dependsOn(`c4actor-kafka`, `c4gate-proto`)
  .enablePlugins(JavaServerAppPackaging)

lazy val `c4gate-sse-example` = project.settings(publishSettings)
  .settings(description := s"$descr")
  .dependsOn(`c4proto-api`, `c4actor-kafka`, `c4ui-main`, `c4gate-publish`, `c4gate-client`, `c4gate-logback`)
  .enablePlugins(JavaServerAppPackaging)


lazy val `c4vdom-base` = project.settings(publishSettings)
  .settings(description := s"$descr")

//lazy val `c4ui-canvas` = project.settings(publishSettings)
//  .settings(description := s"$descr")

lazy val `c4ui-main` = project.settings(publishSettings)
  .settings(description := s"$descr")
  .dependsOn(`c4actor-branch`, `c4vdom-base`, `c4gate-client`)

lazy val `c4gate-client` = project.settings(publishSettings)
  .settings(description := s"$descr")
  .dependsOn(`c4gate-proto`,`c4actor-base`)

lazy val `c4gate-logback` = project.settings(publishSettings)
  .settings(description := s"$descr logback with config")
  .settings(libraryDependencies += "ch.qos.logback" % "logback-classic" % "1.2.3")
  //.settings(libraryDependencies += "org.codehaus.groovy" % "groovy-all" % "2.4.12")

//publishArtifact := false -- bintrayEnsureBintrayPackageExists fails if this
lazy val `c4proto-aggregate` = project.in(file(".")).settings(publishSettings).aggregate(
  `c4actor-base`,
  `c4actor-base-examples`,
  `c4actor-branch`,
  `c4actor-kafka`,
  `c4actor-rdb`,
  `c4assemble-runtime`,
  `c4gate-consumer-example`,
  `c4gate-client`,
  `c4gate-logback`,
  `c4gate-proto`,
  `c4gate-publish`,
  `c4gate-server`,
  `c4gate-sse-example`,
  `c4proto-api`,
  `c4proto-types`,
  `c4vdom-base`,
  //`c4ui-canvas`,
  `c4ui-main`
)
