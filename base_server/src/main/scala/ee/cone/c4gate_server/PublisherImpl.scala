package ee.cone.c4gate_server

import java.time.Instant
import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor.Types.{SrcId, TxEvents}
import ee.cone.c4actor._
import ee.cone.c4di.c4
import ee.cone.c4gate.PurgePublication
import ee.cone.c4gate.HttpProtocol.{S_HttpPublicationV1, S_HttpPublicationV2, S_Manifest}

@c4("AbstractHttpGatewayApp") final case class PublicationPurgerTx(srcId: SrcId = "PublicationPurgerTx")(
  getS_Manifest: GetByPK[S_Manifest], getS_HttpPublicationV1: GetByPK[S_HttpPublicationV1],
  getS_HttpPublicationV2: GetByPK[S_HttpPublicationV2], getPurgePublication: GetByPK[PurgePublication], sleep: Sleep,
) extends SingleTxTr with LazyLogging {
  def transform(local: Context): TxEvents = {
    val now = System.currentTimeMillis
    val outdatedManifests = getS_Manifest.ofA(local).values.filter(_.until<now).toSeq.sortBy(_.srcId)
    val deprecatedPublications = getS_HttpPublicationV1.ofA(local).values.toSeq.sortBy(_.path)
    val publications = getS_HttpPublicationV2.ofA(local)
    val inactivePublications = getPurgePublication.ofA(local).keys.toSeq.sorted.map(publications)
    (outdatedManifests ++ deprecatedPublications ++ inactivePublications).flatMap(LEvent.delete) ++
      sleep.untilMillis(now+15*1000)
  }
}
