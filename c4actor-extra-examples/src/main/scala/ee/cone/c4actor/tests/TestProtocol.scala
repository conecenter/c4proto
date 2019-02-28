package ee.cone.c4actor.tests

import ee.cone.c4actor.TestProtocol
import ee.cone.c4proto._

@protocol(Cat1) object TestProtocolMBase {

  @ShortName("KEK")
  @Id(1)
  @Cat(Cat2, Cat1) case class LUL(
    @ShortName("LUL") @Id(2) test: String
  )

  @Id(2)
  @Cat(Cat1) case class LUL2()

}

case object Cat1 extends OrigCategory {
  def uid: Int = 0x001
}

case object Cat2 extends OrigCategory {
  def uid: Int = 0x002
}

object TestProtocolMain {
  def main(args: Array[String]): Unit = {
    println(TestProtocolM.adapters.map(_.categories))
  }
}
