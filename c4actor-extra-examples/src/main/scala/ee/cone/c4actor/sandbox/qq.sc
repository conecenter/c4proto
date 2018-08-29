import java.nio.ByteBuffer

val long = 456546879155563369L

val bytes3 = ByteBuffer.allocate(java.lang.Long.BYTES).putLong(long).array()
var long1: Long = 0L
for {
  i ← 0 to 7
} long1 = (long1 << 8) + (bytes3(i) & 0xff)
long1

val array = new Array[Byte](8)

def update(array: Array[Byte], long: Long): Unit = {
  array(0) = ((long >> 56) & 0xff).toByte
}

update(array, long)
println(array.toList)



val int = 1264488366

ByteBuffer.allocate(java.lang.Integer.BYTES).putInt(int).array().toList

val array2 = new Array[Byte](8)

array2(0) = ((int >> 24) & 0xff).toByte
array2(1) = ((int >> 16) & 0xff).toByte
array2(2) = ((int >> 8) & 0xff).toByte
array2(3) = ((int >> 0) & 0xff).toByte

println(array2.toList)

(long >> 8).toByte


100.toByte

case class A(a:Int, v: Int)

val b = A(1,1).productArity
for {
  i ← 0 until b
} println(A(1,2).productElement(i))

import java.nio.charset.StandardCharsets.UTF_8

"q".getBytes(UTF_8).size

case class B (a:String, b:Int, c:Long, d:Byte)
B("a", 1, 3,4).isInstanceOf[Product4[_, _, _, _]]
