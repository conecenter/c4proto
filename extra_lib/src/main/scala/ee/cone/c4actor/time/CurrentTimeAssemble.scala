package ee.cone.c4actor.time

import java.security.SecureRandom

import ee.cone.c4actor.QProtocol.S_Firstborn
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor._
import ee.cone.c4actor.dep.request.CurrentTimeApp
import ee.cone.c4actor.dep.request.CurrentTimeProtocol.S_CurrentTimeNodeSetting
import ee.cone.c4assemble.Types.{Each, Values}
import ee.cone.c4assemble.{AbstractAll, c4assemble}

trait GeneralCurrentTimeAppBase extends CurrentTimeApp

trait GeneralCurrentTimeConfig {
  def srcId: SrcId
  def refreshRateSeconds: Long
}

trait CurrentTimeConfig[Model <: Product] extends GeneralCurrentTimeConfig {
  def cl: Class[Model]
  lazy val srcId: SrcId = cl.getName

  def of: Model => Long
  def set: Long => Model => Model
  def default: Model
}

@c4assemble("GeneralCurrentTimeApp") class CurrentTimeGeneralAssembleBase(configs: List[GeneralCurrentTimeConfig])(
  typed: List[CurrentTimeConfig[_ <: Product]] =
  CheckedMap(configs.asInstanceOf[List[CurrentTimeConfig[_ <: Product]]]
    .map(c => c.srcId -> c)
  ).values.toList,
  all: List[SrcId] = configs.map(_.srcId).distinct
) {
  type CurrentTimeGeneralAll = AbstractAll

  def CreateGeneralTimeConfig(
    firstBornId: SrcId,
    firstborn: Each[S_Firstborn],
    timeSetting: Values[S_CurrentTimeNodeSetting]
  ): Values[(SrcId, GeneralCurrentTimeTransform)] =
    WithPK(GeneralCurrentTimeTransform(s"${firstborn.srcId}-general-time", timeSetting.toList, typed)) :: Nil
}

case class GeneralCurrentTimeTransform(
  srcId: SrcId, configs: List[S_CurrentTimeNodeSetting],
  currentTimes: List[CurrentTimeConfig[_ <: Product]]
) extends TxTransform {
  private val random: SecureRandom = new SecureRandom()

  lazy val configsMap: Map[String, Long] =
    configs.map(conf => conf.timeNodeId -> conf.refreshSeconds).toMap
  lazy val actions: List[Context => Seq[LEvent[Product]]] =
    currentTimes.map(currentTime =>
      process(currentTime, configsMap.get(currentTime.srcId), random.nextInt(500))
    )

  def transform(local: Context): Context = {
    actions.flatMap(_.apply(local)) match {
      case updates if updates.isEmpty => local
      case updates => TxAdd(updates)(local)
    }
  }

  def process[Model <: Product](
    config: CurrentTimeConfig[Model],
    refreshRate: Option[Long],
    offset: Long
  ): Context => Seq[LEvent[Product]] = {
    val cl = config.cl
    val default = config.default
    val of = config.of
    val set = config.set
    val srcId = config.srcId
    local => {
      val now = System.currentTimeMillis()
      ByPK(cl).of(local).get(srcId) match {
        case Some(time) if of(time) + offset < now =>
          LEvent.update(set(now)(time))
        case None =>
          LEvent.update(set(now)(default))
        case _ => Nil
      }
    }
  }

}
