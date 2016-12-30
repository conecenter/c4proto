package ee.cone.c4gate

import ee.cone.c4vdom._
import ee.cone.c4vdom.Types.VDomKey

abstract class ElementValue extends VDomValue {
  def elementType: String
  def appendJsonAttributes(builder: MutableJsonBuilder): Unit
  def appendJson(builder: MutableJsonBuilder): Unit = {
    builder.startObject()
      .append("tp").append(elementType)
    appendJsonAttributes(builder)
    builder.end()
  }
}

abstract class SimpleElement extends ElementValue {
  def appendJsonAttributes(builder: MutableJsonBuilder): Unit = ()
}
object DivElement extends SimpleElement { def elementType = "div" }
object SpanElement extends SimpleElement { def elementType = "span" }

case class TextContentElement(content: String) extends ElementValue {
  def elementType = "span"
  def appendJsonAttributes(builder: MutableJsonBuilder): Unit =
    builder.append("content").append(content)
}

case class ButtonElement[State](caption: String)(
  val onClick: Option[State⇒State]
) extends ElementValue with OnClickReceiver[State] {
  def elementType = "input"
  def appendJsonAttributes(builder: MutableJsonBuilder): Unit = {
    builder.append("type").append("button")
    builder.append("value").append(caption)
    onClick.foreach(_⇒ builder.append("onClick").append("send"))
  }
}

case class InputTextElement[State](value: String, deferSend: Boolean)(
  input: TagJsonUtils, val onChange: Option[String ⇒ State ⇒ State]
) extends ElementValue with OnChangeReceiver[State] {
  def elementType = "input"
  def appendJsonAttributes(builder: MutableJsonBuilder): Unit = {
    builder.append("type").append("text")
    input.appendInputAttributes(builder, value, deferSend)
  }
}

class TestTags[State](
  child: ChildPairFactory, inputAttributes: TagJsonUtils
) {
  def span(key: VDomKey, children: List[ChildPair[OfDiv]]): ChildPair[OfDiv] =
    child[OfDiv](key, SpanElement, children)
  def div(key: VDomKey, children: List[ChildPair[OfDiv]]): ChildPair[OfDiv] =
    child[OfDiv](key, DivElement, children)
  def input(key: String, value: String, change: String ⇒ State ⇒ State): ChildPair[OfDiv] =
    child[OfDiv](key, InputTextElement(value, deferSend=true)(inputAttributes, Some(change)), Nil)
  def button(key: String, caption: String, action: State ⇒ State): ChildPair[OfDiv] =
    child[OfDiv](key, ButtonElement(caption)(Some(action)), Nil)
}
