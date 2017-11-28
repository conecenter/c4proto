package ee.cone.c4gate

import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor._
import ee.cone.c4assemble.Types.Values
import ee.cone.c4assemble._
import ee.cone.c4gate.AlienProtocol.PostConsumer

@assemble class PostConsumerAssemble(actorName: ActorName) {

  type WasConsumer = SrcId
  def wasConsumers(
    key: SrcId,
    consumers: Values[PostConsumer]
  ): Values[(WasConsumer,PostConsumer)] =
    for(c ← consumers if c.consumer == actorName.value) yield WithPK(c)

  type NeedConsumer = SrcId
  def needConsumers(
    key: SrcId,
    consumers: Values[LocalPostConsumer]
  ): Values[(NeedConsumer,PostConsumer)] =
    for(c ← consumers.distinct)
      yield WithPK(PostConsumer(s"${actorName.value}/${c.condition}", actorName.value, c.condition))

  def syncConsumers(
    key: SrcId,
    @by[WasConsumer] wasConsumers: Values[PostConsumer],
    @by[NeedConsumer] needConsumers: Values[PostConsumer]
  ): Values[(SrcId,TxTransform)] =
    if(wasConsumers.toList == needConsumers.toList) Nil
    else List(WithPK(SimpleTxTransform(key,
      wasConsumers.flatMap(LEvent.delete) ++ needConsumers.flatMap(LEvent.update)
    )))
}

