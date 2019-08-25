package ee.cone.c4actor.dep.request

import java.security.SecureRandom
import java.time.Instant

import ee.cone.c4actor.QProtocol.S_Firstborn
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor._
import ee.cone.c4actor.dep._
import ee.cone.c4actor.dep.request.CurrentTimeProtocol.S_CurrentTimeNode
import ee.cone.c4actor.dep.request.CurrentTimeProtocolBase.S_CurrentTimeNodeSetting
import ee.cone.c4actor.dep.request.CurrentTimeRequestProtocol.N_CurrentTimeRequest
import ee.cone.c4actor.dep.request.CurrentTimeRequestProtocolBase.D_CurrentTimeMetaAttr
import ee.cone.c4assemble.Types.{Each, Values}
import ee.cone.c4assemble.{Assemble, assemble, by}
import ee.cone.c4proto._

trait CurrentTimeHandlerApp extends AssemblesApp with ProtocolsApp with CurrentTimeConfigApp with DepResponseFactoryApp {


  override def currentTimeConfig: List[CurrentTimeConfig] = CurrentTimeConfig(CurrentTimeRequestAssembleTimeId.id, 10L) :: super.currentTimeConfig

  override def assembles: List[Assemble] = new CurrentTimeRequestAssemble(depResponseFactory) :: super.assembles

  override def protocols: List[Protocol] = QProtocol :: CurrentTimeRequestProtocol :: super.protocols
}

case class CurrentTimeTransform(srcId: SrcId, refreshRateSeconds: Long) extends TxTransform {
  private val refreshMilli = refreshRateSeconds * 1000L
  private val random: SecureRandom = new SecureRandom()

  private val getOffset: Long = refreshMilli + random.nextInt(500)

  def transform(l: Context): Context = {
    val newLocal = InsertOrigMeta(D_CurrentTimeMetaAttr(srcId, refreshRateSeconds) :: Nil)(l)
    val now = Instant.now
    val nowMilli = now.toEpochMilli
    val prev = ByPK(classOf[S_CurrentTimeNode]).of(newLocal).get(srcId)
    prev match {
      case Some(currentTimeNode) if currentTimeNode.currentTimeMilli + getOffset < nowMilli ⇒
        val nowSeconds = now.getEpochSecond
        TxAdd(LEvent.update(currentTimeNode.copy(currentTimeSeconds = nowSeconds, currentTimeMilli = nowMilli)))(newLocal)
      case None ⇒
        val nowSeconds = now.getEpochSecond
        TxAdd(LEvent.update(S_CurrentTimeNode(srcId, nowSeconds, nowMilli)))(newLocal)
      case _ ⇒ l
    }
  }
}

@protocol object CurrentTimeProtocolBase {

  @Id(0x0127) case class S_CurrentTimeNodeSetting(
    @Id(0x0128) timeNodeId: String,
    @Id(0x0129) refreshSeconds: Long
  )

  @Id(0x0123) case class S_CurrentTimeNode(
    @Id(0x0124) srcId: String,
    @Id(0x0125) currentTimeSeconds: Long,
    @Id(0x0126) currentTimeMilli: Long
  )

}

case class CurrentTimeConfig(srcId: SrcId, periodSeconds: Long)

trait CurrentTimeConfigApp {
  def currentTimeConfig: List[CurrentTimeConfig] = Nil
}

trait CurrentTimeAssembleMix extends CurrentTimeConfigApp with AssemblesApp with ProtocolsApp {

  override def protocols: List[Protocol] = CurrentTimeProtocol :: super.protocols

  override def assembles: List[Assemble] = {
    val grouped = currentTimeConfig.distinct.groupBy(_.srcId).filter(kv ⇒ kv._2.size > 1)
    assert(grouped.isEmpty, s"Duplicate keys ${grouped.keySet}")
    new CurrentTimeAssemble(currentTimeConfig.distinct) :: super.assembles
  }
}

object CurrentTimeRequestAssembleTimeId {
  val id: String = "CurrentTimeRequestAssemble"
}


@assemble class CurrentTimeAssembleBase(configList: List[CurrentTimeConfig]) {
  type CurrentTimeId = SrcId

  def CreateTimeConfig(
    firstBornId: SrcId,
    firstborn: Each[S_Firstborn]
  ): Values[(CurrentTimeId, CurrentTimeConfig)] =
    configList.map(WithPK(_))

  def FromSettingsCreateNowTime(
    firstBornId: SrcId,
    @by[CurrentTimeId] config: Each[CurrentTimeConfig],
    settings: Values[S_CurrentTimeNodeSetting]
  ): Values[(SrcId, TxTransform)] =
    List(WithPK(CurrentTimeTransform(config.srcId, settings.headOption.map(_.refreshSeconds).getOrElse(config.periodSeconds))))
}

@assemble class CurrentTimeRequestAssembleBase(util: DepResponseFactory) {
  type CTRATimeId = SrcId

  def FilterTimeForCurrentTimeRequestAssemble(
    timeId: SrcId,
    time: Each[S_CurrentTimeNode]
  ): Values[(CTRATimeId, S_CurrentTimeNode)] =
    if (time.srcId == CurrentTimeRequestAssembleTimeId.id)
      WithPK(time) :: Nil
    else
      Nil

  def FilterTimeRequests(
    requestId: SrcId,
    rq: Each[DepInnerRequest]
  ): Values[(CTRATimeId, DepInnerRequest)] =
    rq.request match {
      case _: N_CurrentTimeRequest ⇒ (CurrentTimeRequestAssembleTimeId.id → rq) :: Nil
      case _ ⇒ Nil
    }

  def TimeToDepResponse(
    alienId: SrcId,
    @by[CTRATimeId] pong: Each[S_CurrentTimeNode],
    @by[CTRATimeId] rq: Each[DepInnerRequest]
  ): Values[(SrcId, DepResponse)] = {
    val timeRq = rq.request.asInstanceOf[N_CurrentTimeRequest]
    val newTime = pong.currentTimeSeconds / timeRq.everyPeriod * timeRq.everyPeriod
    WithPK(util.wrap(rq, Option(newTime))) :: Nil
  }
}

@protocol object CurrentTimeRequestProtocolBase {

    @Id(0x0f83) case class N_CurrentTimeRequest(
    @Id(0x0f86) everyPeriod: Long
  )

    @Id(0x0f87) case class D_CurrentTimeMetaAttr(
    @Id(0x0f88) srcId: String,
    @Id(0x0f89) everyPeriod: Long
  )

}
