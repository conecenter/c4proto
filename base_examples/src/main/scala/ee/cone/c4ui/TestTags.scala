package ee.cone.c4ui

import ee.cone.c4actor._
import ee.cone.c4di.{c4, c4multi, provide}
import ee.cone.c4vdom.Types.VDomKey
import ee.cone.c4vdom.{Tags=>_,_}

abstract class ElementValue extends VDomValue {
  def elementType: String
  def appendJsonAttributes(builder: MutableJsonBuilder): Unit
  def appendJson(builder: MutableJsonBuilder): Unit = {
    builder.startObject()
    builder.append("tp").append(elementType)
    appendJsonAttributes(builder)
    builder.end()
  }
}

case class InputTextElement[State](value: String, mode: OnChangeMode, placeholder: String)(
  input: TagJsonUtils, val receive: VDomMessage => State => State
) extends ElementValue with Receiver[State] {
  def elementType = "ExampleInput"
  def appendJsonAttributes(builder: MutableJsonBuilder): Unit = {
    builder.append("type").append("text")
    input.appendInputAttributes(builder, value, mode)
    if(placeholder.nonEmpty) builder.append("placeholder").append(placeholder)
  }
}

case class SignIn[State]()(
  input: TagJsonUtils, val receive: VDomMessage => State => State
) extends ElementValue with Receiver[State] {
  def elementType: String = "SignIn"
  def appendJsonAttributes(builder: MutableJsonBuilder): Unit = {
    input.appendInputAttributes(builder, "", OnChangeMode.Defer)
  }
}

case class ChangePassword[State]()(
  input: TagJsonUtils, val receive: VDomMessage => State => State
) extends ElementValue with Receiver[State] {
  def elementType: String = "ChangePassword"
  def appendJsonAttributes(builder: MutableJsonBuilder): Unit = {
    input.appendInputAttributes(builder, "", OnChangeMode.Defer)
  }
}

@c4("TestTagsApp") final class TestTagsProvider(factory: TestTagsFactory) {
  @provide def testTags: Seq[TestTags[Context]] = List(factory.create[Context]())
}

@c4multi("TestTagsApp") final class TestTags[State]()(
  child: ChildPairFactory, inputAttributes: TagJsonUtils, tags: Tags,
) {
  def messageStrBody(o: VDomMessage): String =
    o.body match { case bs: okio.ByteString => bs.utf8() }

  def input(access: Access[String]): ChildPair[OfDiv] = input(access, OnChangeMode.SendFirst)
  def input(access: Access[String], mode: OnChangeMode): ChildPair[OfDiv] = {
    val name = access.metaList.collect{ case l: NameMetaAttr => l.value }.mkString(".")
    access.updatingLens.map { lens =>
      val placeholder = access.metaList.collect{ case l: UserLabel => l.values.get("en") }.flatten.lastOption.getOrElse("")
      val input = InputTextElement(access.initialValue, mode, placeholder)(
        inputAttributes,
        message => lens.set(messageStrBody(message))
      )
      child[OfDiv](name, input, Nil)
    }.getOrElse(tags.text(name, access.initialValue))
  }

  def signIn(change: String => State => State): ChildPair[OfDiv] =
    child[OfDiv]("signIn", SignIn()(inputAttributes,
      (message:VDomMessage)=>change(messageStrBody(message))
    ), Nil)
  def changePassword(change: VDomMessage => State => State): ChildPair[OfDiv] =
    child[OfDiv]("changePassword", ChangePassword[State]()(inputAttributes, change), Nil)
}

object UserLabel {
  def en: String => UserLabel = UserLabel().en
  def ru: String => UserLabel = UserLabel().ru
}
case class UserLabel(values: Map[String,String] = Map.empty) extends AbstractMetaAttr {
  def en: String => UserLabel = v => copy(values + ("en"->v))
  def ru: String => UserLabel = v => copy(values + ("ru"->v))
}

case object IsDeep extends AbstractMetaAttr
