

scalaVersion := "2.13.0"

libraryDependencies += "org.scalameta" %% "scalameta" % "4.2.3"

enablePlugins(JavaServerAppPackaging)

Global / onChangedBuildSource := ReloadOnSourceChanges

//description := s"C4 framework / scalameta generator for rules and Protobuf adapters"
//organization := "ee.cone"
//version := "0.E.5.1"
//bintrayRepository := "c4proto"
//licenses := ourLicense
//
//lazy val ourLicense = Seq("Apache-2.0" -> url("http://opensource.org/licenses/Apache-2.0"))

javaOptions in Universal ++= Seq("-XX:MaxRAMPercentage=80.0"/*,"-XX:+PrintFlagsFinal"*/)

scalacOptions ++= Seq("-unchecked", "-deprecation")