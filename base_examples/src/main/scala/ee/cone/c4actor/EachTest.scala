package ee.cone.c4actor

import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor.EachTestProtocol.D_Item
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4assemble.{IndexUtil, assemble, by, c4assemble}
import ee.cone.c4assemble.Types._
import ee.cone.c4di.c4
import ee.cone.c4proto.{Id, protocol}

//  new EachTestNotEffectiveAssemble :: // 25s vs 1s for 3K 1-item-tx-s

@protocol("EachTestApp") object EachTestProtocol   {
  @Id(0x0001) case class D_Item(@Id(0x0001) srcId: String, @Id(0x0002) parent: String)
}

case class EachTestItem(item: D_Item, valueItem: D_Item)

@c4assemble("EachTestApp") class EachTestAssembleBase   {
  type ByParent = SrcId
  def joinByVal(
    key: SrcId,
    item: Each[D_Item]
  ): Values[(ByParent, D_Item)] = List(item.parent -> item)
  def join(
    key: SrcId,
    vItem: Each[D_Item],
    @by[ByParent] item: Each[D_Item]
  ): Values[(SrcId,EachTestItem)] = {
    List(WithPK(EachTestItem(item,vItem)))
  }
}

@c4assemble("EachTestApp") class EachTestNotEffectiveAssembleBase   {
  type ByParent = SrcId
  def joinByVal(
    key: SrcId,
    items: Values[D_Item]
  ): Values[(ByParent, D_Item)] = for {
    item <- items
  } yield item.parent -> item
  def join(
    key: SrcId,
    vItems: Values[D_Item],
    @by[ByParent] items: Values[D_Item]
  ): Values[(SrcId,EachTestItem)] = for {
    vItem <- vItems
    item <- items
  } yield WithPK(EachTestItem(item,vItem))
}

@c4("EachTestApp") final class EachTestExecutable(
  execution: Execution, contextFactory: ContextFactory, indexUtil: IndexUtil,
  items: GetByPK[D_Item], eachTestItems: GetByPK[EachTestItem]
) extends Executable with LazyLogging {
  def run(): Unit = {
    val voidContext = contextFactory.updated(Nil)

    IgnoreTestContext(Function.chain[Context](Seq(
      TxAdd(LEvent.update(D_Item("1","2"))),
      TxAdd(LEvent.update(D_Item("1","3"))),
      l => {
        assert(items.ofA(l)("1").parent=="3","last stored item wins")
        l
      }
    ))(voidContext))

    assert(emptyIndex==indexUtil.mergeIndex(Seq(
      indexUtil.result("1",D_Item("1","2"),-1),
      indexUtil.result("1",D_Item("1","2"),+1)
    )))

    /*println(indexUtil.mergeIndex(Seq(
      indexUtil.result("1",D_Item("1","2"),-1),
      indexUtil.result("1",D_Item("1","3"),+1)
    )))*/

    def measure[R](f: =>R): R = {
      val startTime = System.currentTimeMillis
      val res = f
      logger.info(s"${System.currentTimeMillis - startTime}")
      res
    }

    IgnoreTestContext(Function.chain[Context](Seq(
      TxAdd(LEvent.update(D_Item(s"V",""))),
      l => measure(Function.chain[Context](
        (1 to 3000).map(n=>TxAdd(LEvent.update(D_Item(s"$n","V"))))
      )(l)),
      { (l:Context) =>
        val r = eachTestItems.ofA(l)
        assert(r.keys.size==3000)
        assert(r.values.forall(_.valueItem.parent.isEmpty))
        l
      }
    ))(voidContext))

    execution.complete()
  }
}
