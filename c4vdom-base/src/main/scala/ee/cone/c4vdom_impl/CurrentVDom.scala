package ee.cone.c4vdom_impl

import java.util.Base64

import ee.cone.c4vdom._

import scala.collection.immutable.Queue

class CurrentVDomImpl[State](
  diff: Diff,
  jsonToString: JsonToString,
  wasNoValue: WasNoVDomValue,
  child: ChildPairFactory,
  view: RootView[State],
  vDomStateKey: VDomLens[State,Option[VDomState]],
  relocateKey: VDomLens[State,String],
  send: (String,String,String)⇒State⇒State
) extends CurrentVDom[State] {
  private type Message = Map[String,String]
  private type Handler = Message ⇒ State ⇒ State

  private def init: State ⇒ State =
    vDomStateKey.modify(_.orElse(Option(VDomState(wasNoValue,0,Nil))))

  //dispatches incoming message // can close / set refresh time
  private def dispatch: Handler = message ⇒ state ⇒ {
    val pathStr = message("X-r-vdom-path")
    if(vDomStateKey.of(state).get.until <= 0) throw new Exception("invalid VDom")
    val path = pathStr.split("/").toList match {
      case "" :: parts => parts
      case _ => Never()
    }
    ((message("X-r-action"), ResolveValue(vDomStateKey.of(state).get.value, path)) match {
      case ("click", Some(v: OnClickReceiver[_])) => v.onClick.get
      case ("change", Some(v: OnChangeReceiver[_])) =>
        val decoded = UTF8String(Base64.getDecoder.decode(message("X-r-vdom-value-base64")))
        v.onChange.get(decoded)
      case v => throw new Exception(s"$path ($v) can not receive $message")
    }).asInstanceOf[State=>State](state)
  }

  private def relocate: Handler = message ⇒ state ⇒ relocateKey.of(state) match {
    case "" ⇒ state
    case hash ⇒
      send(message("X-r-connection"), "relocateHash", hash)
        .andThen(relocateKey.set(""))(state)
  }

  def activate: (List[Map[String,String]],Set[String]) ⇒ State ⇒ State = (messages,connectionKeys) ⇒ (identity[State] /: (
    if(messages.isEmpty) List(init,toAlien(connectionKeys))
    else for(m ← messages; f ← List(init, dispatch(m), relocate(m), toAlien(connectionKeys), ackChange(m))) yield f
  ))((a,b)⇒ a andThen b)

  private def diffSend(prev: VDomValue, next: VDomValue, connectionKeys: Set[String]): State ⇒ State = {
    if(connectionKeys.isEmpty) return identity[State]
    val diffTree = diff.diff(prev, next)
    if(diffTree.isEmpty) return identity[State]
    val diffStr = jsonToString(BranchDiff(/*branchKey*/ ???,diffTree.get))
    val sends = connectionKeys.map(send(_,"showDiff",diffStr))
    (identity[State] /: sends)(_ andThen _)
  }

  private def toAlien(connectionKeys: Set[String]): State ⇒ State = state ⇒ {
    val vState = vDomStateKey.of(state).get
    if(
      vState.value != wasNoValue &&
        vState.until > System.currentTimeMillis
    ) state else {
      val (viewRes, until) = view.view(state)
      val nextDom = child("root", RootElement, viewRes).asInstanceOf[VPair].value
      val freshTo = connectionKeys -- vState.connectionKeys
      vDomStateKey.set(Option(VDomState(nextDom, until, connectionKeys)))
        .andThen(diffSend(vState.value, nextDom, vState.connectionKeys))
        .andThen(diffSend(wasNoValue, nextDom, freshTo))(state)
    }
  }

  private def ackChange: Handler = message ⇒ if(message("X-r-action") == "change") {
    val connectionKey = message("X-r-connection")
    val branchKey = message("X-r-branch")
    val index = message("X-r-index")
    send(connectionKey,"ackChange",s"$branchKey $index")
  } else identity[State]




  //val relocateCommands = if(vState.hashFromAlien==vState.hashTarget) Nil
  //else List("relocateHash"→vState.hashTarget)

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

case object RootElement extends VDomValue {
  def appendJson(builder: MutableJsonBuilder): Unit = {
    builder.startObject()
    builder.append("tp").append("span")
    builder.end()
  }
}

case class BranchDiff(key: String, value: VDomValue) extends VDomValue {
  def appendJson(builder: MutableJsonBuilder): Unit = {
    builder.startObject()
    builder.append("branchKey").append(key)
    builder.append("value")
    value.appendJson(builder)
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