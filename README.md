[![Build Status](https://travis-ci.org/conecenter/c4proto.svg?branch=master)](https://travis-ci.org/conecenter/c4proto)

# c4proto
Protobuf scalameta macros

- supports subset of protocol buffers
- supports BigDecimal in messages and is extensible to support custom classes
- messages are described by annotations on case classes
- uses small wire-runtime
- uses scalameta / paradise

```
$> sbt c4proto-util/test:run
$> sbt publish-local
```

```scala
object MyApp extends App {
  import MyProtocol._
  val leader0 = Person("leader0", Some(40))
  val worker0 = Person("worker0", Some(30))
  val worker1 = Person("worker1", Some(20))
  val group0 = Group(Some(leader0), List(worker0,worker1))
  val findAdapter = new FindAdapter(Seq(MyProtocol))()
  val adapter = findAdapter(group0)
  val bytes = adapter.encode(group0)
  println(bytes.toList)
  val group1 = adapter.decode(bytes)
  println(group0,group1,group0==group1)
}

@protocol object MyProtocol extends Protocol {
  import BigDecimalProtocol._
  @Id(0x0003) case class Person(@Id(0x0007) name: String, @Id(0x0004) age: Option[BigDecimal] @scale(0))
  @Id(0x0001) case class Group(@Id(0x0005) leader: Option[Person], @Id(0x0006) worker: List[Person])
}
```
