package ee.cone.c4vdom_impl

import ee.cone.c4vdom._
import ee.cone.c4vdom.Types._

case class TextContentElement(content: String) extends VDomValue {
  def appendJson(builder: MutableJsonBuilder) = {
    builder.startObject()
    builder.append("tp").append("span")
    builder.append("content").append(content)
    builder.end()
  }
}

case class DivButton()(val onClick:Option[()=>Unit]) extends VDomValue with OnClickReceiver {
  def appendJson(builder: MutableJsonBuilder)={
    builder.startObject()
    builder.append("tp").append("div")
    onClick.foreach(_⇒ builder.append("onClick").append("send"))
    builder.append("style"); {
      builder.startObject()
      builder.append("cursor").append("pointer")
      builder.end()
    }
    builder.end()
  }
}

case object DivTagName extends TagName("div")
case class StyledValue(tagName: TagName, styles: List[TagStyle]) extends VDomValue {
  def appendJson(builder: MutableJsonBuilder) = {
    builder.startObject()
    builder.append("tp").append(tagName.name)
    if(styles.nonEmpty){
      builder.append("style"); {
        builder.startObject()
        styles.foreach(_.appendStyle(builder))
        builder.end()
      }
    }
    builder.end()
  }
}

class TagsImpl(
  child: ChildPairFactory,
  utils: TagJsonUtils
) extends Tags {
  def text(key: VDomKey, text: String) =
    child[OfDiv](key, TextContentElement(text), Nil)
  def tag(key: VDomKey, tagName: TagName, attr: TagStyle*)(children: List[ChildPair[OfDiv]]): ChildPair[OfDiv] =
    child[OfDiv](key, StyledValue(tagName, attr.toList), children)
  def div(key: VDomKey, attr: TagStyle*)(children: List[ChildPair[OfDiv]]): ChildPair[OfDiv] =
    tag(key, DivTagName, attr:_*)(children)
  def divButton(key:VDomKey)(action:()=>Unit)(children: List[ChildPair[OfDiv]])=
    child[OfDiv](key,DivButton()(Some(action)), children)
}

