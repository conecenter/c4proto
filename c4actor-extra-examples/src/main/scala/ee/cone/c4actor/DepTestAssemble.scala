package ee.cone.c4actor

import ee.cone.c4actor.DepTestProtocol.{DepTestRequest, Spark}
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor.dep.DepTypes.ContextId
import ee.cone.c4actor.dep._
import ee.cone.c4assemble.Types.Values
import ee.cone.c4assemble.{All, Assemble, assemble, by}
import ee.cone.c4proto.{Id, Protocol, protocol}

trait DepTestAssemble extends AssemblesApp with RequestHandlersApp with ProtocolsApp{
  def testDep: Dep[_]

  def testContextId: String = ""

  def qAdapterRegistry: QAdapterRegistry


  override def protocols: List[Protocol] = DepTestProtocol :: super.protocols

  override def handlers: List[RequestHandler[_]] = DepTestHandler(testDep, testContextId) :: super.handlers

  override def assembles: List[Assemble] = new DepTestAssembles(qAdapterRegistry) :: super.assembles
}

@protocol object DepTestProtocol extends Protocol {

  @Id(0x0455) case class DepTestRequest()

  @Id(0x0567) case class Spark(
    @Id(0x1337) srcId: String
  )

}

case class DepTestHandler(dep: Dep[_], contextId: String) extends RequestHandler[DepTestRequest] {
  def canHandle: Class[DepTestRequest] = classOf[DepTestRequest]

  def handle: DepTestRequest => (Dep[_], ContextId) = _ ⇒ (dep, contextId)
}

case class DepTestResponse(srcId: String, response: Option[_])

@assemble class DepTestAssembles(val qAdapterRegistry: QAdapterRegistry) extends Assemble with DepAssembleUtilityImpl {
  def GiveBirth(
    firstBornId: SrcId,
    sparks: Values[Spark]
  ): Values[(SrcId, DepOuterRequest)] =
    for {
      spark ← sparks
    } yield {
      WithPK(generateDepOuterRequest(DepTestRequest(), "test"))
    }

  def HarvestBirth(
    responseId: SrcId,
    responses: Values[DepOuterResponse]
  ): Values[(SrcId, DepTestResponse)] =
    for {
      resp ← responses
      if resp.request.innerRequest.request.isInstanceOf[DepTestRequest]
    } yield {
      WithPK(DepTestResponse(resp.request.srcId, resp.value))
    }
}
