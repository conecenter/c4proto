package ee.cone.c4ui

import ee.cone.c4actor.{Context, TransientLens}
import ee.cone.c4vdom.Types.ViewRes
import ee.cone.c4vdom.{VDomLens, VDomState, VDomView}

trait View extends VDomView[Context] with Product

trait UntilPolicy {
  def wrap(view: Context⇒ViewRes): Context⇒ViewRes
}

case object VDomStateKey extends TransientLens[Option[VDomState]](None)
  with VDomLens[Context, Option[VDomState]]
