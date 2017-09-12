package ee.cone.c4gate

import ee.cone.c4actor.{Context,FieldAccess,SharedComponentKey}
import ee.cone.c4assemble.fieldAccess
import ee.cone.c4gate.TestTodoProtocol.TodoTask
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

case class InputTextElement[State](value: String, deferSend: Boolean, placeholder: String)(
  input: TagJsonUtils, val receive: VDomMessage ⇒ State ⇒ State
) extends ElementValue with Receiver[State] {
  def elementType = "input"
  def appendJsonAttributes(builder: MutableJsonBuilder): Unit = {
    builder.append("type").append("text")
    input.appendInputAttributes(builder, value, deferSend)
    if(placeholder.nonEmpty) builder.append("placeholder").append(placeholder)
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

  class InputView(local: Context) {
    def binds(value: String): Nothing = throw new Exception("NotExpanded")
    def bindsAccess(accessOpt: Option[FieldAccess[String]]): List[ChildPair[OfDiv]] =
      accessOpt.map(access ⇒
        access.updatingLens.map { lens ⇒
          val placeholder = PlaceholderKey.of(local).getOrElse(access.name,"")
          val input = InputTextElement(access.initialValue, deferSend = true, placeholder)(
            inputAttributes,
            message ⇒ lens.set(messageStrBody(message))
          )
          child[OfDiv](access.name, input, Nil)
        }.getOrElse(tags.text(access.name, access.initialValue))
      ).toList
  }
  def input(local: Context): InputView = new InputView(local)

  def signIn(change: String ⇒ State ⇒ State): ChildPair[OfDiv] =
    child[OfDiv]("signIn", SignIn()(inputAttributes,
      (message:VDomMessage)⇒change(messageStrBody(message))
    ), Nil)
  def changePassword(change: VDomMessage ⇒ State ⇒ State): ChildPair[OfDiv] =
    child[OfDiv]("changePassword", ChangePassword[State]()(inputAttributes, change), Nil)
}

case object PlaceholderKey extends SharedComponentKey[Map[String,String]]


