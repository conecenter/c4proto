package ee.cone.c4vdom_mix

import ee.cone.c4vdom._
import ee.cone.c4vdom_impl._

@deprecated trait VDomApp {
  private lazy val diff = new DiffImpl(MapVDomValueImpl,WasNoValueImpl)
  lazy val childPairFactory: ChildPairFactory = new ChildPairFactoryImpl(new VDomFactoryImpl(MapVDomValueImpl))
  lazy val tagJsonUtils: TagJsonUtils = TagJsonUtilsImpl
  lazy val vDomHandlerFactory: VDomHandlerFactory =
    new VDomHandlerFactoryImpl(diff,JsonToStringImpl,WasNoValueImpl,childPairFactory)
  lazy val vDomResolver: VDomResolver = VDomResolverImpl
}
