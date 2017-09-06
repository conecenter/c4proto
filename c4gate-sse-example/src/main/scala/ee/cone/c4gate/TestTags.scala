package ee.cone.c4gate

import ee.cone.c4actor.{Context, Cursor}
import ee.cone.c4vdom._

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
  input: TagJsonUtils, val receive: VDomMessage ⇒ State ⇒ State
) extends ElementValue with Receiver[State] {
  def elementType = "input"
  def appendJsonAttributes(builder: MutableJsonBuilder): Unit = {
    builder.append("type").append("text")
    input.appendInputAttributes(builder, value, deferSend)
  }
}

case class SignIn[State]()(
  input: TagJsonUtils, val receive: VDomMessage ⇒ State ⇒ State
) extends ElementValue with Receiver[State] {
  def elementType: String = "SignIn"
  def appendJsonAttributes(builder: MutableJsonBuilder): Unit = {
    input.appendInputAttributes(builder, "", deferSend = true)
  }
}

case class ChangePassword[State]()(
  input: TagJsonUtils, val receive: VDomMessage ⇒ State ⇒ State
) extends ElementValue with Receiver[State] {
  def elementType: String = "ChangePassword"
  def appendJsonAttributes(builder: MutableJsonBuilder): Unit = {
    input.appendInputAttributes(builder, "", deferSend = true)
  }
}

class TestTags[State](
  child: ChildPairFactory, inputAttributes: TagJsonUtils, tags: Tags
) {
  def messageStrBody(o: VDomMessage): String =
    o.body match { case bs: okio.ByteString ⇒ bs.utf8() }
  def input(cursor: Cursor[String]): ChildPair[OfDiv] =
    if(cursor.lens.isEmpty) tags.text(cursor.name, cursor.value)
    else {
      def onChange(message:VDomMessage): Context⇒Context = local ⇒ {
        val lens = cursor.lens.get
        if(lens.of(local) != cursor.value) throw new Exception
        lens.set(messageStrBody(message))(local)
      }
      val input = InputTextElement(cursor.value, deferSend=true)(inputAttributes,onChange)
      child[OfDiv](cursor.name, input, Nil)
    }


  def signIn(change: String ⇒ State ⇒ State): ChildPair[OfDiv] =
    child[OfDiv]("signIn", SignIn()(inputAttributes,
      (message:VDomMessage)⇒change(messageStrBody(message))
    ), Nil)
  def changePassword(change: VDomMessage ⇒ State ⇒ State): ChildPair[OfDiv] =
    child[OfDiv]("changePassword", ChangePassword[State]()(inputAttributes, change), Nil)
}
