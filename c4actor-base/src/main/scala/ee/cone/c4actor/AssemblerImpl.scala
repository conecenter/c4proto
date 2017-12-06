package ee.cone.c4actor

import ee.cone.c4actor.QProtocol.Update
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4assemble._
import ee.cone.c4assemble.TreeAssemblerTypes.Replace
import ee.cone.c4assemble.Types.Index
import ee.cone.c4proto.PBAdapters

import scala.collection.immutable.{Map, Seq}

@c4component @listed case class ProtocolsAssemble(
  protocols: List[PBAdapters], factory: ByPKFactory
) extends Assembled {
  def dataDependencies: List[DataDependencyTo[_]] = for {
    protocol <- protocols
    adapter <- protocol.adapters if adapter.hasId
  } yield new OriginalWorldPart(factory.joinKey(adapter.className))
}

case object TreeAssemblerKey extends SharedComponentKey[Replace]

@c4component @listed case class AssemblerInit(
  qAdapterRegistry: QAdapterRegistry,
  toUpdate: ToUpdate,
  treeAssembler: TreeAssembler,
  factory: ByPKFactory
)(
  getDependencies: ()⇒List[Assembled]
) extends ToInject {
  private def toTree(updates: Iterable[Update]): Map[AssembledKey[Index[SrcId,Product]], Index[SrcId,Product]] =
    updates.groupBy(_.valueTypeId).flatMap { case (valueTypeId, tpUpdates) ⇒
      qAdapterRegistry.byId.get(valueTypeId).map(valueAdapter ⇒
        factory.joinKey[Product](valueAdapter.className) →
          tpUpdates.groupBy(_.srcId).map { case (srcId, iUpdates) ⇒
            val rawValue = iUpdates.last.value
            val values =
              if(rawValue.size > 0) valueAdapter.decode(rawValue) :: Nil else Nil
            srcId → values
          }
      )
    }
  private def reduce(out: Seq[Update]): Context ⇒ Context = context ⇒ {
    val diff = toTree(out).asInstanceOf[Map[AssembledKey[_],Index[Object,Object]]]
    val nAssembled = TreeAssemblerKey.of(context)(diff)(context.assembled)
    new Context(context.injected, nAssembled, context.transient)
  }

  private def add(out: Seq[Update]): Context ⇒ Context =
    if(out.isEmpty) identity[Context]
    else WriteModelKey.modify(_.enqueue(out)).andThen(reduce(out.toList))
  def toInject: List[Injectable] =
    TreeAssemblerKey.set(treeAssembler.replace(getDependencies().flatMap(_.dataDependencies))) :::
      WriteModelDebugAddKey.set(out ⇒
        if(out.isEmpty) identity[Context]
        else WriteModelDebugKey.modify(_.enqueue(out))
          .andThen(add(out.map(toUpdate.toUpdate)))
      ) :::
      WriteModelAddKey.set(add) :::
      ReadModelAddKey.set(reduce)
}

