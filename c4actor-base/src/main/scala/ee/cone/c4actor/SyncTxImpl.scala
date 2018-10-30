package ee.cone.c4actor

import ee.cone.c4actor.SyncTx.NeedSrcId
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4assemble.Types.Values
import ee.cone.c4assemble.{Assemble, Single, assemble, by}

//todo RDBImpl

class SyncTxFactoryImpl extends SyncTxFactory {
  def create[Item<:Product](
    classOfItem: Class[Item],
    filter: Item⇒Boolean,
    group: Item⇒SrcId,
    txTransform: (SrcId,List[SyncTxTask[Item]])⇒TxTransform
  ): Assemble = new SyncTxAssemble(classOfItem,filter,group,txTransform)
}

@assemble class SyncTxAssemble[Item<:Product](
  classOfItem: Class[Item],
  filter: Item⇒Boolean,
  group: Item⇒SrcId,
  txTransform: (SrcId,List[SyncTxTask[Item]])⇒TxTransform
) extends Assemble {
  type ExecutorId = SrcId
  def makeTasks(
    key: SrcId,
    items: Values[Item],
    @by[NeedSrcId] needItems: Values[Item]
  ): Values[(ExecutorId,SyncTxTask[Item])] =
    (items.filter(filter).toList,needItems.filter(filter).toList) match {
      case (has,need) if has == need ⇒ Nil
      case (has,need) ⇒
        val executorId = Single((has ::: need).map(group).distinct)
        val events = (has.flatMap(LEvent.delete) ::: need.flatMap(LEvent.update)).asInstanceOf[List[LEvent[Item]]]
        val task = SyncTxTask(key, Single.option(has), Single.option(need), events)
        List(executorId -> task)
    }

  def makeExecutors(
    key: SrcId,
    @by[ExecutorId] tasks: Values[SyncTxTask[Item]]
  ): Values[(SrcId,TxTransform)] = List(WithPK(txTransform(key,tasks.toList)))
}
