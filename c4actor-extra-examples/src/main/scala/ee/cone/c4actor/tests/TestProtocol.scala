package ee.cone.c4actor.tests

import ee.cone.c4actor.TestProtocol
import ee.cone.c4proto.{Cat, Id, Protocol, protocol, OrigCategory}

@protocol(ABABABBA, OLOLOL) object TestProtocolM extends Protocol{
    @Id(1) @Cat(OLOLOL, ABABABBA) case class LUL ()
    @Id(2)  @Cat(ABABABBA) case class LUL2 ()
}

case object ABABABBA extends OrigCategory {
  def uid: Int = 0x001
}

case object OLOLOL extends OrigCategory {
  def uid: Int = 0x002
}

object TestProtocolMain {
  def main(args: Array[String]): Unit = {
    println(TestProtocolM.adapters.map(_.categories))
  }
}
