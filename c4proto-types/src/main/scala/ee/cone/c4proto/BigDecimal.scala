
package ee.cone.c4proto

import scala.annotation.StaticAnnotation

class scale(id: Int) extends StaticAnnotation

object BigDecimalFactory {
  def apply(scale: Int, bytes: okio.ByteString): BigDecimal =
    BigDecimal(new java.math.BigDecimal(new java.math.BigInteger(bytes.toByteArray), scale))
  def unapply(value: BigDecimal): Option[(Int,okio.ByteString)] = {
    val bytes = value.bigDecimal.unscaledValue.toByteArray
    Option((value.bigDecimal.scale, okio.ByteString.of(bytes,0,bytes.length)))
  }
}

@protocol object BigDecimalProtocol extends Protocol {
  case class SysBigDecimal(@Id(0x0001) scale: Int, @Id(0x0002) bytes: okio.ByteString)
}