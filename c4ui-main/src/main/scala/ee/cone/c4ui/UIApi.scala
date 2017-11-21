package ee.cone.c4ui

import ee.cone.c4actor.Context
import ee.cone.c4vdom.Types.ViewRes
import ee.cone.c4vdom.VDomView

trait View extends VDomView[Context] with Product

trait UntilPolicy {
  def wrap(view: Context⇒ViewRes): Context⇒ViewRes
}