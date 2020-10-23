package ee.cone.c4actor

import ee.cone.c4actor.Types.SrcId

import scala.annotation.StaticAnnotation

package object time {

  class c4time(id: Long, appTrait: String*) extends StaticAnnotation

  class time(time: CurrentTime) extends StaticAnnotation

  abstract class CurrentTime(val refreshRateSeconds: Long) extends Product {
    lazy val srcId: SrcId = this.getClass.getName
  }

  abstract class TimeGetter(val currentTime: CurrentTime) extends WithCurrentTime {
    def cl: Class[_ <: T_Time]
    def ofA(context: AssembledContext): Option[T_Time]
  }

  trait TimeGetters {
    def apply(currentTime: CurrentTime): TimeGetter
    def all: List[TimeGetter]
  }

}
