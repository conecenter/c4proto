
export CLASSPATH=$(coursier fetch --classpath org.apache.kafka:kafka-clients:2.8.0)
java --source 15 kafka_test.java $1

