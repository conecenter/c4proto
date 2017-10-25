package ee.cone.c4vdom

import ee.cone.c4vdom.Types.VDomKey

case class PathContextImpl[Context](
  transforms: List[Transform]
)(
  child: ChildPairFactory, pathToJson: CanvasToJson
) extends PathContext {

  def add(tr: Transform): PathContext = PathContextImpl[Context](tr::transforms)(child,pathToJson)
  def path(key: VDomKey, attrs: List[PathAttr])
    (children: List[ChildPair[OfCanvas]]): ChildPair[OfCanvas] =
    child[OfCanvas](key, PartPath[Context](attrs, transforms)(pathToJson), children)
}

case class PartPath[Context](
  attrs:List[PathAttr], transforms: List[Transform]
)(
  pathToJson: CanvasToJson
) extends VDomValue with Receiver[Context] {
  def receive: VDomMessage => Context => Context = message => message.header("X-r-action") match {
    case "clickColor" =>
      attrs.collect{case h:ClickPathHandler[_]=>h}.head.handleClick.asInstanceOf[Context=>Context]
    case _ => throw new Exception("Unknown action type")
  }
  def appendJson(builder: MutableJsonBuilder): Unit =
    pathToJson.appendJson(attrs,transforms,builder)
}
