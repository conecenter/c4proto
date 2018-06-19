package ee.cone.c4actor

import ee.cone.c4actor.QProtocol.Update
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4assemble._
import ee.cone.c4assemble.TreeAssemblerTypes.Replace
import ee.cone.c4assemble.Types._
import ee.cone.c4proto.Protocol

import scala.collection.immutable.Seq

object ProtocolDataDependencies {
  def apply(protocols: List[Protocol]): List[DataDependencyTo[_]] =
    protocols.flatMap(_.adapters.filter(_.hasId)).map{ adapter ⇒
      new OriginalWorldPart(ByPK.raw(adapter.className))
    }
}

case object TreeAssemblerKey extends SharedComponentKey[Replace]

class AssemblerInit(
  qAdapterRegistry: QAdapterRegistry,
  toUpdate: ToUpdate,
  treeAssembler: TreeAssembler,
  getDependencies: ()⇒List[DataDependencyTo[_]],
  isParallel: Boolean,
  composes: IndexFactory
) extends ToInject {
  private def toTree(assembled: ReadModel, updates: Iterable[Update]): ReadModel =
    (for {
      (valueTypeId, tpUpdates) ← updates.par.groupBy(_.valueTypeId)
      valueAdapter ← qAdapterRegistry.byId.get(valueTypeId)
      wKey = ByPK.raw[Product](valueAdapter.className)
      wasIndex = wKey.of(assembled)
      indexDiff ← composes.diffFromJoinRes(for {
        (srcId, iUpdates) ← tpUpdates.groupBy(_.srcId)
        remove = for{
          wasValues ← wasIndex.get(srcId).toSeq
          (prodH,count) ← wasValues
        } yield new JoinRes(srcId,prodH,-count)
        rawValue = iUpdates.last.value
        add = if(rawValue.size > 0) Seq(composes.result(srcId,valueAdapter.decode(rawValue),+1)) else Nil
        res ← remove ++ add
      } yield res)
    } yield wKey → indexDiff).seq

  private def reduce(out: Seq[Update], isParallel: Boolean): Context ⇒ Context = context ⇒ {
    val diff = toTree(context.assembled, out)
    val nAssembled = TreeAssemblerKey.of(context)(diff,isParallel)(context.assembled)
    new Context(context.injected, nAssembled, context.transient)
  }

  private def add(out: Seq[Update]): Context ⇒ Context =
    if(out.isEmpty) identity[Context]
    else WriteModelKey.modify(_.enqueue(out)).andThen(reduce(out.toList, isParallel = false))
  def toInject: List[Injectable] =
    TreeAssemblerKey.set(treeAssembler.replace(getDependencies())) :::
      WriteModelDebugAddKey.set(out ⇒
        if(out.isEmpty) identity[Context]
        else WriteModelDebugKey.modify(_.enqueue(out))
          .andThen(add(out.map(toUpdate.toUpdate)))
      ) :::
      WriteModelAddKey.set(add) :::
      ReadModelAddKey.set(reduce(_,isParallel))
}
