package ee.cone.c4gate_server

import java.time.Instant

import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor.LifeTypes.Alive
import ee.cone.c4actor.QProtocol.S_Firstborn
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor.{Context, GetByPK, LEvent, MortalFactory, SleepUntilKey, TxAdd, TxTransform, WithPK}
import ee.cone.c4assemble.Types.{Each, Values}
import ee.cone.c4assemble.{Assemble, c4assemble}
import ee.cone.c4di.{c4, c4multi, provide}
import ee.cone.c4gate.ByPathHttpPublication
import ee.cone.c4gate.HttpProtocol.{S_HttpPublicationV1, S_HttpPublicationV2, S_Manifest}

@c4multi("AbstractHttpGatewayApp") case class PublicationPurgerTx(srcId: SrcId = "PublicationPurgerTx")(
  getS_Manifest: GetByPK[S_Manifest]
) extends TxTransform with LazyLogging {
  def transform(local: Context): Context = {
    val now = System.currentTimeMillis
    val events = getS_Manifest.ofA(local).values
      .filter(_.until<now).toSeq.sortBy(_.srcId).flatMap(LEvent.delete)
    TxAdd(events).andThen(SleepUntilKey.set(Instant.ofEpochMilli(now+15*1000)))(local)
  }
}
@c4assemble("AbstractHttpGatewayApp") class PublicationPurgerAssembleBase(
  factory: PublicationPurgerTxFactory
) {
  def joinTx(
    key: SrcId,
    firstborn: Each[S_Firstborn]
  ): Values[(SrcId, TxTransform)] =
    List(WithPK(factory.create()))

  def noLife(
    key: SrcId,
    firstborn: Each[S_Firstborn]
  ): Values[(Alive, S_HttpPublicationV1)] = Nil
}

@c4("AbstractHttpGatewayApp") class PublisherAssembles(mortal: MortalFactory) {
  @provide def subAssembles: Seq[Assemble] =
    List(mortal(classOf[S_HttpPublicationV1]), mortal(classOf[S_HttpPublicationV2]))
}

