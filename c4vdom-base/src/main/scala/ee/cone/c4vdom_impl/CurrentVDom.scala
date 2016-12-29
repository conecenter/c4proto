package ee.cone.c4vdom_impl

import java.util.Base64

import ee.cone.c4vdom._

/*trait View {
  def view(path: String): List[ChildPair[_]]
}*/

trait Lens[C,I] {
  def of(container: C): I
  def transform(f: I⇒I)(container: C): C
}

class CurrentVDomImpl[State](
  diff: Diff,
  jsonToString: JsonToString,
  wasNoValue: WasNoVDomValue,
  child: ChildPairFactory,
  vDomStateKey: Lens[State,VDomState]
) extends CurrentVDom[State] {
  //def until(value: Long) = if(value < until) until = value
  private def relocate(state: State, message: Map[String,String]): Option[State] =
    for(hash ← message.get("X-r-location-hash") if hash != vDomStateKey.of(state).hashFromAlien)
      yield vDomStateKey.transform(_.copy(hashFromAlien = hash, hashTarget = hash))(state)
  //dispatches incoming message // can close / set refresh time
  private def dispatch(state: State, message: Map[String,String]): Option[State] =
    for(pathStr <- message.get("X-r-vdom-path")) yield {
      if(vDomStateKey.of(state).until <= 0) throw new Exception("invalid VDom")
      val path = pathStr.split("/").toList match {
        case "" :: parts => parts
        case _ => Never()
      }
      ((message.get("X-r-action"), ResolveValue(vDomStateKey.of(state).value, path)) match {
        case (Some("click"), Some(v: OnClickReceiver[_])) => v.onClick.get(state)
        case (Some("change"), Some(v: OnChangeReceiver[_])) =>
          val decoded = UTF8String(Base64.getDecoder.decode(message("X-r-vdom-value-base64")))
          v.onChange.get(state,decoded)
        case v => throw new Exception(s"$path ($v) can not receive $message")
      }).asInstanceOf[State]
    }
  private def setLastMessage(state: State, message: Map[String,String]): Option[State] =
    for(connection ← message.get("X-r-connection"); index ← message.get("X-r-index"))
      yield vDomStateKey.transform(_.copy(ackFromAlien = connection :: index :: Nil))(state)
  private def handlers =
    List[(State,Map[String,String])⇒Option[State]](setLastMessage,relocate,dispatch)
  def fromAlien(state: State, message: Map[String,String]): State =
    (state /: handlers)((state,f)⇒f(state,message).getOrElse(state))
  def toAlien(state: State)(view: ()⇒List[ChildPair[_]]): (State,List[(String,String)]) = {
    val vState = vDomStateKey.of(state)
    if(
      vState.value != wasNoValue &&
        vState.until > System.currentTimeMillis &&
        vState.hashOfLastView == vState.hashFromAlien
    ) (state,Nil) else {
      val rootAttributes = List("ackMessage" → ("ackMessage" :: vState.ackFromAlien))
      val rootElement = RootElement(rootAttributes)
      val nextDom = child("root", rootElement, view()) //state.hashFromAlien
        .asInstanceOf[VPair].value
      val nextState = vDomStateKey.transform(_.copy(
          value=nextDom,
          until=Long.MaxValue,
          hashOfLastView=vState.hashFromAlien
      ))(state)
      val diffTree = diff.diff(vState.value, vDomStateKey.of(nextState).value)
      val diffCommands = diffTree.map(d=>("showDiff", jsonToString(d))).toList
      val relocateCommands = if(vState.hashFromAlien==vState.hashTarget) Nil
        else List("relocateHash"→vState.hashTarget)
      (nextState, diffCommands ::: relocateCommands)
    }
  }


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