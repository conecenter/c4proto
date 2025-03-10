package ee.cone.c4gate_server

import java.time.Instant
import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor.QProtocol.S_Firstborn
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor.{Context, GetByPK, LEvent, LTxAdd, SleepUntilKey, TxTransform, WithPK}
import ee.cone.c4assemble.Types.{Each, Values}
import ee.cone.c4assemble.c4assemble
import ee.cone.c4di.c4multi
import ee.cone.c4gate.PurgePublication
import ee.cone.c4gate.HttpProtocol.{S_HttpPublicationV1, S_HttpPublicationV2, S_Manifest}

@c4multi("AbstractHttpGatewayApp") final case class PublicationPurgerTx(srcId: SrcId = "PublicationPurgerTx")(
  txAdd: LTxAdd, getS_Manifest: GetByPK[S_Manifest], getS_HttpPublicationV1: GetByPK[S_HttpPublicationV1],
  getS_HttpPublicationV2: GetByPK[S_HttpPublicationV2], getPurgePublication: GetByPK[PurgePublication],
) extends TxTransform with LazyLogging {
  def transform(local: Context): Context = {
    val now = System.currentTimeMillis
    val outdatedManifests = getS_Manifest.ofA(local).values.filter(_.until<now).toSeq.sortBy(_.srcId)
    val deprecatedPublications = getS_HttpPublicationV1.ofA(local).values.toSeq.sortBy(_.path)
    val publications = getS_HttpPublicationV2.ofA(local)
    val inactivePublications = getPurgePublication.ofA(local).keys.toSeq.sorted.map(publications)
    val events = (outdatedManifests ++ deprecatedPublications ++ inactivePublications).flatMap(LEvent.delete)
    txAdd.add(events).andThen(SleepUntilKey.set(Instant.ofEpochMilli(now+15*1000)))(local)
  }
}
@c4assemble("AbstractHttpGatewayApp") class PublicationPurgerAssembleBase(factory: PublicationPurgerTxFactory) {
  def joinTx(key: SrcId, firstborn: Each[S_Firstborn]): Values[(SrcId, TxTransform)] = List(WithPK(factory.create()))
}