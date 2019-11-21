package ee.cone.c4actor.tests

import ee.cone.c4actor.{IdMetaAttr, Meta, NameMetaAttr, TestProtocol}
import ee.cone.c4assemble.{assemble, ignore}
import ee.cone.c4proto._

@protocol /*(Cat1)*/ object TestProtocolMBase {

  @ShortName("KEK")
  @GenLens
  @Id(1)
  @Cat(Cat2, Cat1) case class D_LUL(
    @Meta(NameMetaAttr("123"), IdMetaAttr(1)) @ShortName("LUL") @Id(2) test: String
  )

  @Id(2)
  @Cat(Cat1) case class D_LUL2()

}

case object Cat1 extends DataCategory {
  def uid: Int = 0x001
}

case object Cat2 extends DataCategory {
  def uid: Int = 0x002
}

object TestProtocolMain {
  def main(args: Array[String]): Unit = {
    println(
      "commented due to the failure of cats empire"
      // TestProtocolM.adapters.map(_.categories)
    )
  }
}

@assemble class TestAssembleBase {
  @ignore def test: Int = 1
}
