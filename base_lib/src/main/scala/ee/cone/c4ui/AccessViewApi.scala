package ee.cone.c4ui

import ee.cone.c4actor.{Access, Context, InjectableGetter}
import ee.cone.c4vdom.{ChildPair, OfDiv}

trait AccessViewRegistry {
  def view[P](access: Access[P]): Context=>List[ChildPair[OfDiv]]
}

abstract class HazyAccessView(val valueClass: Class[_])
abstract class AccessView[P](valueClass: Class[P]) extends HazyAccessView(valueClass) {
  def view(access: Access[P]): Context=>List[ChildPair[OfDiv]]
}
