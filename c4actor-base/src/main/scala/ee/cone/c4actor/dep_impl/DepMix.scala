package ee.cone.c4actor.dep_impl

import ee.cone.c4actor.dep._
import ee.cone.c4actor._
import ee.cone.c4assemble.Assemble
import ee.cone.c4proto.Protocol

import scala.collection.immutable.Seq

trait DepHandlersApp {
  def depHandlers: List[DepHandler] = Nil
}

trait DepAssembleApp extends AssemblesApp {
  def preHashing: PreHashing
  def uuidUtil: UUIDUtil
  def qAdapterRegistry: QAdapterRegistry
  def depRequestHandlers: Seq[DepHandler]
  //
  lazy val depFactory: DepFactory = DepFactoryImpl()
  lazy val depAskFactory: DepAskFactory = DepAskFactoryImpl()
  lazy val depHandlerFactory: DepHandlerFactory = DepHandlerFactoryImpl()
  private lazy val requestHandlerRegistry =
    DepRequestHandlerRegistry(depReqUtil,depRequestHandlers)()
  private lazy val depReqUtil =
    DepReqRespFactoryImpl(uuidUtil)(preHashing,qAdapterRegistry)
  override def assembles: List[Assemble] =
    new DepAssemble(requestHandlerRegistry) :: super.assembles
}

///

trait AskByPKsApp {
  def askByPKs: List[AbstractAskByPK] = Nil
}

trait ByPKRequestHandlerApp extends AssemblesApp with ProtocolsApp {
  def askByPKs: List[AbstractAskByPK]
  def depReqRespFactory: DepReqRespFactory
  def depAskFactory: DepAskFactory

  lazy val askByPKFactory: AskByPKFactory = AskByPKFactoryImpl(depAskFactory,depReqRespFactory)
  override def assembles: List[Assemble] = ByPKAssembles(askByPKs) ::: super.assembles
  override def protocols: List[Protocol] = ByPKRequestProtocol :: super.protocols
}
