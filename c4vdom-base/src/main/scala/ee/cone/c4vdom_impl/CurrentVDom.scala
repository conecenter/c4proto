package ee.cone.c4vdom_impl

import java.util.Base64

import ee.cone.c4vdom._

class CurrentVDomImpl[State](
  diff: Diff,
  jsonToString: JsonToString,
  wasNoValue: WasNoVDomValue,
  child: ChildPairFactory,
  view: RootView[State],
  vDomStateKey: VDomLens[State,Option[VDomState]]
) extends CurrentVDom[State] {

  private type Message = String ⇒ Option[String]
  private type Handler = Message ⇒ State ⇒ Option[State]
  //def until(value: Long) = if(value < until) until = value
  private def relocate: Handler = message ⇒ state ⇒
    for(hash ← message("X-r-location-hash") if hash != vDomStateKey.of(state).get.hashFromAlien)
      yield vDomStateKey.transform(vStateOpt ⇒ Option(vStateOpt.get.copy(
        hashFromAlien = hash, hashTarget = hash
      )))(state)
  //dispatches incoming message // can close / set refresh time
  private def dispatch: Handler = message ⇒ state ⇒
    for(pathStr <- message("X-r-vdom-path")) yield {
      if(vDomStateKey.of(state).get.until <= 0) throw new Exception("invalid VDom")
      val path = pathStr.split("/").toList match {
        case "" :: parts => parts
        case _ => Never()
      }
      ((message("X-r-action"), ResolveValue(vDomStateKey.of(state).get.value, path)) match {
        case (Some("click"), Some(v: OnClickReceiver[_])) => v.onClick.get
        case (Some("change"), Some(v: OnChangeReceiver[_])) =>
          val decoded = UTF8String(Base64.getDecoder.decode(message("X-r-vdom-value-base64").get))
          v.onChange.get(decoded)
        case v => throw new Exception(s"$path ($v) can not receive $message")
      }).asInstanceOf[State=>State](state)
    }
  private def handleLastMessage: Handler = message ⇒ state ⇒
    for(connection ← message("X-r-connection"); index ← message("X-r-index"))
      yield vDomStateKey.transform(vStateOpt ⇒ Option(vStateOpt.get.copy(
        ackFromAlien = connection :: index :: Nil
      )))(state)
  private def handlers = List[Handler](handleLastMessage,relocate,dispatch)
  private def init(state: State): State =
    if(vDomStateKey.of(state).nonEmpty) state
    else vDomStateKey.transform(_⇒Option(VDomState(wasNoValue,0,"","","",Nil)))(state)


  def fromAlien: (String⇒Option[String]) ⇒ State ⇒ State =
    message ⇒ state ⇒ (init(state) /: handlers)((state,f)⇒f(message)(state).getOrElse(state))

  def toAlien: (State) ⇒ (State, List[(String, String)]) = state ⇒
    Option(state).map(init).map{ state ⇒
      val vState = vDomStateKey.of(state).get
      if(
        vState.value != wasNoValue &&
          vState.until > System.currentTimeMillis &&
          vState.hashOfLastView == vState.hashFromAlien
      ) (state,Nil) else {
        val rootAttributes =
          if(vState.ackFromAlien.isEmpty) Nil
          else List("ackMessage" → ("ackMessage" :: vState.ackFromAlien))
        val rootElement = RootElement(rootAttributes)
        val (viewRes, until) = view.view(state)
        val nextDom = child("root", rootElement, viewRes)
          .asInstanceOf[VPair].value
        val nextState = vDomStateKey.transform(vStateOpt ⇒ Option(vStateOpt.get.copy(
          value=nextDom, until=until, hashOfLastView=vState.hashFromAlien
        )))(state)
        val diffTree = diff.diff(vState.value, vDomStateKey.of(nextState).get.value)
        val diffCommands = diffTree.map(d=>("showDiff", jsonToString(d))).toList
        val relocateCommands = if(vState.hashFromAlien==vState.hashTarget) Nil
        else List("relocateHash"→vState.hashTarget)
        (nextState, diffCommands ::: relocateCommands)
      }
    }.get



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