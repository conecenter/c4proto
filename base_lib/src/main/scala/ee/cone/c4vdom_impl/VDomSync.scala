package ee.cone.c4vdom_impl

import ee.cone.c4vdom.{MutableJsonBuilder, OnChangeMode, TagJsonUtils, TagStyle}
import ee.cone.c4vdom.OnChangeMode._

object TagJsonUtilsImpl extends TagJsonUtils {
  def appendValue(builder: MutableJsonBuilder, value: String): Unit =
    builder.append("value").append(value)

  @deprecated def appendOnChange(builder: MutableJsonBuilder, value: String, deferSend: Boolean, needStartChanging: Boolean): Unit = {
    val mode = if(!deferSend) Send else if(needStartChanging) SendFirst else Defer
    appendInputAttributes(builder, value, mode)
  }

  def appendInputAttributes(builder: MutableJsonBuilder, value: String, mode: OnChangeMode): Unit = {
    appendValue(builder, value)
    if(mode.value.nonEmpty)
      builder.append("onChange").append(mode.value)
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
