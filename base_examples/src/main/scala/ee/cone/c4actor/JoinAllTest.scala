package ee.cone.c4actor

import ee.cone.c4actor.JoinAllTestProtocol.{D_AItem, D_RegistryItem}
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4assemble.Types.{Each, Values}
import ee.cone.c4assemble._
import ee.cone.c4di.c4
import ee.cone.c4proto.{Id, protocol}

@protocol("JoinAllTestApp") object JoinAllTestProtocol   {
  @Id(0x0002) case class D_RegistryItem(@Id(0x0001) srcId: String)
  @Id(0x0001) case class D_AItem(@Id(0x0001) srcId: String)
}

case class JoinAllTestItem(srcId: String)

@c4assemble("JoinAllTestApp") class JoinAllTestAssembleBase   {
  def joinReg(
    key: SrcId,
    regItem: Each[D_RegistryItem]
  ): Values[(AbstractAll,D_RegistryItem)] = List(All -> regItem)

  def join(
    key: SrcId,
    @byEq[AbstractAll](All) regItem: Each[D_RegistryItem],
    item: Each[D_AItem]
  ): Values[(SrcId,JoinAllTestItem)] = {
    println(s"recalc: ${item.srcId}-${regItem.srcId}")
    List(WithPK(JoinAllTestItem(s"${item.srcId}-${regItem.srcId}")))
  }
}

@c4("JoinAllTestApp") final class JoinAllTestExecutable(
  contextFactory: ContextFactory,
  execution: Execution,
  getJoinAllTestItem: GetByPK[JoinAllTestItem],
  txAdd: LTxAdd,
) extends Executable {
  def run(): Unit = {
    val voidContext = contextFactory.updated(Nil)

    IgnoreTestContext(Function.chain[Context](Seq(
      l => {
        println("will be recalc [12]-[ab] (4)")
        txAdd.add(
          LEvent.update(D_RegistryItem("a")) ++
          LEvent.update(D_RegistryItem("b")) ++
          LEvent.update(D_AItem("1")) ++
          LEvent.update(D_AItem("2"))
        )(l)
      },
      l => {
        println("will be recalc 3-[ab] (2)")
        txAdd.add(LEvent.update(D_AItem("3")))(l)
      },
      l => {
        println("will be recalc [12 123]-[abc] (15)")
        txAdd.add(LEvent.update(D_RegistryItem("c")))(l)
      },
      l => {
        assert(getJoinAllTestItem.ofA(l).keys.size == 9)
        l
      }
    ))(voidContext))
    execution.complete()
  }
}
