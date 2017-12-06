package ee.cone.c4gate

import ee.cone.c4actor.SyncTx.NeedSrcId
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor._
import ee.cone.c4assemble.Types.Values
import ee.cone.c4assemble._
import ee.cone.c4gate.AlienProtocol.PostConsumer

@assemble class PostConsumerAssemble(actorName: ActorName) {
  def needConsumers(
    key: SrcId,
    consumers: Values[LocalPostConsumer]
  ): Values[(NeedSrcId,PostConsumer)] =
    for(c ← consumers.distinct)
      yield WithPK(PostConsumer(s"${actorName.value}/${c.condition}", actorName.value, c.condition))
}

@c4component @listed case class PostConsumerAssembles(actorName: ActorName, create: SyncTxFactory)(
  val inner: Assembled =
    create(classOf[PostConsumer])(c ⇒ c.consumer == actorName.value, _ ⇒ "PostConsumerSync", None)
) extends Assembled with WrapAssembled