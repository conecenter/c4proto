package ee.cone.c4vdom_impl

import java.util.Base64

import ee.cone.c4vdom._

import Function.chain
import scala.collection.immutable.Queue

class VDomHandlerFactoryImpl(
  diff: Diff,
  jsonToString: JsonToString,
  wasNoValue: WasNoVDomValue,
  child: ChildPairFactory
) extends VDomHandlerFactory {
  def create[State](
    sender: VDomSender[State],
    view: VDomView[State],
    vDomUntil: VDomUntil,
    vDomStateKey: VDomLens[State,Option[VDomState]]
  ): VDomHandler[State] =
    VDomHandlerImpl(sender,view)(diff,jsonToString,wasNoValue,child,vDomUntil,vDomStateKey)
}

case class VDomHandlerImpl[State](
  sender: VDomSender[State],
  view: VDomView[State]
)(
  diff: Diff,
  jsonToString: JsonToString,
  wasNoValue: WasNoVDomValue,
  child: ChildPairFactory,
  vDomUntil: VDomUntil,

  vDomStateKey: VDomLens[State,Option[VDomState]]
  //relocateKey: VDomLens[State,String]
) extends VDomHandler[State] {

  private def init: Handler = _ ⇒
    vDomStateKey.modify(_.orElse(Option(VDomState(wasNoValue,0,Set.empty,Map.empty))))

  //dispatches incoming message // can close / set refresh time
  private def dispatch: Handler = get ⇒ state ⇒ if(get("X-r-action").isEmpty) state else {
    val pathStr = get("X-r-vdom-path")
    val path = pathStr.split("/").toList match {
      case "" :: parts => parts
      case _ => Never()
    }
    val decoded = UTF8String(Base64.getDecoder.decode(get("X-r-vdom-value-base64")))
    ((get("X-r-action"), ResolveValue(vDomStateKey.of(state).get.value, path)) match {
      case ("click", Some(v: OnClickReceiver[_])) => v.onClick.get(decoded)
      case ("change", Some(v: OnChangeReceiver[_])) => v.onChange.get(decoded)
      case v => throw new Exception(s"$path ($v) can not receive")
    }).asInstanceOf[State=>State](state)
  }

  //todo invalidate until by default
  private def relocate: Handler = exchange ⇒ state ⇒ state /*relocateKey.of(state) match {
    case "" ⇒ state
    case hash ⇒ state
    //todo pass to parent branch or alien
      /*
      task.directSessionKey.map(exchange.send(_, "relocateHash", hash)).getOrElse(???)
        .andThen(relocateKey.set(""))(state)*/
  }*/

  def exchange: Handler =
    m ⇒ chain(Seq(init,dispatch,relocate,ackChange,toAlien).map(_(m)))


  private def diffSend(prev: VDomValue, next: VDomValue, sessionKeys: Set[String]): State ⇒ State = {
    if(sessionKeys.isEmpty) return identity[State]
    val diffTree = diff.diff(prev, next)
    if(diffTree.isEmpty) return identity[State]
    val diffStr = jsonToString(diffTree.get)
    chain(sessionKeys.map(sender.send(_,"showDiff",sender.branchKey,diffStr)).toSeq)
  }

  private def toAlien: Handler = exchange ⇒ state ⇒ {
    val vState = vDomStateKey.of(state).get
    val newSessionKeys = sender.sessionKeys(state)
    val(keepTo,freshTo) = newSessionKeys.partition(vState.sessionKeys)
    if(newSessionKeys.isEmpty) init(exchange)(state)
    else if(
      vState.value != wasNoValue &&
      vState.until > System.currentTimeMillis &&
      freshTo.isEmpty
    ) state
    else {
      val (until,viewRes) = vDomUntil.get(view.view(state))
      val vPair = child("root", RootElement, viewRes).asInstanceOf[VPair]
      val nextDom = vPair.value
      vDomStateKey.set(Option(VDomState(nextDom, until, newSessionKeys, Map.empty)))
        .andThen(diffSend(vState.value, nextDom, keepTo))
        .andThen(diffSend(wasNoValue, nextDom, freshTo))
        .andThen(chain(vState.ackChanges.map{ case(sessionKey,index) ⇒
          sender.send(sessionKey,"ackChange",sender.branchKey,index)
        }.toSeq))(state)
    }
  }

  private def ackChange: Handler = get ⇒ if(get("X-r-action") == "change") {
    val sessionKey = get("X-r-session")
    val index = get("X-r-index")
    vDomStateKey.modify(_.map(vState ⇒
      vState.copy(ackChanges=vState.ackChanges + (sessionKey → index))
    ))
  } else identity[State]

  def seeds: State ⇒ List[(String,Product)] =
    state ⇒ gatherSeeds(Nil, Nil, vDomStateKey.of(state).get.value)
  private def gatherSeeds(
    acc: List[(String,Product)], path: List[String], value: VDomValue
  ): List[(String,Product)] = value match {
    case n: MapVDomValue ⇒
      (acc /: n.pairs)((acc,pair)⇒gatherSeeds(acc, pair.jsonKey::path, pair.value))
    case SeedElement(seed) ⇒ (path.reverse.map(e⇒s"/$e").mkString,seed) :: acc
    //case UntilElement(until) ⇒ acc.copy(until = Math.min(until, acc.until))
    case _ ⇒ acc
  }





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

object ResolveValue {
  def apply(value: VDomValue, path: List[String]): Option[VDomValue] =
    if(path.isEmpty) Some(value) else Some(value).collect{
      case m: MapVDomValue => m.pairs.collectFirst{
        case pair if pair.jsonKey == path.head => apply(pair.value, path.tail)
      }.flatten
    }.flatten
}
