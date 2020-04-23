package ee.cone.c4assemble

import ee.cone.c4assemble.Types.{DMap, Index, emptyIndex}
import ee.cone.c4di.c4

import scala.concurrent.{ExecutionContext, Future}

@c4("AssembleApp") final class IndexUpdaterImpl(readModelUtil: ReadModelUtil) extends IndexUpdater {
  def setPart(worldKey: AssembledKey, update: Future[IndexUpdate], logTask: Boolean): WorldTransition=>WorldTransition = transition => {
    implicit val executionContext: ExecutionContext = transition.executionContext.value
    val diff = readModelUtil.updated(worldKey,update.map(_.diff))(transition.diff)
    val next = readModelUtil.updated(worldKey,update.map(_.result))(transition.result)
    val log = for {
      log <- transition.log
      u <- update
    } yield u.log ::: log
    val nTaskLog = if(logTask) worldKey :: transition.taskLog else transition.taskLog
    transition.copy(diff=diff,result=next,log=log,taskLog=nTaskLog)
  }
}

@c4("AssembleApp") final class ReadModelUtilImpl(indexUtil: IndexUtil) extends ReadModelUtil {
  def create(inner: MMap): ReadModel =
    new ReadModelImpl(inner)
  def updated(worldKey: AssembledKey, value: Future[Index]): ReadModel=>ReadModel = {
    case from: ReadModelImpl => new ReadModelImpl(from.inner + (worldKey -> value))
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
  def ready(implicit executionContext: ExecutionContext): ReadModel=>Future[ReadModel] = {
    case model: ReadModelImpl =>
      for {
        ready <- Future.sequence(model.inner.values)
      } yield model
  }
}

class ReadModelImpl(val inner: DMap[AssembledKey,Future[Index]]) extends ReadModel {
  def getFuture(key: AssembledKey): Option[Future[Index]] = inner.get(key)
}