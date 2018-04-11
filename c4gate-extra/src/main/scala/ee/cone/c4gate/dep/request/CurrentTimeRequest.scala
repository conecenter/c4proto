package ee.cone.c4gate.dep.request

import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor.dep.{DepGenericUtilityImpl, DepInnerRequest, DepInnerResponse}
import ee.cone.c4actor.{AssemblesApp, ProtocolsApp, WithPK}
import ee.cone.c4assemble.Types.Values
import ee.cone.c4assemble.{Assemble, assemble, by}
import ee.cone.c4gate.AlienProtocol.FromAlienStatus
import ee.cone.c4gate.dep.request.CurrentTimeRequestProtocol.CurrentTimeRequest
import ee.cone.c4proto.{Id, Protocol, protocol}

trait CurrentTimeHandlerApp extends AssemblesApp with ProtocolsApp {


  override def assembles: List[Assemble] = new CurrentTimeAssemble :: super.assembles

  override def protocols: List[Protocol] = CurrentTimeRequestProtocol :: super.protocols
}

@assemble class CurrentTimeAssemble extends Assemble with DepGenericUtilityImpl {
  type PongSrcId = SrcId

  def FromInnerRequestToAlienPongId(
    rqId: SrcId,
    requests: Values[DepInnerRequest]
  ): Values[(PongSrcId, DepInnerRequest)] =
    for {
      rq ← requests
      if rq.request.isInstanceOf[CurrentTimeRequest]
    } yield {
      val timeRq = rq.request.asInstanceOf[CurrentTimeRequest]
      timeRq.sessionId → rq
    }

  def FromAlienPongAndRqToInnerResponse(
    alienId: SrcId,
    pongs: Values[FromAlienStatus],
    @by[PongSrcId] requests: Values[DepInnerRequest]
  ): Values[(SrcId, DepInnerResponse)] =
    for {
      pong ← pongs
      rq ← requests
      if rq.request.isInstanceOf[CurrentTimeRequest]
    } yield {
      val timeRq = rq.request.asInstanceOf[CurrentTimeRequest]
      val newTime = pong.expirationSecond / timeRq.everyPeriod * timeRq.everyPeriod
      WithPK(DepInnerResponse(rq, Option(newTime)))
    }

}

@protocol object CurrentTimeRequestProtocol extends Protocol {

  @Id(0x0f83) case class CurrentTimeRequest(
    @Id(0x0f87) sessionId: String,
    @Id(0x0f86) everyPeriod: Long
  )

}
