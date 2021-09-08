package ee.cone.c4vdom_impl

import ee.cone.c4vdom.{VDomValue, _}

import Function.chain
import scala.annotation.tailrec

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

object VDomResolverImpl extends VDomResolver {
  def resolve(pathStr: String): Option[Resolvable] => Option[Resolvable] = from => {
    val "" :: path = pathStr.split("/").toList
    path.foldLeft(from) { (value, name) =>
      val res = value.flatMap {
        case m: ResolvingVDomValue => m.resolve(name)
        case _ =>
          None
      }
      //println(s"-- $value [$name] $res")
      res
    }
  }
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

  private def reset(state: State): State = vDomStateKey.modify(_.map(
    st=>st.copy(value = wasNoValue, until = 0)
  ))(state)
  private def init(state: State): State = vDomStateKey.modify(_.orElse(
    Option(VDomState(wasNoValue,0,System.currentTimeMillis(),0))
  ))(state)

  private def pathHeader: VDomMessage => String = _.header("x-r-vdom-path")
  //dispatches incoming message // can close / set refresh time
  private def dispatch(exchange: VDomMessage, state: State): State = {
    val path = pathHeader(exchange)
    if(path.isEmpty) state else (VDomResolverImpl.resolve(path)(vDomStateKey.of(state).map(_.value)) match {
      case Some(v: Receiver[_]) => v.receive(exchange)
      case v =>
        println(s"$path ($v) can not receive")
        identity[State] _
    }).asInstanceOf[State=>State](state)
  }

  //todo invalidate until by default

  def receive: Handler = m=>state=>doReceive(m,state)
  private def doReceive(m: VDomMessage, state: State): State = {
    chain(List(init(_),dispatch(m,_),toAlien(m,_)))(state)
  }

  private def diffSend(prev: VDomValue, send: sender.Send, state: State): State =
    if(send.isEmpty) state else {
      val next = vDomStateKey.of(state).get.value
      val diffTree = diff.diff(prev, next)
      if(diffTree.isEmpty) state else {
        val diffStr = jsonToString(diffTree.get)
        send.get("showDiff",s"${sender.branchKey} $diffStr")(state)
      }
    }

  private def toAlien(exchange: VDomMessage, state: State): State = {
    val vState = vDomStateKey.of(state).get
    val (keepTo,freshTo) = sender.sending(state)
    if(keepTo.isEmpty && freshTo.isEmpty){
      reset(state) //orElse in init bug
    }
    else if(
      vState.value != wasNoValue &&
      vState.until > System.currentTimeMillis &&
      (exchange.header("x-r-redraw") match {
        case "1" => false
        case "" => pathHeader(exchange).isEmpty
      }) &&
      freshTo.isEmpty
    ) state
    else chain[State](Seq(
      reset(_), // need to remove prev DomState before review to avoid leak: local-vdom-el-action-local
      reView(_),
      diffSend(vState.value, keepTo, _),
      diffSend(wasNoValue, freshTo, _)
    ))(state)
  }

  private def reView(state: State): State = {
    val startedAt = System.currentTimeMillis
    val (until,viewRes) = vDomUntil.get(view.view(state))
    val wasMakingViewMillis = System.currentTimeMillis - startedAt
    val vPair = child("root", RootElement(sender.branchKey), viewRes).asInstanceOf[VPair]
    val nextDom = vPair.value
    vDomStateKey.modify(_.map(st=>st.copy(
      value=nextDom, until=until,
      wasMakingViewMillis = st.wasMakingViewMillis+wasMakingViewMillis
    )))(state)
  }
/*
  def seeds: State => List[(String,Product)] = state => {
    //println(vDomStateKey.of(state).get.value.getClass)
    gatherSeeds(Nil, Nil, vDomStateKey.of(state).get.value)
  }
  private def gatherSeeds(
    acc: List[(String,Product)], path: List[String], value: VDomValue
  ): List[(String,Product)] = value match {
    case n: MapVDomValue =>
      (acc /: n.pairs)((acc,pair)=>gatherSeeds(acc, pair.jsonKey::path, pair.value))
    case n: SeedVDomValue => (path.reverse.map(e=>s"/$e").mkString,n.seed) :: acc
     //case UntilElement(until) => acc.copy(until = Math.min(until, acc.until))
    case _ => acc
  }*/
  /*private def gatherSeedsValue(value: VDomValue): Product = value match {
    case n: MapVDomValue =>
      val subRes = gatherSeedsPairs(n.pairs,Nil)
      if(subRes.nonEmpty) GatheredSeeds(subRes) else NoSeeds
    case n: SeedVDomValue => n.seed
    case _ => NoSeeds
  }*/

  type Seeds = List[(String,Product)]
  def seeds: State => Seeds = state =>
    gatherSeedsFinal(Nil, gatherSeedsPair("",vDomStateKey.of(state).get.value,Nil), Nil)
  @tailrec private def gatherSeedsPairs(from: List[VPair], res: Seeds): Seeds =
    if(from.isEmpty) res else gatherSeedsPairs(from.tail, gatherSeedsPair(from.head.jsonKey,from.head.value,res))
  private def gatherSeedsPair(key: String, value: VDomValue, res: Seeds ): Seeds = value match {
    case n: MapVDomValue =>
      val subRes = gatherSeedsPairs(n.pairs,Nil)
      if(subRes.nonEmpty) (key,GatheredSeeds(subRes)) :: res else res
    case n: SeedVDomValue => (key,n.seed) :: res
    case _ => res
  }
  private def gatherSeedsFinal(path: List[String], from: Seeds, res: Seeds): Seeds =
    if(from.isEmpty) res else {
      val (key,value) = from.head
      val subPath = key :: path
      gatherSeedsFinal(path, from.tail, value match {
        case GatheredSeeds(pairs) => gatherSeedsFinal(subPath,pairs,res)
        case seed => (subPath.reverse.mkString("/"),seed) :: res
      })
    }


  //val relocateCommands = if(vState.hashFromAlien==vState.hashTarget) Nil
  //else List("relocateHash"->vState.hashTarget)

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

case class GatheredSeeds(pairs: List[(String,Product)])


case class RootElement(branchKey: String) extends VDomValue {
  def appendJson(builder: MutableJsonBuilder): Unit = {
    builder.startObject()
    builder.append("tp").append("span")
    /*
    builder.append("ref");{
      builder.startArray()
      builder.append("root")
      builder.append(branchKey)
      builder.end()
    }*/
    builder.end()
  }
}
