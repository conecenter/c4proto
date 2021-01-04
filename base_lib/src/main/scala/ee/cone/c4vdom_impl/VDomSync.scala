package ee.cone.c4vdom_impl

import ee.cone.c4vdom.{JsonValueAdapter, MutableJsonBuilder, OnChangeMode, TagJsonUtils}
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
      builder.append("onChange").append(mode.value) // ?todo: send on blur anyway
  }

  def jsonValueAdapter[T](inner: (T, MutableJsonBuilder) => Unit): JsonValueAdapter[T] =
    new JsonValueAdapter[T] {
      def appendJson(value: T, builder: MutableJsonBuilder): Unit = inner(value, builder)
    }
}

case object WasNoValueImpl extends WasNoVDomValue {
  def appendJson(builder: MutableJsonBuilder): Unit = Never()
}
