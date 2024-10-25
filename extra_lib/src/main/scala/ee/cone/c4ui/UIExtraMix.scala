package ee.cone.c4ui

import ee.cone.c4actor._
import ee.cone.c4actor_branch.BranchOperations
import ee.cone.c4di.c4
import ee.cone.c4vdom.{ChildPairFactory, TagJsonUtils, VDomResolver}

import scala.collection.immutable.Seq

trait VDomApp extends ComponentProviderApp {
  lazy val childPairFactory: ChildPairFactory = resolveSingle(classOf[ChildPairFactory])
  lazy val tagJsonUtils: TagJsonUtils = resolveSingle(classOf[TagJsonUtils])
  lazy val vDomResolver: VDomResolver = resolveSingle(classOf[VDomResolver])
}

trait ExtraUIApp extends UICompApp with VDomApp with AlienExchangeApp {
  lazy val branchOperations: BranchOperations = resolveSingle(classOf[BranchOperations])
  lazy val untilPolicy: UntilPolicy = resolveSingle(classOf[UntilPolicy])
}
