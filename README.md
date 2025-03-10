[![Build Status](https://travis-ci.org/conecenter/c4proto.svg?branch=master)](https://travis-ci.org/conecenter/c4proto)

# c4proto
This is a microservice framework based on eventsourcing ideas.
Kafka is used by microservices to exchange messages and store application state changes.
Protocol buffers are used to serialize messages (`c4proto-*`):
- supports subset of protocol buffers
- supports BigDecimal in messages and is extensible to support custom classes
- messages are described by annotations on case classes
- uses small wire-runtime
- uses scalameta to generate protocol buffer adapters

Application state changes are consumed to in-memory immutable object graph (read model) (`c4actor-*`).
- Changes are propagated through the graph according to dependency rules.
- Propagation forms data structures optimized for different "requests".

To compile/run example: `cd dev_server && sh example.sh`.

To look inside: 
- `docker exec -it devserver_main_1 bash`
- `tail -f /var/log/syslog`
- browse http://127.0.0.10/todo-app.html#todo
