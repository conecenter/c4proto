package ee.cone.c4actor.tests

import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor.{IdMetaAttr, Meta, NameMetaAttr, TestProtocol}
import ee.cone.c4assemble.{assemble, ignore}
import ee.cone.c4proto._

@protocol /*(Cat1)*/ object TestProtocolM {

  trait TestTrait

  @ShortName("KEK")
  @GenLens
  @Id(1)
  @Meta(NameMetaAttr("321"))
  @Cat(Cat2, Cat1) case class D_LUL(
    @Meta(NameMetaAttr("123"), IdMetaAttr(1)) @ShortName("LUL") @Id(2) test: String
  ) extends TestTrait

  @Id(2)
  @Cat(Cat1) case class D_LUL2(
    @Id (1) id1: SrcId,
    @Id (2) id2: SrcId,
    @Id (3) id3: SrcId,
    @Id (4) id4: SrcId,
    @Id (5) id5: SrcId,
    @Id (6) id6: SrcId,
    @Id (7) id7: SrcId,
    @Id (8) id8: SrcId,
    @Id (9) id9: SrcId,
    @Id (10) id10: SrcId,
    @Id (11) id11: SrcId,
    @Id (12) id12: SrcId,
    @Id (13) id13: SrcId,
    @Id (14) id14: SrcId,
    @Id (15) id15: SrcId,
    @Id (16) id16: SrcId,
    @Id (17) id17: SrcId,
    @Id (18) id18: SrcId,
    @Id (19) id19: SrcId,
    @Id (20) id20: SrcId,
    @Id (21) id21: SrcId,
    @Id (22) id22: SrcId,
  )

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
