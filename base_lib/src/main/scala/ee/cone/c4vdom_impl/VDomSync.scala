package ee.cone.c4vdom_impl

import ee.cone.c4vdom.{MutableJsonBuilder, TagJsonUtils, TagStyle}

object TagJsonUtilsImpl extends TagJsonUtils {
  def appendValue(builder: MutableJsonBuilder, value: String): Unit =
    builder.append("value").append(value)

  def appendOnChange(builder: MutableJsonBuilder, value: String, deferSend: Boolean, needStartChanging: Boolean): Unit = {
    appendValue(builder, value)
    if (!deferSend)
      builder.append("onChange").append("send")
    else if (needStartChanging) {
      builder.append("onChange").append("send_first")
      builder.append("onBlur").append("send")
    } else {
      builder.append("onChange").append("local")
      builder.append("onBlur").append("send")
    }
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
