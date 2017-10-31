package ee.cone.c4ui

import ee.cone.c4actor._
import ee.cone.c4vdom.{ChildPair, OfDiv}

trait AccessViewRegistry {
  def view[P](access: Access[P]): Context⇒List[ChildPair[OfDiv]]
}

@c4component @listed abstract class tAccessView[P](val valueClass: Class[P]) {
  def view(access: Access[P]): Context⇒List[ChildPair[OfDiv]]
}

trait AccessViewsApp {
  def accessViews: List[AccessView[_]] = Nil
}