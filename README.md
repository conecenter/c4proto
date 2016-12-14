[![Build Status](https://travis-ci.org/conecenter/c4proto.svg?branch=master)](https://travis-ci.org/conecenter/c4proto)

# c4proto
This is a microservice framework based on eventsourcing ideas.
Kafka is used by microservices to exchange messages and store application state changes.
Protocol buffers are used to serialize messages (`c4proto-*`):
- supports subset of protocol buffers
- supports BigDecimal in messages and is extensible to support custom classes
- messages are described by annotations on case classes
- uses small wire-runtime
- uses scalameta / macro paradise to generate protocol buffer adapters

Includes gate-microservice (`c4gate-*`) that:
- serves all external requests, so other microservices can be freely moved
- forward POST-s to Kafka topic
- serves GET requests and raw TCP connections by content published to Kafka topic

Application state changes are consumed to in-memory immutable object graph (read model) (`c4actor-*`).
- Changes are propogated through the graph according to dependency rules.
- Propogation forms data structures optimized for different "requests".

[usage sbt config example](https://github.com/conecenter/c4proto-example)

`./do.pl` can help to run examples
