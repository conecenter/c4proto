package ee.cone.c4ui

import ee.cone.c4actor_branch.BranchApp
import ee.cone.c4gate.{AlienProtocolApp, HttpProtocolApp}
import ee.cone.c4di.{c4, provide}
import ee.cone.c4vdom.{ChildPairFactory, TagJsonUtils, TagStyles, Tags, VDomHandlerFactory, VDomResolver}
import ee.cone.c4vdom_impl.{ChildPairFactoryImpl, DiffImpl, JsonToStringImpl, MapVDomValueImpl, TagJsonUtilsImpl, TagStylesImpl, TagsImpl, VDomHandlerFactoryImpl, VDomResolverImpl, WasNoValueImpl}

trait AccessViewAppBase

trait PublicViewAssembleAppBase

trait UICompAppBase extends AlienExchangeApp with BranchApp

trait AlienExchangeAppBase extends AlienProtocolApp with HttpProtocolApp

@c4("UICompApp") class VDomProvider {
  private lazy val diff = new DiffImpl(MapVDomValueImpl,WasNoValueImpl)
  private lazy val childPairFactory = new ChildPairFactoryImpl(MapVDomValueImpl)
  @provide def childPairFactoryPr: Seq[ChildPairFactory] = List(childPairFactory)
  @provide def tagJsonUtilsPr: Seq[TagJsonUtils] = List(TagJsonUtilsImpl)
  @provide def tagsPr: Seq[Tags] = List(new TagsImpl(childPairFactory,TagJsonUtilsImpl))
  @provide def tagStylesPr: Seq[TagStyles] = List(new TagStylesImpl)
  @provide def vDomHandlerFactoryPr: Seq[VDomHandlerFactory] =
    List(new VDomHandlerFactoryImpl(diff,JsonToStringImpl,WasNoValueImpl,childPairFactory))
  @provide def vDomResolverPr: Seq[VDomResolver] = List(VDomResolverImpl)
}
