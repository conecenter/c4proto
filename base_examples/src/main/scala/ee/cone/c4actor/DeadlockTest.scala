package ee.cone.c4actor

import ee.cone.c4di.c4

import scala.concurrent.Future

object DeadlockTest {
  case class Rich(){
    lazy val veryRich = VeryRich(this)
    lazy val value = 2
    lazy val veryRichValue = {
      Thread.sleep(300)
      println("in Rich")
      val res = veryRich.value
      println("Rich can not be here")
      res
    }
  }

  case class VeryRich(b: Rich){
    lazy val value = {
      Thread.sleep(300)
      println("in VeryRich")
      val res = b.value
      println("VeryRich can not be here")
      res
    }
  }
}

import DeadlockTest._

@c4("DeadlockTestApp") final class DeadlockTest(execution: Execution) extends Executable {
  def run(): Unit = {
    val b = Rich()
    val c = b.veryRich
    execution.fatal(Future(b.veryRichValue)(_))
    execution.fatal(Future(c.value)(_))
  }
}
