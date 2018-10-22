package ee.cone.c4gate

import com.squareup.wire.ProtoAdapter
import ee.cone.c4actor.LifeTypes.Alive
import ee.cone.c4actor.QProtocol.TxRef
import ee.cone.c4actor._
import ee.cone.c4actor.SimpleAssembleProfilerProtocol.TxAddMeta
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4assemble.Types.{Each, Values}
import ee.cone.c4assemble.{Assemble, assemble, by}
import ee.cone.c4gate.AlienProtocol.ToAlienWrite
import ee.cone.c4gate.HttpProtocol.HttpPublication
import ee.cone.c4proto.HasId
import ee.cone.c4ui.{ByLocationHashView, ByLocationHashViewsApp, UntilPolicy}
import ee.cone.c4vdom.Tags
import ee.cone.c4vdom.Types.ViewRes

import scala.annotation.tailrec

trait TestTxLogApp extends AssemblesApp with ByLocationHashViewsApp with MortalFactoryApp {
  def tags: Tags
  def untilPolicy: UntilPolicy
  def qAdapterRegistry: QAdapterRegistry

  private lazy val testTxLogView = TestTxLogView()(actorName, untilPolicy, tags)
  private lazy val actorName = getClass.getName

  override def assembles: List[Assemble] =
    mortal(classOf[TxRef]) :: mortal(classOf[TxAddMeta]) ::
    new TestTxLogAssemble(actorName)(qAdapterRegistry)() ::
    super.assembles
  override def byLocationHashViews: List[ByLocationHashView] =
    testTxLogView :: super.byLocationHashViews
}

case class TestTxLogView(locationHash: String = "txlog")(
  actorName: String,
  untilPolicy: UntilPolicy,
  mTags: Tags
) extends ByLocationHashView {
  def view: Context ⇒ ViewRes = untilPolicy.wrap { local ⇒
    import mTags._
    for{
      updatesListSummary ← ByPK(classOf[UpdatesListSummary]).of(local).get(actorName).toList
      updatesSummary ← updatesListSummary.items
      add = updatesSummary.add
    } yield div(s"tx${add.srcId}",List())(
      text("text",
        s"tx: ${add.srcId}," +
          s" objects: ${add.updObjCount}," +
          s" bytes: ${add.updByteCount}," +
          s" types: ${add.updValueTypeIds.map(java.lang.Long.toHexString).mkString(", ")}" +
          s" period: ${add.finishedAt-add.startedAt}"
      ) ::
      (for {
        (logEntry,idx) ← add.log.zipWithIndex
      } yield div(s"$idx",Nil)(
        text("text",
          s" ** ${logEntry.value} ${logEntry.name}"
        ) :: Nil
      ))
    )
  }
}

case class UpdatesSummary(add: TxAddMeta, ref: TxRef)
case class UpdatesListSummary(srcId: SrcId, items: List[UpdatesSummary], txCount: Long, objCount: Long, byteCount: Long)

@assemble class TestTxLogAssemble(actorName: String)(
  qAdapterRegistry: QAdapterRegistry
)(
  metaAdapter: ProtoAdapter[TxAddMeta] with HasId =
    qAdapterRegistry.byName(classOf[TxAddMeta].getName)
      .asInstanceOf[ProtoAdapter[TxAddMeta] with HasId]
) extends Assemble {
  type SummaryId = SrcId

  def mapMeta(
    key: SrcId,
    txRef: Each[TxRef],
    txAdd: Each[TxAddMeta]
  ): Values[(SummaryId,UpdatesSummary)] =
    List(actorName → UpdatesSummary(txAdd, txRef))

  def sumMeta(
    key: SrcId,
    @by[SummaryId] updatesSummary: Values[UpdatesSummary]
  ): Values[(SrcId,UpdatesListSummary)] = {
    @tailrec def headToKeep(res: UpdatesListSummary, in: List[UpdatesSummary]): UpdatesListSummary =
      if(in.isEmpty) res else {
        val will = UpdatesListSummary(
          res.srcId,
          in.head :: res.items,
          res.txCount + 1,
          res.objCount + in.head.add.updObjCount,
          res.byteCount + in.head.add.updByteCount
        )
        if(will.txCount > 20 ||
          will.objCount > 10000 ||
          will.byteCount > 100000000
        ) res
        else headToKeep(will, in.tail)
      }

    val skipIds = Seq(classOf[ToAlienWrite],classOf[HttpPublication],classOf[TxAddMeta],classOf[TxRef])
      .map(cl⇒qAdapterRegistry.byName(cl.getName).id).toSet

    List(WithPK(headToKeep(
      UpdatesListSummary(key,Nil,0L,0L,0L),
      updatesSummary.filterNot(_.add.updValueTypeIds.forall(skipIds))
        .sortBy(_.ref.txId).toList.reverse
    )))
  }

  def keepAdds(
    key: SrcId,
    updatesListSummary: Each[UpdatesListSummary]
  ): Values[(Alive,TxAddMeta)] = for {
    item ← updatesListSummary.items
  } yield WithPK(item.add)

  def keepRefs(
    key: SrcId,
    updatesListSummary: Each[UpdatesListSummary]
  ): Values[(Alive,TxRef)] = for {
    item ← updatesListSummary.items
  } yield WithPK(item.ref)

}
