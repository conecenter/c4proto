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
  child: ChildPairFactory, inputAttributes: TagJsonUtils, save: Product ⇒ State ⇒ State
) {
  def toInput[Model<:Product](key: VDomKey, attr: VDomLens[Model,String]): Model ⇒ ChildPair[OfDiv] =
    model ⇒ input(key, attr.of(model), value⇒save(attr.set(value)(model)))

  private def input(key: VDomKey, value: String, change: String ⇒ State ⇒ State): ChildPair[OfDiv] =
    child[OfDiv](key, InputTextElement(value, deferSend=true)(inputAttributes, Some(change)), Nil)
}

abstract class TextInputLens[Model<:Product](val of: Model⇒String, val set: String⇒Model⇒Model)
  extends VDomLens[Model,String]
{
  def modify: (String ⇒ String) ⇒ Model ⇒ Model = f ⇒ model ⇒ set(f(of(model)))(model)
}