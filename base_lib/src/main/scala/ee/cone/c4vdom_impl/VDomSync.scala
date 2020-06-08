package ee.cone.c4vdom_impl

import ee.cone.c4vdom.{MutableJsonBuilder, OnChangeMode, TagJsonUtils, TagStyle}

object TagJsonUtilsImpl extends TagJsonUtils {
  def appendInputAttributes(builder: MutableJsonBuilder, value: String, mode: OnChangeMode): Unit = {
    builder.append("value").append(value)
    if(mode.value.nonEmpty)
      builder.append("onChange").append(mode.value)
    /*if(deferSend){
      builder.append("onChange").append("local")
      builder.append("onBlur").append("send")
    } else builder.append("onChange").append("send")*/
  }
  def appendStyles(builder: MutableJsonBuilder, styles: List[TagStyle]): Unit =
    if(styles.nonEmpty){
      builder.append("style").startObject(); {
        styles.foreach(_.appendStyle(builder))
        builder.end()
      }
    }
}

object WasNoValueImpl extends WasNoVDomValue {
  def appendJson(builder: MutableJsonBuilder): Unit = Never()
}
