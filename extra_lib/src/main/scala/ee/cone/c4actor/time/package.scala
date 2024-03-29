package ee.cone.c4actor

import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor.time.ProtoCurrentTimeConfig.T_Time

import scala.annotation.StaticAnnotation

package object time {

  class c4time(id: Long, appTrait: String*) extends StaticAnnotation

  class time(time: CurrentTime) extends StaticAnnotation

  abstract class CurrentTime(val refreshRateSeconds: Long) extends Product {
    lazy val srcId: SrcId = this.getClass.getName
  }

  abstract class TimeGetter(val currentTime: CurrentTime) extends WithCurrentTime {
    def ofA(context: AssembledContext): Option[T_Time]
  }

  trait TimeGetters {
    def apply(currentTime: CurrentTime): TimeGetter
    def all: List[TimeGetter]
  }

  type T_Time = ee.cone.c4actor.time.ProtoCurrentTimeConfig.T_Time
}
