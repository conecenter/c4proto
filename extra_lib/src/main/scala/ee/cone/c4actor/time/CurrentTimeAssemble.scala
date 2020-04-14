package ee.cone.c4actor.time

import java.security.SecureRandom

import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor.QProtocol.S_Firstborn
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor._
import ee.cone.c4actor.dep.request.CurrentTimeApp
import ee.cone.c4actor.dep.request.CurrentTimeProtocol.S_CurrentTimeNodeSetting
import ee.cone.c4assemble.Types.{Each, Values}
import ee.cone.c4assemble.{AbstractAll, All, byEq, c4assemble}
import ee.cone.c4di.c4

trait T_Time extends Product {
  def srcId: SrcId
  def millis: Long
}

trait GeneralCurrentTimeAppBase extends CurrentTimeApp

trait WithCurrentTime {
  def currentTime: CurrentTime
}

trait GeneralCurrTimeConfig extends WithCurrentTime

trait CurrTimeConfig[Model <: T_Time] extends GeneralCurrTimeConfig {
  def cl: Class[Model]
  def set: Long => Model => Model
  def default: Model
  def timeGetter: GetByPK[Model]
}

@c4("GeneralCurrentTimeApp") final class TimeGettersImpl(timeGetters: List[TimeGetter]) extends TimeGetters {
  lazy val gettersMap: Map[SrcId, TimeGetter] = timeGetters.map(getter => getter.currentTime.srcId -> getter).toMap
  def apply(currentTime: CurrentTime): TimeGetter =
    gettersMap(currentTime.srcId)
}

@c4assemble("GeneralCurrentTimeApp") class CurrentTimeGeneralAssembleBase(configs: List[GeneralCurrTimeConfig])(
  typed: List[CurrTimeConfig[_ <: T_Time]] =
  CheckedMap(configs.asInstanceOf[List[CurrTimeConfig[_ <: T_Time]]]
    .map(c => c.currentTime.srcId -> c)
  ).values.toList
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
    @byEq[CurrentTimeGeneralAll](All) timeSetting: Values[S_CurrentTimeNodeSetting],
    //@time(TestDepTime) time: Each[Time] // byEq[SrcId](TestTime.srcId) time: Each[Time]
  ): Values[(SrcId, TxTransform)] =
    WithPK(GeneralCurrentTimeTransform(s"${firstborn.srcId}-general-time", timeSetting.toList, typed)) :: Nil
}

case class GeneralCurrentTimeTransform(
  srcId: SrcId, configs: List[S_CurrentTimeNodeSetting],
  currentTimes: List[CurrTimeConfig[_ <: T_Time]]
) extends TxTransform with LazyLogging {
  private val random: SecureRandom = new SecureRandom()

  lazy val configsMap: Map[String, Long] =
    configs.map(conf => conf.timeNodeId -> conf.refreshSeconds).toMap
  lazy val actions: List[Context => Seq[LEvent[Product]]] =
    currentTimes.map(currentTime =>
      process(currentTime, configsMap.get(currentTime.currentTime.srcId), random.nextInt(500))
    )

  def transform(local: Context): Context = {
    actions.flatMap(_.apply(local)) match { // TODO may be throttle time updates and do them one by one
      case updates if updates.isEmpty => local
      case updates => TxAdd(updates)(local)
    }
  }

  def process[Model <: T_Time](
    config: CurrTimeConfig[Model],
    refreshRate: Option[Long],
    offset: Long
  ): Context => Seq[LEvent[Product]] = {
    val default = config.default
    val srcId = config.currentTime.srcId
    val set = config.set
    val refreshRateMillis = config.currentTime.refreshRateSeconds * 1000L
    local => {
      val now = System.currentTimeMillis()
      config.timeGetter.ofA(local).get(srcId) match {
        case Some(time) if time.millis + offset + refreshRateMillis < now =>
          logger.debug(s"Updating ${config.currentTime.srcId} with ${offset}")
          LEvent.update(set(now)(time))
        case None =>
          LEvent.update(set(now)(default))
        case _ => Nil
      }
    }
  }

}
