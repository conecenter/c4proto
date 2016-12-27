package ee.cone.c4vdom_impl

import ee.cone.c4vdom.{TagJsonUtils, MutableJsonBuilder}

object TagJsonUtilsImpl extends TagJsonUtils {
  def appendInputAttributes(builder: MutableJsonBuilder, value: String, deferSend: Boolean): Unit = {
    builder.append("value").append(value)
    if(deferSend){
      builder.append("onChange").append("local")
      builder.append("onBlur").append("send")
    } else builder.append("onChange").append("send")
  }
}

object WasNoValueImpl extends WasNoVDomValue {
  def appendJson(builder: MutableJsonBuilder): Unit = Never()
}
