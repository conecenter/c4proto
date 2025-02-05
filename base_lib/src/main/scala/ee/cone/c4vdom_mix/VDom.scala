package ee.cone.c4vdom_mix

import ee.cone.c4vdom._
import ee.cone.c4vdom_impl._

@deprecated trait VDomApp {
  lazy val childPairFactory: ChildPairFactory = new ChildPairFactoryImpl(new VDomFactoryImpl(MapVDomValueImpl))
  lazy val tagJsonUtils: TagJsonUtils = TagJsonUtilsImpl
  lazy val vDomResolver: VDomResolver = VDomResolverImpl
}
