package ee.cone.c4assemble

import ee.cone.c4assemble.Types.{DMap, Index}
import ee.cone.c4di.c4

import scala.collection.immutable.Seq
import scala.concurrent.{ExecutionContext, Future}

@c4("AssembleApp") final class IndexUpdaterImpl(readModelUtil: ReadModelUtil) extends IndexUpdater {
  def setPart(worldKeys: Seq[AssembledKey], update: IndexUpdates, logTask: Boolean): WorldTransition=>WorldTransition = transition => {
    val diff = readModelUtil.updated(worldKeys,update.diffs)(transition.diff)
    val next = readModelUtil.updated(worldKeys,update.results)(transition.result)
    val log = update.log ::: transition.log
    val nTaskLog = if(logTask) worldKeys.toList ::: transition.taskLog else transition.taskLog
    new WorldTransition(
      prev = transition.prev,
      diff = diff,
      result = next,
      profiling = transition.profiling,
      log = log,
      executionContext = transition.executionContext,
      taskLog = nTaskLog
    )
  }
}

@c4("AssembleApp") final class ReadModelUtilImpl(indexUtil: IndexUtil) extends ReadModelUtil {
  def create(inner: MMap): ReadModel =
    new ReadModelImpl(inner)
  def updated(worldKeys: Seq[AssembledKey], values: Seq[Index]): ReadModel=>ReadModel = {
    case from: ReadModelImpl =>
      assert(values.size==worldKeys.size)
      new ReadModelImpl(from.inner ++ worldKeys.zip(values))
  }

  def isEmpty: ReadModel=>Boolean = {
    case model: ReadModelImpl =>
      model.inner.values.forall(indexUtil.isEmpty)
  }
  def op(op: (MMap,MMap)=>MMap): (ReadModel,ReadModel)=>ReadModel = Function.untupled({
    case (a: ReadModelImpl, b: ReadModelImpl) => new ReadModelImpl(op(a.inner,b.inner))
    case (_,_) =>  throw new Exception("ReadModel op")
  })
  def toMap: ReadModel=>Map[AssembledKey,Index] = {
    case model: ReadModelImpl => model.inner
  }
}

class ReadModelImpl(val inner: DMap[AssembledKey,Index]) extends ReadModel {
  def getIndex(key: AssembledKey): Option[Index] = inner.get(key)
}