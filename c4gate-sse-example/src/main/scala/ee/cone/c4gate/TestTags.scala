package ee.cone.c4gate

import ee.cone.c4actor.{Access, MetaAttr, NameMetaAttr, ProdLens}
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

  def input(access: Access[String]): ChildPair[OfDiv] = {
    val name = access.metaList.collect{ case l: NameMetaAttr ⇒ l.value }.mkString
    access.updatingLens.map { lens ⇒
      val placeholder = access.metaList.collect{ case l: UserLabel ⇒ l.values.get("en") }.flatten.lastOption.getOrElse("")
      val input = InputTextElement(access.initialValue, deferSend = true, placeholder)(
        inputAttributes,
        message ⇒ lens.set(messageStrBody(message))
      )
      child[OfDiv](name, input, Nil)
    }.getOrElse(tags.text(name, access.initialValue))
  }

  def dateInput(access: Access[Option[Long]]): ChildPair[OfDiv] =
    input(access to ProdLens[Option[Long],String](Nil)(
      _.map(_.toString).getOrElse(""),
      s⇒_⇒ for(s←Option(s) if s.nonEmpty) yield s.toLong
    ))

  def signIn(change: String ⇒ State ⇒ State): ChildPair[OfDiv] =
    child[OfDiv]("signIn", SignIn()(inputAttributes,
      (message:VDomMessage)⇒change(messageStrBody(message))
    ), Nil)
  def changePassword(change: VDomMessage ⇒ State ⇒ State): ChildPair[OfDiv] =
    child[OfDiv]("changePassword", ChangePassword[State]()(inputAttributes, change), Nil)
}

object UserLabel {
  def en: String ⇒ UserLabel = UserLabel().en
  def ru: String ⇒ UserLabel = UserLabel().ru
}
case class UserLabel(values: Map[String,String] = Map.empty) extends MetaAttr {
  def en: String ⇒ UserLabel = v ⇒ copy(values + ("en"→v))
  def ru: String ⇒ UserLabel = v ⇒ copy(values + ("ru"→v))
}

case object IsDeep extends MetaAttr
