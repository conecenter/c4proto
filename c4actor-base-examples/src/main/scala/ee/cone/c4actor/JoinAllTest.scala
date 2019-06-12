package ee.cone.c4actor

import ee.cone.c4actor.JoinAllTestProtocol.{D_Item, D_RegistryItem}
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

@protocol(TestCat) object JoinAllTestProtocolBase   {
  @Id(0x0002) case class D_RegistryItem(@Id(0x0001) srcId: String)
  @Id(0x0001) case class D_Item(@Id(0x0001) srcId: String)
}

case class JoinAllTestItem(srcId: String)

@assemble class JoinAllTestAssembleBase   {
  def joinReg(
    key: SrcId,
    regItem: Each[D_RegistryItem]
  ): Values[(All,D_RegistryItem)] = List(All -> regItem)

  def join(
    key: SrcId,
    @by[All] regItem: Each[D_RegistryItem],
    item: Each[D_Item]
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
          LEvent.update(D_RegistryItem("a")) ++
          LEvent.update(D_RegistryItem("b")) ++
          LEvent.update(D_Item("1")) ++
          LEvent.update(D_Item("2"))
        )(l)
      },
      l => {
        println("will be recalc 3-[ab] (2)")
        TxAdd(LEvent.update(D_Item("3")))(l)
      },
      l => {
        println("will be recalc [12 123]-[abc] (15)")
        TxAdd(LEvent.update(D_RegistryItem("c")))(l)
      },
      l => {
        assert(ByPK(classOf[JoinAllTestItem]).of(l).keys.size == 9)
        l
      }
    ))(voidContext)

  }
}

// C4STATE_TOPIC_PREFIX=ee.cone.c4actor.JoinAllTestApp sbt ~'c4actor-base-examples/run-main ee.cone.c4actor.ServerMain'