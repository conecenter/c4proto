package ee.cone.c4actor

import ee.cone.c4actor.Types.SrcId

import scala.annotation.StaticAnnotation

package object time {

  class c4time(id: Long, appTrait: String*) extends StaticAnnotation

  class time(time: CurrentTime) extends StaticAnnotation

  abstract class CurrentTime(val refreshRateSeconds: Long) extends Product {
    lazy val srcId: SrcId = this.getClass.getName

    private lazy val packageName = this.getClass.getPackage.getName
    private lazy val simpleName = this.getClass.getSimpleName.init
    private lazy val protocolClassName = s"$packageName.Proto${simpleName}Base$$T_$simpleName"
    def of(local: Context): Option[T_Time] =
      ByPrimaryKeyGetter[T_Time](protocolClassName).of(local).get(srcId)
  }

}
