#!/bin/sh

cd tmp/kafka_2.11-0.10.1.0
bin/zookeeper-server-start.sh config/zookeeper.properties 1> zookeeper.log 2> zookeeper.error.log &
bin/kafka-server-start.sh config/server.properties 1> kafka.log 2> kafka.error.log &
