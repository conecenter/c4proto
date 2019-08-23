package ee.cone.c4assemble

import ee.cone.c4assemble.Types.{DMap, Index, emptyIndex}

import scala.concurrent.{ExecutionContext, Future}
//import scala.concurrent.ExecutionContext.Implicits.global

class IndexUpdaterImpl(readModelUtil: ReadModelUtil) extends IndexUpdater {
  def setPart[K,V](worldKey: AssembledKey)(
    update: Future[IndexUpdate]
  ): WorldTransition⇒WorldTransition = transition ⇒ {
    implicit val executionContext: ExecutionContext = transition.executionContext
    val diff = readModelUtil.updated(worldKey,update.map(_.diff))(transition.diff)
    val next = readModelUtil.updated(worldKey,update.map(_.result))(transition.result)
    val log = for {
      log ← transition.log
      u ← update
    } yield u.log ::: log
    transition.copy(diff=diff,result=next,log=log)
  }
}

class ReadModelUtilImpl(indexUtil: IndexUtil) extends ReadModelUtil {
  def create(inner: MMap): ReadModel =
    new ReadModelImpl(inner)
  def updated(worldKey: AssembledKey, value: Future[Index]): ReadModel⇒ReadModel = {
    case from: ReadModelImpl ⇒ new ReadModelImpl(from.inner + (worldKey → value))
  }
  def isEmpty(implicit executionContext: ExecutionContext): ReadModel⇒Future[Boolean] = {
    case model: ReadModelImpl ⇒
      Future.sequence(model.inner.values).map(_.forall(indexUtil.isEmpty))
  }
  def op(op: (MMap,MMap)⇒MMap): (ReadModel,ReadModel)⇒ReadModel = Function.untupled({
    case (a: ReadModelImpl, b: ReadModelImpl) ⇒ new ReadModelImpl(op(a.inner,b.inner))
  })
  def toMap: ReadModel⇒Map[AssembledKey,Index] = {
    case model: ReadModelImpl ⇒ model.inner.transform((k,f) ⇒ f.value.get.get)
  }
  def ready(implicit executionContext: ExecutionContext): ReadModel⇒Future[ReadModel] = {
    case model: ReadModelImpl ⇒
      for {
        ready ← Future.sequence(model.inner.values)
      } yield model
  }
}

class ReadModelImpl(val inner: DMap[AssembledKey,Future[Index]]) extends ReadModel {
  def getFuture(key: AssembledKey): Future[Index] = inner.getOrElse(key, Future.successful(emptyIndex))
}