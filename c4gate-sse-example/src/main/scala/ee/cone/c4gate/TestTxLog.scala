package ee.cone.c4gate

import com.squareup.wire.ProtoAdapter
import ee.cone.c4actor._
import ee.cone.c4actor.QProtocol.Updates
import ee.cone.c4actor.SimpleAssembleProfilerProtocol.TxAddMeta
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4assemble.Types.{Each, Values}
import ee.cone.c4assemble.{All, Assemble, assemble, by}
import ee.cone.c4gate.AlienProtocol.ToAlienWrite
import ee.cone.c4gate.HttpProtocol.HttpPublication
import ee.cone.c4proto.HasId
import ee.cone.c4ui.{ByLocationHashView, ByLocationHashViewsApp, UntilPolicy}
import ee.cone.c4vdom.Tags
import ee.cone.c4vdom.Types.ViewRes

import scala.annotation.tailrec

trait TestTxLogApp extends AssemblesApp with ByLocationHashViewsApp {
  def tags: Tags
  def untilPolicy: UntilPolicy
  def qAdapterRegistry: QAdapterRegistry

  private lazy val testTxLogView = TestTxLogView()(actorName, untilPolicy, tags)
  private lazy val actorName = getClass.getName

  override def assembles: List[Assemble] =
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
    } yield div(s"tx${updatesSummary.srcId}",List())(
      text("text",
        s"tx: ${updatesSummary.srcId}," +
          s" objects: ${updatesSummary.objCount}," +
          s" bytes: ${updatesSummary.byteCount}," +
          s" types: ${updatesSummary.valueTypeIds.map(java.lang.Long.toHexString).mkString(", ")}"
      ) :: (for {
        (txAddMeta,idx) ← updatesSummary.txAddMetaList.zipWithIndex
      } yield div(s"$idx",Nil)(
        text("text",
          s" * $idx ${txAddMeta.finishedAt-txAddMeta.startedAt}"
        ) :: (for {
          (logEntry,idx) ← txAddMeta.log.zipWithIndex
        } yield div(s"$idx",Nil)(
          text("text",
            s" ** ${logEntry.value} ${logEntry.name}"
          ) :: Nil
        ))
      ))
    )
  }
}

case class UpdatesSummary(srcId: SrcId, objCount: Long, byteCount: Long, valueTypeIds: List[Long], txAddMetaList: List[TxAddMeta])
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
    updates: Each[Updates]
  ): Values[(SummaryId,UpdatesSummary)] =
    List(actorName → UpdatesSummary(
      updates.srcId,
      updates.updates.size,
      updates.updates.map(_.value.size).sum,
      updates.updates.map(_.valueTypeId).distinct,
      updates.updates.filter(u⇒u.valueTypeId==metaAdapter.id).map(u⇒metaAdapter.decode(u.value))
    ))

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
          res.objCount + in.head.objCount,
          res.byteCount + in.head.byteCount
        )
        if(will.txCount > 20 ||
          will.objCount > 10000 ||
          will.byteCount > 100000000
        ) res
        else headToKeep(will, in.tail)
      }

    val skipIds = Seq(classOf[ToAlienWrite],classOf[HttpPublication],classOf[TxAddMeta])
      .map(cl⇒qAdapterRegistry.byName(cl.getName).id).toSet


    List(WithPK(headToKeep(
      UpdatesListSummary(key,Nil,0L,0L,0L),
      updatesSummary.filterNot(_.valueTypeIds.forall(skipIds))
        .sortBy(_.srcId).toList.reverse
    )))
  }

  def keep(
    key: SrcId,
    updatesListSummary: Each[UpdatesListSummary]
  ): Values[(SrcId,KeepUpdates)] = for {
    item ← updatesListSummary.items
  } yield WithPK(KeepUpdates(item.srcId))
}
