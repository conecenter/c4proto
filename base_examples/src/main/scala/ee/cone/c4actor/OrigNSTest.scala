
package ee.cone.c4actor

import ee.cone.c4di.c4
import ee.cone.c4proto.{Id, protocol}
import ee.cone.c4assemble._
import ee.cone.c4assemble.Types.{Each, Values}
import ee.cone.c4actor.Types.SrcId
import OrigNSTestProtocol.D_OrigNSTestItem

@protocol("OrigNSTestApp") object OrigNSTestProtocol {
  @Id(0x0001) case class D_OrigNSTestItem(
    @Id(0x0002) name: String,
    @Id(0x0003) group: String
  )
}

@c4("OrigNSTestApp") final class OrigNSTestOrigPartitioner extends OrigPartitioner(classOf[D_OrigNSTestItem]) {
  def handle(value: D_OrigNSTestItem): String = value.group
  def partitions: Set[String] = Set("a","b")
}

@c4assemble("OrigNSTestApp") class OrigNSTestAssembleBase {
  type GroupKey = SrcId
  def aMap(
    key: SrcId,
    @by[SrcId@ns("a")] item: Each[D_OrigNSTestItem]
  ): Values[(GroupKey,D_OrigNSTestItem)] =
    List("a" -> item)
  def bMap(
    key: SrcId,
    @by[SrcId@ns("b")] item: Each[D_OrigNSTestItem]
  ): Values[(GroupKey,D_OrigNSTestItem)] =
    List("b" -> item)
  def join(
    key: SrcId,
    @by[GroupKey] items: Values[D_OrigNSTestItem]
  ): Values[(SrcId,OrigNSTestItemGroup)] =
    List(WithPK(OrigNSTestItemGroup(key,items.toList.map(_.name).sorted)))
}

case class OrigNSTestItemGroup(group: SrcId, items: List[SrcId])


@c4("OrigNSTestApp") final class OrigNSTestExecutable(
  execution: Execution,
  contextFactory: ContextFactory, txAdd: LTxAdd,
  getGroup: GetByPK[OrigNSTestItemGroup]
) extends Executable {
  def it: (String, String) => D_OrigNSTestItem = D_OrigNSTestItem.apply _
  def run(): Unit = {
    val voidContext = contextFactory.updated(Nil)
    IgnoreTestContext(Function.chain[Context](Seq(
      txAdd.add(
        LEvent.update(Seq(it("1","a"), it("2","a"), it("3","b"), it("4","b")))
      ),
      local => {
        assert(getGroup.ofA(local)("a") == OrigNSTestItemGroup("a",List("1","2")))
        assert(getGroup.ofA(local)("b") == OrigNSTestItemGroup("b",List("3","4")))
        local
      },
      txAdd.add(
        LEvent.update(Seq(it("2","b"), it("0","a"))) ++
        LEvent.delete(Seq(it("4","b")))
      ),
      local => {
        assert(getGroup.ofA(local)("a") == OrigNSTestItemGroup("a",List("0","1")))
        assert(getGroup.ofA(local)("b") == OrigNSTestItemGroup("b",List("2","3")))
        local
      },
    ))(voidContext))
    execution.complete()
  }
}