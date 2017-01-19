package ee.cone.c4vdom_impl

import java.util.Base64

import ee.cone.c4vdom._

class CurrentVDomImpl[State](
  diff: Diff,
  jsonToString: JsonToString,
  wasNoValue: WasNoVDomValue,
  child: ChildPairFactory,

  vDomStateKey: VDomLens[State,Option[VDomState]],
  relocateKey: VDomLens[State,String]
) extends CurrentVDom[State] {
  private type Message = Map[String,String]
  private type Handler = Message ⇒ State ⇒ State

  private def init: State ⇒ State =
    vDomStateKey.modify(_.orElse(Option(VDomState(wasNoValue,0,Set.empty))))

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

  private def relocate(task: BranchTask[State]): Handler = message ⇒ state ⇒ relocateKey.of(state) match {
    case "" ⇒ state
    case hash ⇒
    //todo pass to parent branch or alien
      task.directSessionKey.map(task.message(_, "relocateHash", hash)).getOrElse(???)
        .andThen(relocateKey.set(""))(state)
  }

  def activate: BranchTask[State] ⇒ State ⇒ State = task ⇒ (identity[State] _ /: (
    if(task.getPosts.isEmpty) List(init,toAlien(task))
    else for(m ← task.getPosts; f ← List(init, dispatch(m), relocate(task)(m), toAlien(task), ackChange(task)(m))) yield f
  ))((a,b)⇒ a andThen b)

  private def diffSend(prev: VDomValue, next: VDomValue, sessionKeys: Set[String], task: BranchTask[State]): State ⇒ State = {
    if(sessionKeys.isEmpty) return identity[State]
    val diffTree = diff.diff(prev, next)
    if(diffTree.isEmpty) return identity[State]
    val diffStr = jsonToString(BranchDiff("/connection", task.branchKey,diffTree.get))
    val sends = sessionKeys.map(task.message(_,"showDiff",diffStr))
    (identity[State] _ /: sends)(_ andThen _)
  }

  private def toAlien(task: BranchTask[State]): State ⇒ State = state ⇒ {
    val vState = vDomStateKey.of(state).get
    if(
      vState.value != wasNoValue &&
        vState.until > System.currentTimeMillis
    ) state
    /*else if(task.sessionKeys.isEmpty){
      task.updateResult(Nil)(state)
    }*/
    else {
      val viewRes = task.view(state)
      val vPair = child("root", RootElement, viewRes).asInstanceOf[VPair]
      val GatherResult(until,seeds) = gather(GatherResult(0,Nil),vPair)
      val nextDom = vPair.value
      val(keepTo,freshTo) = task.sessionKeys.partition(vState.sessionKeys)
      vDomStateKey.set(Option(VDomState(nextDom, until, task.sessionKeys)))
        .andThen(diffSend(vState.value, nextDom, keepTo, task))
        .andThen(diffSend(wasNoValue, nextDom, freshTo, task))
        .andThen(task.updateResult(seeds))(state)
    }
  }

  private def ackChange(task: BranchTask[State]): Handler = message ⇒ if(message("X-r-action") == "change") {
    val sessionKey = message("X-r-session")
    val branchKey = message("X-r-branch")
    val index = message("X-r-index")
    task.message(sessionKey,"ackChange",s"$branchKey $index")
  } else identity[State]

  private def gather(acc: GatherResult, pair: VPair): GatherResult = pair.value match {
    case n: MapVDomValue ⇒ (acc /: n.pairs)(gather)
    case SeedElement(seed) ⇒ acc.copy(seeds = seed :: acc.seeds)
    case UntilElement(until) ⇒ acc.copy(until = Math.min(until, acc.until))
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

case class GatherResult(until: Long, seeds: List[Product])

case class UntilElement(until: Long) extends VDomValue {
  def appendJson(builder: MutableJsonBuilder): Unit = {
    builder.startObject()
    builder.append("tp").append("span")
    builder.end()
  }
}


case class SeedElement(seed: Product) extends VDomValue {
  def appendJson(builder: MutableJsonBuilder): Unit = {
    builder.startObject()
    builder.append("tp").append("Seed")
    builder.append("hash").append(seed.productElement(0).toString)
    builder.end()
  }
}

case object RootElement extends VDomValue {
  def appendJson(builder: MutableJsonBuilder): Unit = {
    builder.startObject()
    builder.append("tp").append("span")
    builder.end()
  }
}

case class BranchDiff(postURL: String, key: String, value: VDomValue) extends VDomValue {
  def appendJson(builder: MutableJsonBuilder): Unit = {
    builder.startObject()
    builder.append("postURL").append(postURL)
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