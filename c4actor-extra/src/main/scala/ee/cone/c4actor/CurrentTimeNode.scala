package ee.cone.c4actor

import java.time.Instant

import ee.cone.c4actor.CurrentTimeProtocol.CurrentTimeNode
import ee.cone.c4actor.QProtocol.Firstborn
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor.dep.DepGenericUtilityImpl
import ee.cone.c4assemble.Types.Values
import ee.cone.c4assemble.{All, Assemble, assemble}
import ee.cone.c4proto.{Id, Protocol, protocol}

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
  override def assembles: List[Assemble] = new CurrentTimeAssemble(currentTimeConfig.distinct) :: super.assembles

  override def protocols: List[Protocol] = QProtocol :: CurrentTimeProtocol :: super.protocols
}


@assemble class CurrentTimeAssemble(configList: List[CurrentTimeConfig]) extends Assemble with DepGenericUtilityImpl {
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
