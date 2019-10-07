resolvers += "Central Maven" at "https://search.maven.org"

libraryDependencies += "com.typesafe.akka" %% "akka-stream" % "2.5.25"

libraryDependencies += "com.typesafe.akka" % "akka-http-core_2.12" % "10.0.13"

description := s"C4 framework / http gate server to kafka"

javaOptions in Universal ++= Seq(
    "-J-XX:+UseG1GC","-J-XX:MaxGCPauseMillis=200","-J-XX:+ExitOnOutOfMemoryError",
    "-J-XX:GCTimeRatio=1","-J-XX:MinHeapFreeRatio=15","-J-XX:MaxHeapFreeRatio=50"
)

enablePlugins(JavaAppPackaging)
/*,AshScriptPlugin*/