package ee.cone.c4actor

import ee.cone.c4assemble.Single
import ee.cone.c4di.{c4, provide}

import scala.concurrent.Promise

@c4("BaseApp") final class OptionProvider {
  @provide def getOption[T](items: List[T]): Seq[Option[T]] =
    Seq(Single.option(items))
}

@c4("BaseApp") final class DeferredSeqProvider {
  def ignorePromise[T](value: Promise[T]): Unit = ()
  abstract class SimpleDeferredSeq[T] extends DeferredSeq[T] {
    def set(value: Seq[T]): Unit
  }
  @provide def getDeferredSeq[T](key: StrictTypeKey[T]): Seq[DeferredSeq[T]] = {
    val promise: Promise[Seq[T]] = Promise()
    val future = promise.future
    Seq(new SimpleDeferredSeq[T]{
      def fail(): Nothing = throw new Exception(s"can not access DeferredSeq at this stage: $key")
      def value: Seq[T] = future.value.getOrElse(fail()).getOrElse(fail())
      def set(value: Seq[T]): Unit = ignorePromise(promise.success(value))
    })
  }
  @provide def getDeferredSeqSetup[T](seq: DeferredSeq[T], list: List[T]): Seq[DeferredSetup] =
    Seq(new DeferredSetup(()=>seq.asInstanceOf[SimpleDeferredSeq[T]].set(list)))
}

@c4("BaseApp") final class ExclusionProvider {
  class ExcludingImpl[T](keep: Boolean) extends Excluding[T] {
    def of[U](item: U): Seq[ProbablyExcluded[U]] = Seq(new ProbablyExcludedImpl(item,keep))
  }
  class ProbablyExcludedImpl[U](val value: U, val keep: Boolean) extends ProbablyExcluded[U]
  @provide def excluding[T](excludes: List[Exclude[T]]): Seq[Excluding[T]] =
    Seq(new ExcludingImpl(excludes.isEmpty))
  @provide def notExcluded[U<:Object](item: ProbablyExcluded[U]): Seq[U] =
    if(item.keep) Seq(item.value) else Nil
}




