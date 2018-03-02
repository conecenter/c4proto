package ee.cone.c4vdom

import ee.cone.c4vdom.Types.VDomKey

case class PathFactoryImpl[Context](
  child: ChildPairFactory, pathToJson: CanvasToJson
) extends PathFactory {
  def path(key: VDomKey, children: ChildPair[OfPath]*): ChildPair[OfPathParent] =
    path(key, children.toList)
  def path(key: VDomKey, children: List[ChildPair[OfPath]]): ChildPair[OfPathParent] =
    child[OfPathParent](
      key,
      PartPath[Context](children.collect{ case a:PathAttr⇒a })(pathToJson),
      children.filterNot(_.isInstanceOf[PathAttr])
    )
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
