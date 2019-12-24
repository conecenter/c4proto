package ee.cone.c4actor

import ee.cone.c4actor.Types.SrcId

import scala.annotation.StaticAnnotation

package object time {

  class c4time(id: Long, appTrait: String*) extends StaticAnnotation

  class time(time: CurrentTime) extends StaticAnnotation

  abstract class CurrentTime(val refreshRateSeconds: Long) extends Product {
    lazy val srcId: SrcId = this.getClass.getName
  }
}
