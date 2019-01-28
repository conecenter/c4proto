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

case class OrigKeyFactory(composes: IndexUtil) {
  def rawKey(className: String): AssembledKey =
    composes.joinKey(was=false, "SrcId", classOf[SrcId].getName, className)
}

case class ProtocolDataDependencies(protocols: List[Protocol], externalIds:Set[Long], origKeyFactory: OrigKeyFactory) {

  def apply(): List[DataDependencyTo[_]] =
    protocols.flatMap(_.adapters.filter(_.hasId)).filterNot(adapter ⇒ externalIds(adapter.id)).map{ adapter ⇒
      new OriginalWorldPart(origKeyFactory.rawKey(adapter.className))
    }
}

case object TreeAssemblerKey extends SharedComponentKey[Replace]

class AssemblerInit(
  qAdapterRegistry: QAdapterRegistry,
  toUpdate: ToUpdate,
  treeAssembler: TreeAssembler,
  getDependencies: ()⇒List[DataDependencyTo[_]],
  isParallel: Boolean,
  composes: IndexUtil,
  origKeyFactory: OrigKeyFactory,
  assembleProfiler: AssembleProfiler,
  readModelUtil: ReadModelUtil,
  actorName: String,
  processors: List[UpdatesPreprocessor]
  actorName: String,
  externalUpdateProcessor: ExtUpdateProcessor
) extends ToInject with LazyLogging {

  private def toTree(assembled: ReadModel, updates: DPIterable[Update]): ReadModel =
    readModelUtil.create((for {
      tpPair ← updates.groupBy(_.valueTypeId)
      (valueTypeId, tpUpdates) = tpPair
      _ = assert(!externalUpdateProcessor.idSet(valueTypeId), s"Got Updates for external Model $tpUpdates")
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
  private def reduce(replace: Replace, wasAssembled: ReadModel, diff: ReadModel): ReadModel = {
    val res = replace(wasAssembled,diff,isParallel,assembleProfiler.createJoiningProfiling(None)).map(_.result)
    concurrent.blocking{Await.result(res, Duration.Inf)}
  }

  private def offset(events: Seq[RawEvent]): List[Update] = for{
    ev ← events.lastOption.toList
    lEvent ← LEvent.update(Offset(actorName,ev.srcId))
  } yield toUpdate.toUpdate(lEvent)
  private def readModelAdd(replace: Replace): Seq[RawEvent]⇒ReadModel⇒ReadModel = events ⇒ assembled ⇒ try {
    val updates = offset(events) ::: toUpdate.toUpdates(events.toList)
    val realDiff = toTree(assembled, if(isParallel) updates.par else updates)
    val end = NanoTimer()
    val nAssembled = reduce(replace, assembled, realDiff)
    val period = end.ms
    if(period > 1000) logger.info(s"long join $period ms")
    nAssembled
  } catch {
    case NonFatal(e) ⇒
      logger.error("reduce", e) // ??? exception to record
      if(events.size == 1){
        val updates = offset(events) ++
          events.map(ev⇒FailedUpdates(ev.srcId, e.getMessage))
            .flatMap(LEvent.update).map(toUpdate.toUpdate)
        val failDiff = toTree(assembled, updates)
        reduce(replace, assembled, failDiff)
      } else {
        val(a,b) = events.splitAt(events.size / 2)
        Function.chain(Seq(readModelAdd(replace)(a), readModelAdd(replace)(b)))(assembled)
      }
  }
  // other parts:
  private def add(out: Seq[Update]): Context ⇒ Context = {
    val processedOut = processors.par.flatMap(_.process(out)).to[Seq] ++ out
    val externalOut = externalUpdateProcessor.process(out)
    if (externalOut.isEmpty) identity[Context]
    else { local ⇒
      val diff = toTree(local.assembled, externalOut)
      val profiling = assembleProfiler.createJoiningProfiling(Option(local))
      val replace = TreeAssemblerKey.of(local)
      val res = for {
        transition ← replace(local.assembled,diff,false,profiling)
        updates ← assembleProfiler.addMeta(transition, externalOut)
      } yield {
        val nLocal = new Context(local.injected, transition.result, local.transient)
        WriteModelKey.modify(_.enqueue(updates))(nLocal)
      }
      concurrent.blocking{Await.result(res, Duration.Inf)}
      //call add here for new mortal?
    }
  }

  private def getOrigIndex(context: AssembledContext, className: String): Map[SrcId,Product] = {
    val index = origKeyFactory.rawKey(className).of(context.assembled).value.get.get
    UniqueIndexMap(index)(composes)
  }

  def toInject: List[Injectable] =
    TreeAssemblerKey.set(treeAssembler.replace(getDependencies())) :::
      WriteModelDebugAddKey.set(out ⇒
        if(out.isEmpty) identity[Context]
        else WriteModelDebugKey.modify(_.enqueue(out))
          .andThen(add(out.map(toUpdate.toUpdate)))
      ) :::
      WriteModelAddKey.set(add) :::
      ReadModelAddKey.set(context⇒readModelAdd(TreeAssemblerKey.of(context))) :::
      GetOrigIndexKey.set(getOrigIndex)
}

case class UniqueIndexMap[K,V](index: Index)(indexUtil: IndexUtil) extends Map[K,V] {
  def +[B1 >: V](kv: (K, B1)): Map[K, B1] = iterator.toMap + kv
  def get(key: K): Option[V] = Single.option(indexUtil.getValues(index,key,"")).asInstanceOf[Option[V]]
  def iterator: Iterator[(K, V)] = indexUtil.keySet(index).iterator.map{ k ⇒ (k,Single(indexUtil.getValues(index,k,""))).asInstanceOf[(K,V)] }
  def -(key: K): Map[K, V] = iterator.toMap - key
  override def keysIterator: Iterator[K] = indexUtil.keySet(index).iterator.asInstanceOf[Iterator[K]] // to work with non-Single
}