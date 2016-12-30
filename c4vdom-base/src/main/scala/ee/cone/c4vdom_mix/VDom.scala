package ee.cone.c4vdom_mix

import ee.cone.c4vdom.{VDomLens, VDomState, RootView}
import ee.cone.c4vdom_impl._

trait VDomApp {
  type VDomStateContainer
  def vDomStateKey: VDomLens[VDomStateContainer,Option[VDomState]]
  def rootView: RootView[VDomStateContainer]
  //
  lazy val diff = new DiffImpl(MapVDomValueImpl,WasNoValueImpl)
  lazy val childPairFactory = new ChildPairFactoryImpl(MapVDomValueImpl)
  lazy val currentVDom =
    new CurrentVDomImpl(diff,JsonToStringImpl,WasNoValueImpl,childPairFactory,rootView,vDomStateKey)
  lazy val tagJsonUtils = TagJsonUtilsImpl
  lazy val tags = new TagsImpl(childPairFactory,tagJsonUtils)
  lazy val tagStyles = new TagStylesImpl
}
