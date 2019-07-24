package ee.cone.c4gate

import ee.cone.c4actor.SyncTx.NeedSrcId
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor._
import ee.cone.c4assemble.Types.{Each, Values}
import ee.cone.c4assemble._
import ee.cone.c4gate.AlienProtocol.E_PostConsumer

@assemble class PostConsumerAssembleBase(
  actorName: String, syncTxFactory: SyncTxFactory
)(
  val subAssembles: List[Assemble] = List(syncTxFactory.create[E_PostConsumer](
    classOf[E_PostConsumer], c ⇒ c.consumer == actorName, _ ⇒ "PostConsumerSync",
    (key,tasks)⇒SimpleTxTransform(key,tasks.flatMap(_.events))
  ))
) extends CallerAssemble {
  def needConsumers(
    key: SrcId,
    @distinct c: Each[LocalPostConsumer]
  ): Values[(NeedSrcId,E_PostConsumer)] =
    List(WithPK(E_PostConsumer(s"$actorName/${c.condition}", actorName, c.condition)))
}
