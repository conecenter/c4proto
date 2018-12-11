package ee.cone.c4actor

import ee.cone.c4actor.JoinAllTestProtocol.{Item, RegistryItem}
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4assemble.Types.{Each, Values}
import ee.cone.c4assemble._
import ee.cone.c4proto.{Id, Protocol, protocol}

class JoinAllTestApp extends TestRichDataApp
  with TreeIndexValueMergerFactoryApp
  with SimpleAssembleProfilerApp
  with VMExecutionApp with ToStartApp with ExecutableApp
{
  override def assembles: List[Assemble] = new JoinAllTestAssemble :: super.assembles
  override def protocols: List[Protocol] = JoinAllTestProtocol :: super.protocols
  override def toStart: List[Executable] = new JoinAllTestExecutable(contextFactory) :: super.toStart
}

@protocol(TestCat) object JoinAllTestProtocol extends Protocol {
  @Id(0x0002) case class RegistryItem(@Id(0x0001) srcId: String)
  @Id(0x0001) case class Item(@Id(0x0001) srcId: String)
}

case class JoinAllTestItem(srcId: String)

@assemble class JoinAllTestAssemble extends Assemble {
  def joinReg(
    key: SrcId,
    regItem: Each[RegistryItem]
  ): Values[(All,RegistryItem)] = List(All -> regItem)

  def join(
    key: SrcId,
    @by[All] regItem: Each[RegistryItem],
    item: Each[Item]
  ): Values[(SrcId,JoinAllTestItem)] = {
    println(s"recalc: ${item.srcId}-${regItem.srcId}")
    List(WithPK(JoinAllTestItem(s"${item.srcId}-${regItem.srcId}")))
  }
}

class JoinAllTestExecutable(contextFactory: ContextFactory) extends Executable {
  def run(): Unit = {
    val voidContext = contextFactory.updated(Nil)

    Function.chain[Context](Seq(
      l => {
        println("will be recalc [12]-[ab] (4)")
        TxAdd(
          LEvent.update(RegistryItem("a")) ++
          LEvent.update(RegistryItem("b")) ++
          LEvent.update(Item("1")) ++
          LEvent.update(Item("2"))
        )(l)
      },
      l => {
        println("will be recalc 3-[ab] (2)")
        TxAdd(LEvent.update(Item("3")))(l)
      },
      l => {
        println("will be recalc [12 123]-[abc] (15)")
        TxAdd(LEvent.update(RegistryItem("c")))(l)
      },
      l => {
        assert(ByPK(classOf[JoinAllTestItem]).of(l).keys.size == 9)
        l
      }
    ))(voidContext)

  }
}

// C4STATE_TOPIC_PREFIX=ee.cone.c4actor.JoinAllTestApp sbt ~'c4actor-base-examples/run-main ee.cone.c4actor.ServerMain'