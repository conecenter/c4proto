package ee.cone.c4vdom_mix

import ee.cone.c4vdom._
import ee.cone.c4vdom_impl._

trait VDomApp {
  def `the TagStyles` = tagStyles
  def `the Tags` = tags
  def `the ChildPairFactory` = childPairFactory
  def `the TagJsonUtils` = tagJsonUtils
  private lazy val diff = new DiffImpl(MapVDomValueImpl,WasNoValueImpl)
  lazy val childPairFactory: ChildPairFactory = new ChildPairFactoryImpl(MapVDomValueImpl)
  lazy val tagJsonUtils: TagJsonUtils = TagJsonUtilsImpl
  lazy val tags: Tags = new TagsImpl(childPairFactory,tagJsonUtils)
  lazy val tagStyles: TagStyles = new TagStylesImpl
  lazy val vDomHandlerFactory: VDomHandlerFactory =
    new VDomHandlerFactoryImpl(diff,JsonToStringImpl,WasNoValueImpl,childPairFactory)
}
