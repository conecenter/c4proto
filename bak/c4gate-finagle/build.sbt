
libraryDependencies += "com.twitter" %% "finagle-http" % "19.8.0"

description := s"C4 framework / http gate server to kafka"

javaOptions in Universal ++= Seq(
    "-J-XX:+UseG1GC","-J-XX:MaxGCPauseMillis=200","-J-XX:+ExitOnOutOfMemoryError",
    "-J-XX:GCTimeRatio=1","-J-XX:MinHeapFreeRatio=15","-J-XX:MaxHeapFreeRatio=50"
)

// enablePlugins(JavaAppPackaging)