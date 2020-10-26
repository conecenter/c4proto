package ee.cone.c4assemble

import ee.cone.c4assemble.Types.{DMap, Index, emptyIndex}
import ee.cone.c4di.c4

import scala.collection.immutable.Seq
import scala.concurrent.{ExecutionContext, Future}

@c4("AssembleApp") final class IndexUpdaterImpl(readModelUtil: ReadModelUtil) extends IndexUpdater {
  def setPart(worldKeys: Seq[AssembledKey], update: Future[IndexUpdates], logTask: Boolean): WorldTransition=>WorldTransition = transition => {
    implicit val ec: ExecutionContext = transition.executionContext.value
    val diff = readModelUtil.updated(worldKeys,update.map(_.diffs))(ec)(transition.diff)
    val next = readModelUtil.updated(worldKeys,update.map(_.results))(ec)(transition.result)
    val log = for {
      log <- transition.log
      u <- update
    } yield u.log ::: log
    val nTaskLog = if(logTask) worldKeys.toList ::: transition.taskLog else transition.taskLog
    transition.copy(diff=diff,result=next,log=log,taskLog=nTaskLog)
  }
}

@c4("AssembleApp") final class ReadModelUtilImpl(indexUtil: IndexUtil) extends ReadModelUtil {
  def create(inner: MMap): ReadModel =
    new ReadModelImpl(inner)
  def updated(worldKeys: Seq[AssembledKey], values: Future[Seq[Index]])(implicit ec: ExecutionContext): ReadModel=>ReadModel = {
    case from: ReadModelImpl =>
      val cValues = values.map{l=>assert(l.size==worldKeys.size);l}
      new ReadModelImpl(from.inner ++ worldKeys.zipWithIndex.map{ case (k,i) => k -> cValues.map(_(i)) })
  }

  def isEmpty(implicit executionContext: ExecutionContext): ReadModel=>Future[Boolean] = {
    case model: ReadModelImpl =>
      Future.sequence(model.inner.values).map(_.forall(indexUtil.isEmpty))
  }
  def op(op: (MMap,MMap)=>MMap): (ReadModel,ReadModel)=>ReadModel = Function.untupled({
    case (a: ReadModelImpl, b: ReadModelImpl) => new ReadModelImpl(op(a.inner,b.inner))
    case (_,_) =>  throw new Exception("ReadModel op")
  })
  def toMap: ReadModel=>Map[AssembledKey,Index] = {
    case model: ReadModelImpl => model.inner.transform((k,f) => indexUtil.getInstantly(f)) // .getOrElse(throw new Exception(s"index failure: $k"))
  }

  private def getInner(model: ReadModel): MMap = model match {
    case a: ReadModelImpl => a.inner
    case _ => Map.empty
  }
  def changesReady(prev: ReadModel, next: ReadModel)(implicit executionContext: ExecutionContext): Future[Any] = {
    /* val freshFutures = getInner(next).filterNot(getInner(prev).toSet).toSeq */
    val prevInner = getInner(prev)
    val freshFutures = getInner(next).collect{ case (k,v) if !prevInner.get(k).contains(v) => v }
    //println(s"freshFutures ${freshFutures.size}")
    Future.sequence(freshFutures)
  }
}

class ReadModelImpl(val inner: DMap[AssembledKey,Future[Index]]) extends ReadModel {
  def getFuture(key: AssembledKey): Option[Future[Index]] = inner.get(key)
}