#!/usr/bin/env bash

C4BOOTSTRAP_SERVERS=localhost:9092 C4HTTP_PORT=8067 C4SSE_PORT=8068 sbt c4http-server/run

#export PATH=$HOME/tools/jdk/bin:$HOME/tools/sbt/bin:$PATH

#./c4http-server.sh
#sbt c4http-proto/test:run

#curl http://127.0.0.1:8067/abc -X POST -d 6
#curl http://127.0.0.1:8067/abc

#nc 127.0.0.1 8068

#sbt show compile:dependencyClasspath
#... ScalaCheck, Specs2, and ScalaTest

#sbt clean c4http-server/stage
#C4BOOTSTRAP_SERVERS=localhost:9092 C4HTTP_PORT=8067 C4SSE_PORT=8068 c4http-server/target/universal/stage/bin/c4http-server

#sbt clean c4http-consumer-example/run