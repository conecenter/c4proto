
package ee.cone.c4proto

object BigDecimalFactory {
  def apply(scale: Int, bytes: okio.ByteString): BigDecimal =
    BigDecimal(new java.math.BigDecimal(new java.math.BigInteger(bytes.toByteArray), scale))
  def unapply(value: BigDecimal): Option[(Int,okio.ByteString)] = {
    val byteString = ToByteString(value.bigDecimal.unscaledValue.toByteArray)
    Option((value.bigDecimal.scale, byteString))
  }
}

@protocol object BigDecimalProtocol {
  case class SysBigDecimal(@Id(0x0001) scale: Int, @Id(0x0002) bytes: okio.ByteString)
}