package ee.cone.c4vdom_impl

import java.util.{Base64, UUID}

import ee.cone.c4vdom._

trait DictMessage {
  def value: Map[String,String]
}

trait SessionManager {
  def switchSession(key: UUID): Unit
}

trait View {
  def view(path: String): List[ChildPair[_]]
}

class CurrentVDom(
  diff: Diff,
  jsonToString: JsonToString,
  wasNoValue: WasNoVDomValue,
  child: ChildPairFactory,
  sessionManager: SessionManager,
  rootView: View
) extends CurrentView {
  def invalidate() = vDom = wasNoValue
  def until(value: Long) = if(value < until) until = value

  private var until: Long = Long.MaxValue
  private var vDom: VDomValue = wasNoValue
  private var hashForView = ""
  private var hashFromAlien = ""
  private var rootAttributes: List[(String,List[String])] = Nil

  private def relocate(message: DictMessage): Unit =
    for(hash <- message.value.get("X-r-location-hash")) if(hashFromAlien != hash){
      hashFromAlien = hash
      relocate(hash)
    }
  def relocate(value: String) = if(hashForView != value) {
    hashForView = value
    println(s"hashForView: $value")
    invalidate()
  }
  private def dispatch(message: DictMessage): Unit = {
    if(until <= 0) return
    for(pathStr <- message.value.get("X-r-vdom-path")){
      val path = pathStr.split("/").toList match {
        case "" :: parts => parts
        case _ => Never()
      }
      def decoded = UTF8String(Base64.getDecoder.decode(message.value("X-r-vdom-value-base64")))
      (message.value.get("X-r-action"), ResolveValue(vDom, path)) match {
        case (Some("click"), Some(v: OnClickReceiver)) => v.onClick.get()
        case (Some("change"), Some(v: OnChangeReceiver)) => v.onChange.get(decoded)
        case (Some("resize"), Some(v: OnResizeReceiver)) => v.onResize.get(decoded)
        case v => throw new Exception(s"$path ($v) can not receive $message")
      }
    }
  }
  private def switchSession(message: DictMessage) =
    for(sessionKey <- message.value.get("X-r-session")){
      sessionManager.switchSession(UUID.fromString(sessionKey))
    }
  private def showToAlien() = {
    if(until <= System.currentTimeMillis) invalidate()
    if(vDom != wasNoValue) Nil else {
      until = Long.MaxValue
      val nextDom = child("root", RootElement(rootAttributes), rootView.view(hashForView)).asInstanceOf[VPair].value
      val diffRes = diff.diff(vDom, nextDom)
      vDom = nextDom
      val diffCmd = diffRes.map(d=>("showDiff", jsonToString(d))).toList
      diffCmd :::
        (if(hashFromAlien==hashForView) Nil else ("relocateHash",hashForView) :: Nil)
    }
  }

  def interact(message: Option[DictMessage]): List[(String,String)] = {
    message.foreach{ m ⇒
      setLastMessage(m)
      switchSession(m)
      relocate(m)
      dispatch(m) //dispatches incoming message // can close / set refresh time
    }
    showToAlien()
  }


  private def setLastMessage(message: DictMessage) = rootAttributes =
    List("ackMessage"→List("ackMessage",message.value("X-r-connection"),message.value("X-r-index")))

  /*
  private lazy val PathSplit = """(.*)(/[^/]*)""".r
  private def view(pathPrefix: String, pathPostfix: String): List[ChildPair[_]] =
    Single.option(handlerLists.list(ViewPath(pathPrefix))).map(_(pathPostfix))
      .getOrElse(pathPrefix match {
        case PathSplit(nextPrefix,nextPostfix) =>
          view(nextPrefix,s"$nextPostfix$pathPostfix")
      })
  */

}

case class RootElement(conf: List[(String,List[String])]) extends VDomValue {
  def appendJson(builder: MutableJsonBuilder) = {
    builder.startObject()
    builder.append("tp").append("span")
    conf.foreach{ case (k,v) ⇒
      builder.append(k)
      builder.startArray()
      v.foreach(builder.append)
      builder.end()
    }
    builder.end()
  }
}

object ResolveValue {
  def apply(value: VDomValue, path: List[String]): Option[VDomValue] =
    if(path.isEmpty) Some(value) else Some(value).collect{
      case m: MapVDomValue => m.pairs.collectFirst{
        case pair if pair.jsonKey == path.head => apply(pair.value, path.tail)
      }.flatten
    }.flatten
}