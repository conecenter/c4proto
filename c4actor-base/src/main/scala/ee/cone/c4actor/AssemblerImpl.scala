package ee.cone.c4actor

import ee.cone.c4actor.QProtocol.Update
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4assemble.{AssembledKey, DataDependencyTo, OriginalWorldPart, TreeAssembler}
import ee.cone.c4assemble.TreeAssemblerTypes.Replace
import ee.cone.c4assemble.Types.Index
import ee.cone.c4proto.Protocol

import scala.collection.immutable.{Map, Seq}

object ProtocolDataDependencies {
  def apply(protocols: List[Protocol]): List[DataDependencyTo[_]] =
    protocols.flatMap(_.adapters.filter(_.hasId)).map{ adapter ⇒
      new OriginalWorldPart(ByPK.raw(adapter.className))
    }
}

case object TreeAssemblerKey extends SharedComponentKey[Replace]

class AssemblerInit(
  qAdapterRegistry: QAdapterRegistry,
  qMessages: QMessages,
  treeAssembler: TreeAssembler,
  getDependencies: ()⇒List[DataDependencyTo[_]]
) extends ToInject {
  private def toTree(updates: Iterable[Update]): Map[AssembledKey[Index[SrcId,Product]], Index[SrcId,Product]] =
    updates.groupBy(_.valueTypeId).flatMap { case (valueTypeId, tpUpdates) ⇒
      qAdapterRegistry.byId.get(valueTypeId).map(valueAdapter ⇒
        ByPK.raw[Product](valueAdapter.className) →
          tpUpdates.groupBy(_.srcId).map { case (srcId, iUpdates) ⇒
            val rawValue = iUpdates.last.value
            val values =
              if(rawValue.size > 0) valueAdapter.decode(rawValue) :: Nil else Nil
            srcId → values
          }
      )
    }
  private def reduce(out: Seq[Update]): Context ⇒ Context = context ⇒ new Context(
    context.injected,
    TreeAssemblerKey.of(context)(
      toTree(out).asInstanceOf[Map[AssembledKey[_],Index[Object,Object]]]
    )(context.assembled),
    context.transient
  )
  private def add(out: Seq[Update]): Context ⇒ Context =
    if(out.isEmpty) identity[Context]
    else WriteModelKey.modify(_.enqueue(out)).andThen(reduce(out.toList))
  def toInject: List[Injectable] =
    TreeAssemblerKey.set(treeAssembler.replace(getDependencies())) :::
      WriteModelDebugAddKey.set(out ⇒
        if(out.isEmpty) identity[Context]
        else WriteModelDebugKey.modify(_.enqueue(out))
          .andThen(add(out.map(qMessages.toUpdate)))
      ) :::
      WriteModelAddKey.set(add) :::
      ReadModelAddKey.set(reduce)
}

