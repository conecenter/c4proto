package ee.cone.c4actor

import scala.annotation.StaticAnnotation

package object time {

  class с4time(id: Long, appTrait: String*) extends StaticAnnotation

  class time(time: CurrentTime) extends StaticAnnotation

  abstract class CurrentTime(refreshRateSeconds: Long)
}
