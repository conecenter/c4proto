package ee.cone.c4vdom

import ee.cone.c4vdom.Types.VDomKey

case class PathFactoryImpl[Context](
  child: ChildPairFactory, pathToJson: CanvasToJson
) extends PathFactory {
  def path(key: VDomKey, attrs: List[PathAttr])
    (children: List[ChildPair[OfCanvas]]): ChildPair[OfCanvas] =
    child[OfCanvas](key, PartPath[Context](attrs)(pathToJson), children)
}

case class PartPath[Context](attrs:List[PathAttr])(
  pathToJson: CanvasToJson
) extends VDomValue with Receiver[Context] {
  def receive: VDomMessage => Context => Context = message => message.header("X-r-action") match {
    case "clickColor" =>
      attrs.collect{case h:ClickPathHandler[_]=>h}.head.handleClick.asInstanceOf[Context=>Context]
    case _ => throw new Exception("Unknown action type")
  }
  def appendJson(builder: MutableJsonBuilder): Unit =
    pathToJson.appendPathJson(attrs,builder)
}
