package ee.cone.c4gate.dep.request

import java.time.Instant

import ee.cone.c4actor.QProtocol.Firstborn
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor._
import ee.cone.c4actor.dep.{DepGenericUtilityImpl, DepInnerRequest, DepInnerResponse}
import ee.cone.c4assemble.Types.Values
import ee.cone.c4assemble.{All, Assemble, assemble, by}
import ee.cone.c4gate.dep.request.CurrentTimeProtocol.CurrentTimeNode
import ee.cone.c4gate.dep.request.CurrentTimeRequestProtocol.CurrentTimeRequest
import ee.cone.c4proto.{Id, Protocol, protocol}

trait CurrentTimeHandlerApp extends AssemblesApp with ProtocolsApp {

  override def assembles: List[Assemble] = new CurrentTimeAssemble :: super.assembles

  override def protocols: List[Protocol] = QProtocol :: CurrentTimeRequestProtocol :: CurrentTimeProtocol :: super.protocols
}

case class CurrentTimeTransform(srcId: SrcId, refreshRateSeconds: Long) extends TxTransform {
  def transform(local: Context): Context = {
    val now = Instant.now
    val nowTimeSecondsTruncated = now.getEpochSecond / refreshRateSeconds * refreshRateSeconds
    val currentTimeNode = CurrentTimeNode(srcId, nowTimeSecondsTruncated)
    val prev = ByPK(classOf[CurrentTimeNode]).of(local).get(srcId)
    if (prev.isEmpty || prev.get != currentTimeNode) {
      TxAdd(LEvent.update(currentTimeNode))(local)
    } else {
      SleepUntilKey.set(now.plusSeconds(refreshRateSeconds))(local)
    }
  }
}

@protocol object CurrentTimeProtocol extends Protocol {
  @Id(0x0123) case class CurrentTimeNode(
    @Id(0x0124) srcId: String,
    @Id(0x0125) currentTimeSeconds: Long
  )
}

@assemble class CurrentTimeAssemble extends Assemble with DepGenericUtilityImpl {
  type PongSrcId = SrcId

  def FromFirstBornCreateNowTime(
    firstBornId: SrcId,
    firstborns: Values[Firstborn]
  ): Values[(SrcId, TxTransform)] =
    for {
      _ ← firstborns
    } yield WithPK(CurrentTimeTransform("CurrentTimeAssemble", 10L))

  def GetCurrentTimeToAll(
    nowTimeId: SrcId,
    nowTimes: Values[CurrentTimeNode]
  ): Values[(All, CurrentTimeNode)] =
    for {
      nowTimeNode ← nowTimes
    } yield All → nowTimeNode

  def FromAlienPongAndRqToInnerResponse(
    alienId: SrcId,
    @by[All] pongs: Values[CurrentTimeNode],
    requests: Values[DepInnerRequest]
  ): Values[(SrcId, DepInnerResponse)] =
    for {
      pong ← pongs.filter(_.srcId == "CurrentTimeAssemble")
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
