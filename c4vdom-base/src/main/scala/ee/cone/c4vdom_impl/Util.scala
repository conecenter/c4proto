package ee.cone.c4vdom_impl

import java.nio.charset.StandardCharsets.UTF_8

object Never {
  def apply(): Nothing = throw new Exception("Never Here")
}

object UTF8String {
  def apply(data: Array[Byte]) = new String(data,UTF_8)
}
