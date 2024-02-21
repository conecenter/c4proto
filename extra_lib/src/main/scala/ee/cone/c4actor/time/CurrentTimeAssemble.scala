package ee.cone.c4actor.time

import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor.QProtocol.S_Firstborn
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor._
import ee.cone.c4actor.time.ProtoCurrentTimeConfig.{S_CurrentTimeGlobalOffset, S_CurrentTimeNodeSetting, T_Time}
import ee.cone.c4assemble.Types.{Each, Values}
import ee.cone.c4assemble.{AbstractAll, All, byEq, c4assemble}
import ee.cone.c4di.{c4, c4multi}
import ee.cone.c4proto.{Id, protocol}

import java.security.SecureRandom

trait GeneralCurrentTimeAppBase

trait WithCurrentTime {
  def currentTime: CurrentTime
}

trait CurrTimeConfig extends WithCurrentTime with LazyLogging {
  def set: Long => T_Time => T_Time = time => _.copy(millis = time)
  def timeGetter: GetByPK[T_Time]

  def process(refreshRate: Option[Long], globalOffset: Long, offset: Long): Context => Seq[LEvent[Product]] = {
    val refreshTolerance = refreshRate.getOrElse(currentTime.refreshRateSeconds) * 1000L + offset
    local => {
      val now = System.currentTimeMillis() + globalOffset
      val model: Option[T_Time] = timeGetter.ofA(local).get(currentTime.srcId)
      model match {
        case Some(time) if Math.abs(now - time.millis) > refreshTolerance =>
          logger.debug(s"Updating ${currentTime.srcId} with ${offset}")
          LEvent.update(set(now)(time))
        case None =>
          LEvent.update(set(now)(T_Time(currentTime.srcId, 0)))
        case _ => Nil
      }
    }
  }
}

@c4("GeneralCurrentTimeApp") final class TimeGettersImpl(timeGetters: List[TimeGetter]) extends TimeGetters {
  def all: List[TimeGetter] = timeGetters
  lazy val gettersMap: Map[SrcId, TimeGetter] = timeGetters.map(getter => getter.currentTime.srcId -> getter).toMap
  def apply(currentTime: CurrentTime): TimeGetter =
    gettersMap(currentTime.srcId)
}

@c4("GeneralCurrentTimeApp") final class TimeNodePartition(configs: List[CurrTimeConfig]) extends OrigPartitioner(classOf[T_Time]) {
  private val unknownSrcId: String = "unknown"
  lazy val allSrcIds: Set[String] = configs.map(_.currentTime.srcId).toSet + unknownSrcId
  def handle(value: T_Time): String = if (allSrcIds(value.srcId)) value.srcId else unknownSrcId
  lazy val partitions: Set[String] = allSrcIds
}

@protocol("GeneralCurrentTimeApp") object ProtoCurrentTimeConfig {

  @Id(0x012a) case class S_CurrentTimeGlobalOffset(
    @Id(0x012b) srcId: SrcId,
    @Id(0x012c) offset: Long,
  )

  @Id(0x0127) case class S_CurrentTimeNodeSetting(
    @Id(0x0128) timeNodeId: String,
    @Id(0x0129) refreshSeconds: Long
  )

  @Id(0x012d) case class T_Time(
    @Id(0x012f) srcId: SrcId,
    @Id(0x0130) millis: Long,
  )

}

@c4assemble("GeneralCurrentTimeApp") class CurrentTimeGeneralAssembleBase(
  generalCurrentTimeTransformFactory: GeneralCurrentTimeTransformFactory,
) {
  type CurrentTimeGeneralAll = AbstractAll

  def timeToAll(
    timeId: SrcId,
    setting: Each[S_CurrentTimeNodeSetting]
  ): Values[(CurrentTimeGeneralAll, S_CurrentTimeNodeSetting)] =
    WithAll(setting) :: Nil

  def CreateGeneralTimeConfig(
    firstBornId: SrcId,
    firstborn: Each[S_Firstborn],
    globalOffset: Each[S_CurrentTimeGlobalOffset],
    @byEq[CurrentTimeGeneralAll](All) timeSetting: Values[S_CurrentTimeNodeSetting],
    //@time(TestDepTime) time: Each[Time] // byEq[SrcId](TestTime.srcId) time: Each[Time]
  ): Values[(SrcId, TxTransform)] =
    WithPK(generalCurrentTimeTransformFactory.create(s"${firstborn.srcId}-general-time", globalOffset, timeSetting.toList)) :: Nil
}

@c4multi("GeneralCurrentTimeApp") final case class GeneralCurrentTimeTransform(
  srcId: SrcId, globalOffset: S_CurrentTimeGlobalOffset, configs: List[S_CurrentTimeNodeSetting]
)(
  generalCurrTimeConfigs: List[CurrTimeConfig],
  txAdd: LTxAdd,
) extends TxTransform {
  lazy val currentTimes: List[CurrTimeConfig] =
    CheckedMap(generalCurrTimeConfigs.map(c => c.currentTime.srcId -> c)).values.toList
  private val random: SecureRandom = new SecureRandom()

  lazy val offset: Long = globalOffset.offset

  lazy val configsMap: Map[String, Long] =
    configs.map(conf => conf.timeNodeId -> conf.refreshSeconds).toMap
  lazy val actions: List[Context => Seq[LEvent[Product]]] =
    currentTimes.map(currentTime =>
      currentTime.process(configsMap.get(currentTime.currentTime.srcId), offset, random.nextInt(500))
    )

  def transform(local: Context): Context = {
    actions.flatMap(_.apply(local)) match { // TODO may be throttle time updates and do them one by one
      case updates if updates.isEmpty => local
      case updates => txAdd.add(updates)(local)
    }
  }

}
