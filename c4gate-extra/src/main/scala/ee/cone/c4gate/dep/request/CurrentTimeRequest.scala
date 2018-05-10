package ee.cone.c4gate.dep.request

import java.time.Instant

import ee.cone.c4actor.CurrentTimeProtocol.CurrentTimeNode
import ee.cone.c4actor.QProtocol.Firstborn
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor._
import ee.cone.c4actor.dep.{DepGenericUtilityImpl, DepInnerRequest, DepInnerResponse}
import ee.cone.c4assemble.Types.Values
import ee.cone.c4assemble.{All, Assemble, assemble, by}
import ee.cone.c4gate.dep.request.CurrentTimeRequestProtocol.CurrentTimeRequest
import ee.cone.c4proto.{Id, Protocol, protocol}

trait CurrentTimeHandlerApp extends AssemblesApp with ProtocolsApp with CurrentTimeConfigApp{


  override def currentTimeConfig: List[CurrentTimeConfig] = CurrentTimeConfig("CurrentTimeRequestAssemble", 10L) :: super.currentTimeConfig

  override def assembles: List[Assemble] = new CurrentTimeRequestAssemble :: super.assembles

  override def protocols: List[Protocol] = QProtocol :: CurrentTimeRequestProtocol :: super.protocols
}

@assemble class CurrentTimeRequestAssemble extends Assemble {
  type CurrentTimeRequestAll = All

  def TimeNodeToCurrentTimeRequestAll(
    firstBornId: SrcId,
    firstBorns: Values[Firstborn],
    @by[All] pongs: Values[CurrentTimeNode]
  ): Values[(CurrentTimeRequestAll, CurrentTimeNode)] =
    for {
      pong ← pongs
      if pong.srcId == "CurrentTimeRequestAssemble"
    } yield All → pong

  def FromAlienPongAndRqToInnerResponse(
    alienId: SrcId,
    @by[CurrentTimeRequestAll] pongs: Values[CurrentTimeNode],
    requests: Values[DepInnerRequest]
  ): Values[(SrcId, DepInnerResponse)] =
    for {
      pong ← pongs
      rq ← requests
      if rq.request.isInstanceOf[CurrentTimeRequest]
    } yield {
      val timeRq = rq.request.asInstanceOf[CurrentTimeRequest]
      val newTime = pong.currentTimeSeconds / timeRq.everyPeriod * timeRq.everyPeriod
      WithPK(DepInnerResponse(rq, Option(newTime)))
    }
}

@protocol object CurrentTimeRequestProtocol extends Protocol {

  @Id(0x0f83) case class CurrentTimeRequest(
    @Id(0x0f86) everyPeriod: Long
  )

}
