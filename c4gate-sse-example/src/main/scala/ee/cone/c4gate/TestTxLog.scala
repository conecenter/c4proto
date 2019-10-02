package ee.cone.c4gate

import com.squareup.wire.ProtoAdapter
import ee.cone.c4actor.LifeTypes.Alive
import ee.cone.c4actor.QProtocol.N_TxRef
import ee.cone.c4actor._
import ee.cone.c4actor.SimpleAssembleProfilerProtocol.D_TxAddMeta
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4assemble.Types.{Each, Values}
import ee.cone.c4assemble.{Assemble, CallerAssemble, assemble, by}
import ee.cone.c4gate.AlienProtocol.U_ToAlienWrite
import ee.cone.c4gate.HttpProtocol.S_HttpPublication
import ee.cone.c4gate.TestFilterProtocol.B_Content
import ee.cone.c4proto.{HasId, Id, c4component}
import ee.cone.c4ui.{ByLocationHashView, UntilPolicy}
import ee.cone.c4vdom.{ChildPair, OfDiv, Tags}
import ee.cone.c4vdom.Types.ViewRes

import scala.annotation.tailrec

/*trait TestTxLogApp extends TestTxLogAutoApp {
  def tags: Tags
  def untilPolicy: UntilPolicy
  def snapshotMerger: SnapshotMerger
  def sessionAttrAccessFactory: SessionAttrAccessFactory
  def testTags: TestTags[Context]

  def componentRegistry: ComponentRegistry

  private lazy val testTxLogView = TestTxLogView()(
    resolveSingle(classOf[ActorName]), untilPolicy, tags, snapshotMerger, componentRegistry.resolveSingle(classOf[SnapshotTaskSigner]), sessionAttrAccessFactory, testTags
  )


  override def byLocationHashViews: List[ByLocationHashView] =
    testTxLogView :: super.byLocationHashViews
}*/

@assemble("TestTxLogApp") class TestTxLogMortalAssembleBase(mortal: MortalFactory) extends CallerAssemble {
  override def subAssembles: List[Assemble] =
    mortal(classOf[N_TxRef]) :: mortal(classOf[D_TxAddMeta]) :: super.subAssembles
}
@c4component("TestTxLogApp") case class TestTxLogView(locationHash: String = "txlog")(
  actorName: ActorName,
  untilPolicy: UntilPolicy,
  mTags: Tags,
  snapshotMerger: SnapshotMerger,
  signer: SnapshotTaskSigner,
  sessionAttrAccess: SessionAttrAccessFactory,
  tags: TestTags[Context]
) extends ByLocationHashView {
  def view: Context => ViewRes = untilPolicy.wrap { local =>
    import mTags._

    val logs: List[ChildPair[OfDiv]] = for{
      updatesListSummary <- ByPK(classOf[UpdatesListSummary]).of(local).get(actorName.value).toList
      updatesSummary <- updatesListSummary.items
      add = updatesSummary.add
    } yield div(s"tx${add.srcId}",List())(
      text("text",
        s"tx: ${updatesSummary.ref.txId}," +
          s" objects: ${add.updObjCount}," +
          s" bytes: ${add.updByteCount}," +
          s" types: ${add.updValueTypeIds.map(java.lang.Long.toHexString).mkString(", ")}" +
          s" period: ${add.finishedAt-add.startedAt}"
      ) ::
      (for {
        (logEntry,idx) <- add.log.zipWithIndex
      } yield div(s"$idx",Nil)(
        text("text",
          s" ** ${logEntry.value} ${logEntry.name}"
        ) :: Nil
      ))
    )

    def getAccess(attr: SessionAttr[B_Content]): Option[Access[String]] =
      sessionAttrAccess.to(attr)(local).map(_.to(TestContentAccess.value))

    val baseURLAccessOpt = getAccess(TestTxLogAttrs.baseURL)
    val authKeyAccessOpt = getAccess(TestTxLogAttrs.authKey)
//signer.sign()
    val inputs: List[ChildPair[OfDiv]] =
      List(baseURLAccessOpt,authKeyAccessOpt).flatten.map(tags.input)

    val merge: Option[ChildPair[OfDiv]] = for {
      baseURLAccess <- baseURLAccessOpt if baseURLAccess.initialValue.nonEmpty
      authKeyAccess <- authKeyAccessOpt if authKeyAccess.initialValue.nonEmpty
    } yield {
      divButton[Context]("merge")(
        snapshotMerger.merge(baseURLAccess.initialValue,authKeyAccess.initialValue)
      )(List(text("text",s"merge ${baseURLAccess.initialValue} ${authKeyAccess.initialValue}")))
    }

    inputs ::: merge.toList ::: logs
  }
}

//TestContentAccess

object TestTxLogAttrs {
  lazy val baseURL = SessionAttr(Id(0x000A), classOf[B_Content], UserLabel en "(baseURL)")
  lazy val authKey = SessionAttr(Id(0x000B), classOf[B_Content], UserLabel en "(authKey)")
}

case class UpdatesSummary(add: D_TxAddMeta, ref: N_TxRef)
case class UpdatesListSummary(srcId: SrcId, items: List[UpdatesSummary], txCount: Long, objCount: Long, byteCount: Long)

@assemble("TestTxLogApp") class TestTxLogAssembleBase(actorName: ActorName)(
  qAdapterRegistry: QAdapterRegistry
)(
  metaAdapter: ProtoAdapter[D_TxAddMeta] with HasId =
    qAdapterRegistry.byName(classOf[D_TxAddMeta].getName)
      .asInstanceOf[ProtoAdapter[D_TxAddMeta] with HasId]
)   {
  type SummaryId = SrcId

  def mapMeta(
    key: SrcId,
    txRef: Each[N_TxRef],
    txAdd: Each[D_TxAddMeta]
  ): Values[(SummaryId,UpdatesSummary)] =
    List(actorName.value -> UpdatesSummary(txAdd, txRef))

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

    val skipIds = Seq(classOf[U_ToAlienWrite],classOf[S_HttpPublication],classOf[D_TxAddMeta],classOf[N_TxRef])
      .map(cl=>qAdapterRegistry.byName(cl.getName).id).toSet

    List(WithPK(headToKeep(
      UpdatesListSummary(key,Nil,0L,0L,0L),
      updatesSummary.filterNot(_.add.updValueTypeIds.forall(skipIds))
        .sortBy(_.ref.txId).toList.reverse
    )))
  }

  def keepAdds(
    key: SrcId,
    updatesListSummary: Each[UpdatesListSummary]
  ): Values[(Alive,D_TxAddMeta)] = for {
    item <- updatesListSummary.items
  } yield WithPK(item.add)

  def keepRefs(
    key: SrcId,
    updatesListSummary: Each[UpdatesListSummary]
  ): Values[(Alive,N_TxRef)] = for {
    item <- updatesListSummary.items
  } yield WithPK(item.ref)

}
