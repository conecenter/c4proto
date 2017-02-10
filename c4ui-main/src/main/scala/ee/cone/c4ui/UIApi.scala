package ee.cone.c4ui

import ee.cone.c4actor.BranchOperations
import ee.cone.c4assemble.Types.World
import ee.cone.c4assemble.WorldKey
import ee.cone.c4vdom.Types.ViewRes
import ee.cone.c4vdom.{TagStyles, Tags, VDomView}

case object BranchOperationsKey extends  WorldKey[Option[BranchOperations]](None)

case object TagsKey extends WorldKey[Option[Tags]](None)

case object TagStylesKey extends WorldKey[Option[TagStyles]](None)

trait View extends VDomView[World]

case object UntilPolicyKey extends WorldKey[(()⇒ViewRes)⇒ViewRes](_⇒throw new Exception)