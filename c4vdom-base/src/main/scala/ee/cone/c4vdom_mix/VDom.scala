package ee.cone.c4vdom_mix

import ee.cone.c4vdom.{VDomLens, VDomState}
import ee.cone.c4vdom_impl._

trait VDomApp {
  type VDomStateContainer
  def vDomStateKey: VDomLens[VDomStateContainer,Option[VDomState]]
  def relocateKey: VDomLens[VDomStateContainer,String]
  //
  lazy val diff = new DiffImpl(MapVDomValueImpl,WasNoValueImpl)
  lazy val childPairFactory = new ChildPairFactoryImpl(MapVDomValueImpl)
  lazy val currentVDom =
    new CurrentVDomImpl(diff,JsonToStringImpl,WasNoValueImpl,childPairFactory,vDomStateKey,relocateKey)
  lazy val tagJsonUtils = TagJsonUtilsImpl
  lazy val tags = new TagsImpl(childPairFactory,tagJsonUtils)
  lazy val tagStyles = new TagStylesImpl
}
