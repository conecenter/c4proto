package ee.cone.c4gate

import ee.cone.c4actor.SyncTx.NeedSrcId
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor._
import ee.cone.c4assemble.Types.Values
import ee.cone.c4assemble.{Assemble, assemble, by}
import ee.cone.c4gate.AlienProtocol.PostConsumer

@assemble class PostConsumerAssemble(actorName: String) extends Assemble {
  def needConsumers(
    key: SrcId,
    consumers: Values[LocalPostConsumer]
  ): Values[(NeedSrcId,PostConsumer)] =
    for(c ← consumers.distinct)
      yield WithPK(PostConsumer(s"$actorName/${c.condition}", actorName, c.condition))
}

class PostConsumerAssembles(actorName: String, syncTxFactory: SyncTxFactory)(
  inner: List[Assemble] = List(
    new PostConsumerAssemble(actorName),
    syncTxFactory.create[PostConsumer](
      classOf[PostConsumer], c ⇒ c.consumer == actorName, _ ⇒ "PostConsumerSync",
      (key,tasks)⇒SimpleTxTransform(key,tasks.flatMap(_.events))
    )
  )
) extends Assemble {
  override def dataDependencies = f ⇒ inner.flatMap(_.dataDependencies(f))
}