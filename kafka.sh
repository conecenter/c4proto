#!/bin/sh

cd tmp/kafka_2.11-0.10.1.0
bin/zookeeper-server-start.sh config/zookeeper.properties 1> zookeeper.log 2> zookeeper.error.log &
bin/kafka-server-start.sh config/server.properties 1> kafka.log 2> kafka.error.log &

#export PATH=$PATH:$HOME/tools/sbt/bin

#./c4http-server.sh
#sbt c4http-proto/test:run

#curl http://127.0.0.1:8067/abc -X POST -d 6
#curl http://127.0.0.1:8067/abc