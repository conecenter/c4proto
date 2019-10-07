package ee.cone.c4ui

import ee.cone.c4actor.ComponentRegistry
import ee.cone.c4vdom.{ChildPairFactory, TagJsonUtils, TagStyles, Tags, VDomResolver}

trait VDomApp {
  def componentRegistry: ComponentRegistry
  //
  lazy val childPairFactory: ChildPairFactory = componentRegistry.resolveSingle(classOf[ChildPairFactory])
  lazy val tagJsonUtils: TagJsonUtils = componentRegistry.resolveSingle(classOf[TagJsonUtils])
  lazy val tags: Tags = componentRegistry.resolveSingle(classOf[Tags])
  lazy val tagStyles: TagStyles = componentRegistry.resolveSingle(classOf[TagStyles])
  lazy val vDomResolver: VDomResolver = componentRegistry.resolveSingle(classOf[VDomResolver])
}
