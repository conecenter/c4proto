package ee.cone.c4gate

import ee.cone.c4actor.SyncTx.NeedSrcId
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor._
import ee.cone.c4assemble.Types.{Each, Values}
import ee.cone.c4assemble._
import ee.cone.c4gate.AlienProtocol.PostConsumer

@assemble class PostConsumerAssemble(
  actorName: String, syncTxFactory: SyncTxFactory
)(
  val subAssembles: List[Assemble] = List(syncTxFactory.create[PostConsumer](
    classOf[PostConsumer], c ⇒ c.consumer == actorName, _ ⇒ "PostConsumerSync",
    (key,tasks)⇒SimpleTxTransform(key,tasks.flatMap(_.events))
  ))
) extends CallerAssemble {
  def needConsumers(
    key: SrcId,
    @distinct c: Each[LocalPostConsumer]
  ): Values[(NeedSrcId,PostConsumer)] =
    List(WithPK(PostConsumer(s"$actorName/${c.condition}", actorName, c.condition)))
}
