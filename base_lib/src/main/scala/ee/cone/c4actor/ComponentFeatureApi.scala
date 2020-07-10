package ee.cone.c4actor

trait DeferredSeq[+T] {
  def value: Seq[T]
}

trait GeneralExclude
trait Exclude[T]
trait Excluding[T] {
  def of[U](item: U): Seq[ProbablyExcluded[U]]
}
trait ProbablyExcluded[T] {
  def value: T
  def keep: Boolean
}

/* usage:
@c4 class WeakFoo(excl: Excluding[Foo], ...) extends Foo {
  @provide def aFoo: Seq[ProbablyExcluded[Foo]] = excl.of(this)
  ...
}
@c4 class StrongFoo(inner: ProbablyExcluded[Foo], ...) extends Foo {...}
@c4 class ExcludeFoo extends Exclude[Foo]
*/