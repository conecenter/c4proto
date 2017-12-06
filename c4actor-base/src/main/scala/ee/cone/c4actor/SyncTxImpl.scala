package ee.cone.c4actor

import ee.cone.c4actor.SyncTx.NeedSrcId
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4assemble.Types.Values
import ee.cone.c4assemble._

//todo RDBImpl

@c4component case class SyncTxFactoryImpl()(
  wrap: SyncTxAssemble[_] ⇒ Assembled
) extends SyncTxFactory {
  def apply[Item<:Product](classOfItem: Class[Item])(
    filter: Item⇒Boolean,
    group: Item⇒SrcId,
    txTransform: Option[(SrcId,List[SyncTxTask[Item]])⇒TxTransform]
  ): Assembled = {
    val txTr = txTransform.getOrElse(SyncTxTransform[Item](_,_))
    wrap(new SyncTxAssemble(classOfItem,filter,group,txTr))
  }
}

case class SyncTxTransform[Item<:Product](
  srcId: SrcId, tasks: List[SyncTxTask[Item]]
) extends TxTransform {
  def transform(local: Context): Context = TxAdd(tasks.flatMap(_.events))(local)
}

@assemble class SyncTxAssemble[Item<:Product](
  classOfItem: Class[Item],
  filter: Item⇒Boolean,
  group: Item⇒SrcId,
  txTransform: (SrcId,List[SyncTxTask[Item]])⇒TxTransform
) {
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
        val events = has.flatMap(LEvent.delete) ::: need.flatMap(LEvent.update)
        val task = SyncTxTask(key, Single.option(has), Single.option(need), events)
        List(executorId -> task)
    }

  def makeExecutors(
    key: SrcId,
    @by[ExecutorId] tasks: Values[SyncTxTask[Item]]
  ): Values[(SrcId,TxTransform)] = List(WithPK(txTransform(key,tasks.toList)))
}

