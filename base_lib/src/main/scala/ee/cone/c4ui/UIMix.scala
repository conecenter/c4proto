package ee.cone.c4ui

import ee.cone.c4actor_branch.BranchApp
import ee.cone.c4gate.{AlienProtocolApp, HttpProtocolApp}
import ee.cone.c4di.{c4, provide}
import ee.cone.c4vdom.{ChildPairFactory, TagJsonUtils, VDomFactory, VDomHandlerFactory, VDomResolver}
import ee.cone.c4vdom_impl._

trait AccessViewAppBase

trait PublicViewAssembleAppBase

trait UICompAppBase extends AlienExchangeCompApp with BranchApp

trait AlienExchangeCompAppBase extends AlienProtocolApp with HttpProtocolApp

@c4("UICompApp") final class VDomProvider {
  private lazy val diff = new DiffImpl(MapVDomValueImpl,WasNoValueImpl)
  private lazy val vDomFactory = new VDomFactoryImpl(MapVDomValueImpl)
  private lazy val childPairFactory = new ChildPairFactoryImpl(vDomFactory)
  @provide def vDomFactoryPr: Seq[VDomFactory] = List(vDomFactory)
  @provide def childPairFactoryPr: Seq[ChildPairFactory] = List(childPairFactory)
  @provide def tagJsonUtilsPr: Seq[TagJsonUtils] = List(TagJsonUtilsImpl)
  @provide def vDomHandlerFactoryPr: Seq[VDomHandlerFactory] =
    List(new VDomHandlerFactoryImpl(diff,FixDuplicateKeysImpl,JsonToStringImpl,WasNoValueImpl,childPairFactory))
  @provide def vDomResolverPr: Seq[VDomResolver] = List(VDomResolverImpl)
}
