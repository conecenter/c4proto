package ee.cone.c4gate

import ee.cone.c4actor.SyncTx.NeedSrcId
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor._
import ee.cone.c4assemble.Types.{Each, Values}
import ee.cone.c4assemble._
import ee.cone.c4gate.AlienProtocol.E_HttpConsumer

@assemble("ManagementApp") class HttpConsumerAssembleBase(
  actorName: ActorName, syncTxFactory: SyncTxFactory
) extends CallerAssemble {
  def needConsumers(
    key: SrcId,
    @distinct c: Each[LocalHttpConsumer]
  ): Values[(NeedSrcId,E_HttpConsumer)] =
    List(WithPK(E_HttpConsumer(s"$actorName/${c.condition}", actorName.value, c.condition)))

  override def subAssembles: List[Assemble] = List(syncTxFactory.create[E_HttpConsumer](
    classOf[E_HttpConsumer], c => c.consumer == actorName.value, _ => "PostConsumerSync",
    (key,tasks)=>SimpleTxTransform(key,tasks.flatMap(_.events))
  )) ::: super.subAssembles
}
