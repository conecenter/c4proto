package ee.cone.c4actor

import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor.QProtocol._
import ee.cone.c4actor.Types._
import ee.cone.c4assemble._
import ee.cone.c4assemble.TreeAssemblerTypes.Replace
import ee.cone.c4assemble.Types._
import ee.cone.c4proto.Protocol

import scala.collection.immutable.{Map, Seq}
import scala.concurrent.{Await, Future}
import scala.util.control.NonFatal
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.Duration

case class ProtocolDataDependencies(protocols: List[Protocol], origKeyFactory: KeyFactory) {
  def apply(): List[DataDependencyTo[_]] =
    protocols.flatMap(_.adapters.filter(_.hasId)).map { adapter ⇒
      new OriginalWorldPart(origKeyFactory.rawKey(adapter.className))
    }
}

case object TreeAssemblerKey extends SharedComponentKey[Replace]

class AssemblerInit(
  qAdapterRegistry: QAdapterRegistry,
  toUpdate: ToUpdate,
  treeAssembler: TreeAssembler,
  getDependencies: ()⇒List[DataDependencyTo[_]],
  composes: IndexUtil,
  byPKKeyFactory: KeyFactory,
  origKeyFactory: KeyFactory,
  assembleProfiler: AssembleProfiler,
  readModelUtil: ReadModelUtil,
  actorName: String,
  updateProcessor: UpdateProcessor,
  processors: List[UpdatesPreprocessor],
  defaultAssembleOptions: AssembleOptions
)(
  assembleOptionsOuterKey: AssembledKey = origKeyFactory.rawKey(classOf[AssembleOptions].getName),
  assembleOptionsInnerKey: String = ToPrimaryKey(defaultAssembleOptions)
) extends ToInject with LazyLogging {

  private def toTree(assembled: ReadModel, updates: DPIterable[Update]): ReadModel =
    readModelUtil.create((for {
      tpPair ← updates.groupBy(_.valueTypeId)
      (valueTypeId, tpUpdates) = tpPair
      valueAdapter ← qAdapterRegistry.byId.get(valueTypeId)
      wKey = origKeyFactory.rawKey(valueAdapter.className)
    } yield {
      wKey → (for {
        wasIndex ← wKey.of(assembled)
      } yield composes.mergeIndex(for {
        iPair ← tpUpdates.groupBy(_.srcId)
        (srcId, iUpdates) = iPair
        rawValue = iUpdates.last.value
        remove = composes.removingDiff(wasIndex,srcId)
        add = if(rawValue.size > 0) composes.result(srcId,valueAdapter.decode(rawValue),+1) :: Nil else Nil
        res ← remove :: add
      } yield res))
    }).seq.toMap)

  // read model part:
  private def reduce(replace: Replace, wasAssembled: ReadModel, diff: ReadModel, options: AssembleOptions): ReadModel = {
    val res = replace(wasAssembled,diff,options,assembleProfiler.createJoiningProfiling(None)).map(_.result)
    concurrent.blocking{Await.result(res, Duration.Inf)}
  }

  private def offset(events: Seq[RawEvent]): List[Update] = for{
    ev ← events.lastOption.toList
    lEvent ← LEvent.update(Offset(actorName,ev.srcId))
  } yield toUpdate.toUpdate(lEvent)
  private def readModelAdd(replace: Replace): Seq[RawEvent]⇒ReadModel⇒ReadModel = events ⇒ assembled ⇒ try {
    val options = getAssembleOptions(assembled)
    val updates = offset(events) ::: toUpdate.toUpdates(events.toList)
    val realDiff = toTree(assembled, composes.mayBePar(updates, options))
    val end = NanoTimer()
    val nAssembled = reduce(replace, assembled, realDiff, options)
    val period = end.ms
    if(period > 1000) logger.info(s"${options.toString} long join $period ms")
    nAssembled
  } catch {
    case NonFatal(e) ⇒
      logger.error("reduce", e) // ??? exception to record
      if(events.size == 1){
        val options = getAssembleOptions(assembled)
        val updates = offset(events) ++
          events.map(ev⇒FailedUpdates(ev.srcId, e.getMessage))
            .flatMap(LEvent.update).map(toUpdate.toUpdate)
        val failDiff = toTree(assembled, updates)
        reduce(replace, assembled, failDiff, options)
      } else {
        val(a,b) = events.splitAt(events.size / 2)
        Function.chain(Seq(readModelAdd(replace)(a), readModelAdd(replace)(b)))(assembled)
      }
  }
  // other parts:
  private def add(out: Seq[Update]): Context ⇒ Context = {
    if (out.isEmpty) identity[Context]
    else { local ⇒
      val options = getAssembleOptions(local.assembled)
      val processedOut = composes.mayBePar(processors, options).flatMap(_.process(out)).to[Seq] ++ out
      val externalOut = updateProcessor.process(processedOut)
      val diff = toTree(local.assembled, externalOut)
      val profiling = assembleProfiler.createJoiningProfiling(Option(local))
      val replace = TreeAssemblerKey.of(local)
      val res = for {
        transition ← replace(local.assembled,diff,options,profiling)
        updates ← assembleProfiler.addMeta(transition, externalOut)
      } yield {
        val nLocal = new Context(local.injected, transition.result, local.transient)
        WriteModelKey.modify(_.enqueue(updates))(nLocal)
      }
      concurrent.blocking{Await.result(res, Duration.Inf)}
      //call add here for new mortal?
    }
  }

  private def getAssembleOptions(assembled: ReadModel): AssembleOptions = {
    val index = assembleOptionsOuterKey.of(assembled).value.get.get
    composes.getValues(index,assembleOptionsInnerKey,"",defaultAssembleOptions).collectFirst{
      case o: AssembleOptions ⇒ o
    }.getOrElse(defaultAssembleOptions)
  }

  private def getOrigIndex(context: AssembledContext, className: String): Map[SrcId,Product] = {
    val index = byPKKeyFactory.rawKey(className).of(context.assembled).value.get.get
    val options = getAssembleOptions(context.assembled)
    UniqueIndexMap(index,options)(composes)
  }

  def toInject: List[Injectable] = {
    logger.debug("getDependencies started")
    val deps = getDependencies()
    logger.debug("getDependencies finished")
    TreeAssemblerKey.set(treeAssembler.replace(deps)) :::
      WriteModelDebugAddKey.set(out ⇒
        if(out.isEmpty) identity[Context]
        else WriteModelDebugKey.modify(_.enqueue(out))
          .andThen(add(out.map(toUpdate.toUpdate)))
      ) :::
      WriteModelAddKey.set(add) :::
      ReadModelAddKey.set(context⇒readModelAdd(TreeAssemblerKey.of(context))) :::
      GetOrigIndexKey.set(getOrigIndex)
  }
}

case class UniqueIndexMap[K,V](index: Index, options: AssembleOptions)(indexUtil: IndexUtil) extends Map[K,V] {
  def +[B1 >: V](kv: (K, B1)): Map[K, B1] = iterator.toMap + kv
  def get(key: K): Option[V] = Single.option(indexUtil.getValues(index,key,"",options)).asInstanceOf[Option[V]]
  def iterator: Iterator[(K, V)] = indexUtil.keySet(index).iterator.map{ k ⇒ (k,Single(indexUtil.getValues(index,k,"",options))).asInstanceOf[(K,V)] }
  def -(key: K): Map[K, V] = iterator.toMap - key
  override def keysIterator: Iterator[K] = indexUtil.keySet(index).iterator.asInstanceOf[Iterator[K]] // to work with non-Single
}