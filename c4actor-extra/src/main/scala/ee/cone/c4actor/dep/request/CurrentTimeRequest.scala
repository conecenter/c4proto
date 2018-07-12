package ee.cone.c4actor.dep.request

import java.time.Instant

import ee.cone.c4actor.QProtocol.Firstborn
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor._
import ee.cone.c4actor.dep._
import ee.cone.c4actor.dep.request.CurrentTimeProtocol.CurrentTimeNode
import ee.cone.c4actor.dep.request.CurrentTimeRequestProtocol.CurrentTimeRequest
import ee.cone.c4assemble.Types.{Each, Values}
import ee.cone.c4assemble.{All, Assemble, assemble, by}
import ee.cone.c4proto.{Id, Protocol, protocol}

trait CurrentTimeHandlerApp extends AssemblesApp with ProtocolsApp with CurrentTimeConfigApp with DepResponseFactoryApp{


  override def currentTimeConfig: List[CurrentTimeConfig] = CurrentTimeConfig("CurrentTimeRequestAssemble", 10L) :: super.currentTimeConfig

  override def assembles: List[Assemble] = new CurrentTimeRequestAssemble(depResponseFactory) :: super.assembles

  override def protocols: List[Protocol] = QProtocol :: CurrentTimeRequestProtocol :: super.protocols
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

trait CurrentTimeAssembleMix extends CurrentTimeConfigApp with AssemblesApp with ProtocolsApp{

  override def protocols: List[Protocol] = CurrentTimeProtocol :: super.protocols

  override def assembles: List[Assemble] = new CurrentTimeAssemble(currentTimeConfig.distinct) :: super.assembles
}


@assemble class CurrentTimeAssemble(configList: List[CurrentTimeConfig]) extends Assemble {
  type PongSrcId = SrcId

  def FromFirstBornCreateNowTime(
    firstBornId: SrcId,
    firstborn: Each[Firstborn]
  ): Values[(SrcId, TxTransform)] = for {
    config ← configList
  } yield WithPK(CurrentTimeTransform(config.srcId, config.periodSeconds))

  def GetCurrentTimeToAll(
    nowTimeId: SrcId,
    nowTimeNode: Each[CurrentTimeNode]
  ): Values[(All, CurrentTimeNode)] = List(All → nowTimeNode)
}

@assemble class CurrentTimeRequestAssemble(util: DepResponseFactory) extends Assemble {
  def FromAlienPongAndRqToInnerResponse(
    alienId: SrcId,
    @by[All] pong: Each[CurrentTimeNode],
    rq: Each[DepInnerRequest]
  ): Values[(SrcId, DepResponse)] =
    if(pong.srcId == "CurrentTimeRequestAssemble" && rq.request.isInstanceOf[CurrentTimeRequest]) {
      val timeRq = rq.request.asInstanceOf[CurrentTimeRequest]
      val newTime = pong.currentTimeSeconds / timeRq.everyPeriod * timeRq.everyPeriod
      List(WithPK(util.wrap(rq, Option(newTime))))
    } else Nil
}

@protocol object CurrentTimeRequestProtocol extends Protocol {

  @Id(0x0f83) case class CurrentTimeRequest(
    @Id(0x0f86) everyPeriod: Long
  )

}
