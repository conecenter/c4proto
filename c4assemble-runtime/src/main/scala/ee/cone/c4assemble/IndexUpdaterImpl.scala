package ee.cone.c4assemble

import ee.cone.c4assemble.Types.{DMap, Index, emptyIndex}

import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global

class IndexUpdaterImpl(readModelUtil: ReadModelUtil) extends IndexUpdater {
  def setPart[K,V](worldKey: AssembledKey)(
    update: Future[IndexUpdate]
  ): WorldTransition⇒WorldTransition = transition ⇒ {
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
  def isEmpty: ReadModel⇒Future[Boolean] = {
    case model: ReadModelImpl ⇒
      Future.sequence(model.inner.values).map(_.forall(indexUtil.isEmpty))
  }
  def op(op: (MMap,MMap)⇒MMap): (ReadModel,ReadModel)⇒ReadModel = Function.untupled({
    case (a: ReadModelImpl, b: ReadModelImpl) ⇒ new ReadModelImpl(op(a.inner,b.inner))
  })
  def toMap: ReadModel⇒Future[Map[AssembledKey,Index]] = {
    case model: ReadModelImpl ⇒
      Future.sequence(model.inner.map{ case (k,f) ⇒ f.map(v⇒k->v) }).map(_.toMap)
  }

}

class ReadModelImpl(val inner: DMap[AssembledKey,Future[Index]]) extends ReadModel {
  def apply(key: AssembledKey): Future[Index] = inner.getOrElse(key, Future.successful(emptyIndex))
}