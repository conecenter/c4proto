package ee.cone.c4ui

import ee.cone.c4di.c4
import ee.cone.c4vdom._
import ee.cone.c4vdom.Types._
import ee.cone.c4vdom_impl.SeedVDomValue




case class TextContentElement(content: String) extends VDomValue {
  def appendJson(builder: MutableJsonBuilder): Unit = {
    builder.startObject()
    builder.append("tp").append("span")
    builder.append("content").append(content)
    builder.end()
  }
}

case class DivButton[State]()(val receive:VDomMessage => State => State) extends VDomValue with Receiver[State] {
  def appendJson(builder: MutableJsonBuilder): Unit = {
    builder.startObject()
    builder.append("tp").append("div")
    builder.append("onClick").append("sendThen")
    builder.append("style").startObject(); {
      builder.append("cursor").append("pointer")
      builder.end()
    }
    builder.end()
  }
}

abstract class TagName(val name: String)
case object DivTagName extends TagName("div")
case class StyledValue(tagName: TagName, styles: List[TagStyle])(utils: Tags) extends VDomValue {
  def appendJson(builder: MutableJsonBuilder): Unit = {
    builder.startObject()
    builder.append("tp").append(tagName.name)
    utils.appendStyles(builder, styles)
    builder.end()
  }
}

case class SeedElement(seed: Product, styles: List[TagStyle], src: String)(utils: Tags) extends SeedVDomValue { // copy if missing
  def appendJson(builder: MutableJsonBuilder): Unit = {
    builder.startObject()
    builder.append("tp").append("iframe")
    builder.append("src").append(src)
    utils.appendStyles(builder, styles)
    builder.append("ref").startArray(); {
      builder.append("seed").append(seed.productElement(0).toString)
      builder.end()
    }
    builder.end()
  }
}

@c4("TestTagsApp") final class TagsImpl(
  child: ChildPairFactory,
  utils: TagJsonUtils
) extends Tags {
  def text(key: VDomKey, text: String): ChildPair[OfDiv] = //base_examples
    child[OfDiv](key, TextContentElement(text), Nil)
  def tag(key: VDomKey, tagName: TagName, attr: List[TagStyle])(children: ViewRes): ChildPair[OfDiv] = //div only
    child[OfDiv](key, StyledValue(tagName, attr)(this), children)
  def div(key: VDomKey, attr: List[TagStyle])(children: ViewRes): ChildPair[OfDiv] = //base_examples
    tag(key, DivTagName, attr)(children)
  def divButton[State](key:VDomKey)(action:State=>State)(children: ViewRes): ChildPair[OfDiv] = //base_examples
    child[OfDiv](key,DivButton()((_:VDomMessage)=>action), children)
  def seed(product: Product)(attr: List[TagStyle], src: String)(children: ViewRes): ChildPair[OfDiv] = //base_examples
    child[OfDiv](product.productElement(0).toString,SeedElement(product,attr,src)(this), children)
  def appendStyles(builder: MutableJsonBuilder, styles: List[TagStyle]): Unit =
    if(styles.nonEmpty){
      builder.append("style").startObject(); {
        styles.foreach(_.appendStyle(builder))
        builder.end()
      }
    }
}

trait Tags {
  def text(key: VDomKey, text: String): ChildPair[OfDiv]
  def tag(key: VDomKey, tagName: TagName, attr: List[TagStyle])(children: ViewRes): ChildPair[OfDiv]
  def div(key: VDomKey, attr: List[TagStyle])(children: ViewRes): ChildPair[OfDiv]
  def divButton[State](key:VDomKey)(action:State=>State)(children: ViewRes): ChildPair[OfDiv]
  def seed(product: Product)(attr: List[TagStyle], src: String)(children: ViewRes): ChildPair[OfDiv]
  def appendStyles(builder: MutableJsonBuilder, styles: List[TagStyle]): Unit
}

////

abstract class SingleTagStyle() extends TagStyle {
  def key: String
  def valueStr: String
  def appendStyle(builder: MutableJsonBuilder) = builder.append(key).append(valueStr)
}
abstract class StaticTagStyle(val key: String, val valueStr: String)
  extends SingleTagStyle
abstract class PxTagStyle extends SingleTagStyle {
  def value: Int
  def valueStr = s"${value}px"
}
case class HeightTagStyle(value:Int) extends PxTagStyle{ def key = "height" }
case object WidthAllTagStyle extends StaticTagStyle("width","100%")
case class WidthTagStyle(value: Int) extends PxTagStyle{ def key = "width" }

trait TestTagStyles {
  def width: Int=>TagStyle = WidthTagStyle
  def widthAll: TagStyle = WidthAllTagStyle
  def height: Int=>TagStyle = HeightTagStyle
}