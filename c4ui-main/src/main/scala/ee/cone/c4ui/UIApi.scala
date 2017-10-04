package ee.cone.c4ui

import ee.cone.c4actor.Context
import ee.cone.c4vdom.Types.ViewRes
import ee.cone.c4vdom.VDomView

//case object BranchOperationsKey extends  SharedComponentKey[BranchOperations]

//case object TagsKey extends SharedComponentKey[Tags]

//case object TagStylesKey extends SharedComponentKey[TagStyles]

trait View extends VDomView[Context] with Product

//case object UntilPolicyKey extends SharedComponentKey[(()⇒ViewRes)⇒ViewRes]

trait UntilPolicy {
  def wrap(view: Context⇒ViewRes): Context⇒ViewRes
}