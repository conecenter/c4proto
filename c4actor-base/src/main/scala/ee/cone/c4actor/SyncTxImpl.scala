package ee.cone.c4actor

import ee.cone.c4actor.SyncTx.NeedSrcId
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4assemble.Types.Values
import ee.cone.c4assemble.{Assemble, Single, assemble, by}
import ee.cone.c4proto.c4

//todo RDBImpl
@c4("SyncTxFactoryImplApp") class SyncTxFactoryImpl extends SyncTxFactory {
  def create[D_Item<:Product](
    classOfItem: Class[D_Item],
    filter: D_Item=>Boolean,
    group: D_Item=>SrcId,
    txTransform: (SrcId,List[SyncTxTask[D_Item]])=>TxTransform
  ): Assemble = new SyncTxAssemble(classOfItem,filter,group,txTransform)
}

@assemble class SyncTxAssembleBase[D_Item<:Product](
  classOfItem: Class[D_Item],
  filter: D_Item=>Boolean,
  group: D_Item=>SrcId,
  txTransform: (SrcId,List[SyncTxTask[D_Item]])=>TxTransform
)   {
  type ExecutorId = SrcId
  def makeTasks(
    key: SrcId,
    items: Values[D_Item],
    @by[NeedSrcId] needItems: Values[D_Item]
  ): Values[(ExecutorId,SyncTxTask[D_Item])] =
    (items.filter(filter).toList,needItems.filter(filter).toList) match {
      case (has,need) if has == need => Nil
      case (has,need) =>
        val executorId = Single((has ::: need).map(group).distinct)
        val events = (has.flatMap(LEvent.delete) ::: need.flatMap(LEvent.update)).asInstanceOf[List[LEvent[D_Item]]]
        val task = SyncTxTask(key, Single.option(has), Single.option(need), events)
        List(executorId -> task)
    }

  def makeExecutors(
    key: SrcId,
    @by[ExecutorId] tasks: Values[SyncTxTask[D_Item]]
  ): Values[(SrcId,TxTransform)] = List(WithPK(txTransform(key,tasks.toList)))
}
