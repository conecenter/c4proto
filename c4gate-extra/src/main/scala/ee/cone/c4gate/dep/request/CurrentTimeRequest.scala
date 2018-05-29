package ee.cone.c4gate.dep.request

import java.time.Instant

import ee.cone.c4actor.QProtocol.Firstborn
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor._
import ee.cone.c4actor.dep._
import ee.cone.c4assemble.Types.Values
import ee.cone.c4assemble.{All, Assemble, assemble, by}
import ee.cone.c4gate.dep.request.CurrentTimeProtocol.CurrentTimeNode
import ee.cone.c4gate.dep.request.CurrentTimeRequestProtocol.CurrentTimeRequest
import ee.cone.c4proto.{Id, Protocol, protocol}

trait CurrentTimeHandlerApp extends AssemblesApp with ProtocolsApp with CurrentTimeConfigApp with DepResponseFactoryApp{


  override def currentTimeConfig: List[CurrentTimeConfig] = CurrentTimeConfig("CurrentTimeRequestAssemble", 10L) :: super.currentTimeConfig

  override def assembles: List[Assemble] = new CurrentTimeRequestAssemble(depResponseFactory) :: super.assembles

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

case class CurrentTimeConfig(srcId: SrcId, periodSeconds: Long)

trait CurrentTimeConfigApp {
  def currentTimeConfig: List[CurrentTimeConfig] = Nil
}

trait CurrentTimeAssembleMix extends CurrentTimeConfigApp with AssemblesApp{
  override def assembles: List[Assemble] = new CurrentTimeAssemble(currentTimeConfig.distinct) :: super.assembles
}


@assemble class CurrentTimeAssemble(configList: List[CurrentTimeConfig]) extends Assemble {
  type PongSrcId = SrcId

  def FromFirstBornCreateNowTime(
    firstBornId: SrcId,
    firstborns: Values[Firstborn]
  ): Values[(SrcId, TxTransform)] =
    for {
      _ ← firstborns
      config ← configList
    } yield WithPK(CurrentTimeTransform(config.srcId, config.periodSeconds))

  def GetCurrentTimeToAll(
    nowTimeId: SrcId,
    nowTimes: Values[CurrentTimeNode]
  ): Values[(All, CurrentTimeNode)] =
    for {
      nowTimeNode ← nowTimes
    } yield All → nowTimeNode
}

@assemble class CurrentTimeRequestAssemble(util: DepResponseFactory) extends Assemble {
  def FromAlienPongAndRqToInnerResponse(
    alienId: SrcId,
    @by[All] pongs: Values[CurrentTimeNode],
    requests: Values[DepInnerRequest]
  ): Values[(SrcId, DepResponse)] =
    for {
      pong ← pongs.filter(_.srcId == "CurrentTimeRequestAssemble")
      rq ← requests
      if rq.request.isInstanceOf[CurrentTimeRequest]
    } yield {
      val timeRq = rq.request.asInstanceOf[CurrentTimeRequest]
      val newTime = pong.currentTimeSeconds / timeRq.everyPeriod * timeRq.everyPeriod
      WithPK(util.wrap(rq, Option(newTime)))
    }
}

@protocol object CurrentTimeRequestProtocol extends Protocol {

  @Id(0x0f83) case class CurrentTimeRequest(
    @Id(0x0f86) everyPeriod: Long
  )

}
