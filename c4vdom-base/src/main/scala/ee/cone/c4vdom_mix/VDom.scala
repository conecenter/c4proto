package ee.cone.c4vdom_mix

import ee.cone.c4vdom_impl._

trait VDomApp {
  lazy val diff = new DiffImpl(MapVDomValueImpl,WasNoValueImpl)
  lazy val childPairFactory = new ChildPairFactoryImpl(MapVDomValueImpl)
  lazy val tagJsonUtils = TagJsonUtilsImpl
  lazy val tags = new TagsImpl(childPairFactory,tagJsonUtils)
  lazy val tagStyles = new TagStylesImpl
}
