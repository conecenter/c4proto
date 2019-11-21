package ee.cone.c4ui

import ee.cone.c4actor._
import ee.cone.c4actor_branch.BranchOperations
import ee.cone.c4vdom.{ChildPairFactory, TagJsonUtils, TagStyles, Tags, VDomResolver}

trait VDomApp extends ComponentProviderApp {
  lazy val childPairFactory: ChildPairFactory = resolveSingle(classOf[ChildPairFactory])
  lazy val tagJsonUtils: TagJsonUtils = resolveSingle(classOf[TagJsonUtils])
  lazy val tags: Tags = resolveSingle(classOf[Tags])
  lazy val tagStyles: TagStyles = resolveSingle(classOf[TagStyles])
  lazy val vDomResolver: VDomResolver = resolveSingle(classOf[VDomResolver])
}

trait UIApp extends UICompApp with VDomApp {
  lazy val branchOperations: BranchOperations = resolveSingle(classOf[BranchOperations])
  lazy val untilPolicy: UntilPolicy = resolveSingle(classOf[UntilPolicy])
}