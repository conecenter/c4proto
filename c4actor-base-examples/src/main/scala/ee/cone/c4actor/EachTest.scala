package ee.cone.c4actor

import ee.cone.c4actor.EachTestProtocol.Item
import ee.cone.c4assemble.IndexUtil
import ee.cone.c4assemble.Types._
import ee.cone.c4proto.{Id, Protocol, protocol}

class EachTestApp extends RichDataApp
  with SimpleAssembleProfilerApp
  with VMExecutionApp with ToStartApp with ExecutableApp
{
  override def protocols: List[Protocol] = EachTestProtocol :: super.protocols
  override def toStart: List[Executable] = new EachTestExecutable(execution, rawWorldFactory, indexUtil) :: super.toStart
}

@protocol object EachTestProtocol extends Protocol {
  @Id(0x0001) case class Item(@Id(0x0001) srcId: String, @Id(0x0002) value: String)
}

class EachTestExecutable(execution: Execution, rawWorldFactory: RawWorldFactory, indexUtil: IndexUtil) extends Executable {
  def run(): Unit = {
    val rawWorld = rawWorldFactory.create()
    val voidContext = rawWorld match { case w: RichRawWorld ⇒ w.context }

    Function.chain[Context](Seq(
      TxAdd(LEvent.update(Item("1","2"))),
      TxAdd(LEvent.update(Item("1","3"))),
      l ⇒ {
        assert(ByPK.apply(classOf[Item]).of(l)("1").value=="3","last stored item wins")
        l
      }
    ))(voidContext)

    assert(emptyIndex==indexUtil.mergeIndex(Seq(
      indexUtil.result("1",indexUtil.del(Item("1","2"))),
      indexUtil.result("1",Item("1","2"))
    )))

    println(indexUtil.mergeIndex(Seq(
      indexUtil.result("1",indexUtil.del(Item("1","2"))),
      indexUtil.result("1",Item("1","3"))
    )))

    execution.complete()
  }
}

// C4STATE_TOPIC_PREFIX=ee.cone.c4actor.EachTestApp sbt ~'c4actor-base-examples/run-main ee.cone.c4actor.ServerMain'