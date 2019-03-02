
description := s"C4 framework / http/tcp gate server to kafka"

javaOptions in Universal ++= Seq(
    "-J-XX:+UseG1GC","-J-XX:MaxGCPauseMillis=200","-J-XX:+ExitOnOutOfMemoryError",
    "-J-XX:GCTimeRatio=1","-J-XX:MinHeapFreeRatio=15","-J-XX:MaxHeapFreeRatio=50"
)

enablePlugins(JavaAppPackaging)
/*,AshScriptPlugin*/