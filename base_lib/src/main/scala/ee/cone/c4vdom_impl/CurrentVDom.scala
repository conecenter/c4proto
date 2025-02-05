package ee.cone.c4vdom_impl

import ee.cone.c4vdom._
import scala.annotation.tailrec

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

case class CalcDiffRes(value: VDomValue, needSnapshot: Long, failed: Boolean, diff: String, snapshot: String)
class VDomHandlerImpl(
  diff: Diff,
  fixDuplicateKeys: FixDuplicateKeys,
  jsonToString: JsonToString,
  wasNoValue: WasNoVDomValue,
) extends VDomHandler {
  def preView: Option[VDomState]=>Option[PreViewResult] = {
    case None => Option(PreViewResult(VDomState(wasNoValue,0,System.currentTimeMillis(),MakingViewStats(0,Nil,0),failed=false,0L), wasNoValue, System.currentTimeMillis))
    case Some(st) if st.value != wasNoValue && st.until > System.currentTimeMillis => None
    case Some(st) => Option(PreViewResult(st.copy(value = wasNoValue, until = 0), st.value, System.currentTimeMillis))
    // need to remove prev DomState before review to avoid leak: local-vdom-el-action-local
  }

  def postView(preViewRes: PreViewResult, nextDom: VDomValue): PostViewResult = {
    val st = preViewRes.clean
    val res = calcDiff(preViewRes.prev, nextDom, st.needSnapshot, st.failed)
    val seeds = gatherSeedsFinal(Nil, gatherSeedsPair("",res.value,Nil), Nil).map(_._2)
    val wasMakingViewMillis = addMakingViewStat(st.wasMakingViewMillis,preViewRes.startedAt,System.currentTimeMillis)
    val nSt = VDomState(res.value, 0L, st.startedAtMillis, wasMakingViewMillis, res.failed, res.needSnapshot)
    PostViewResult(nSt, seeds, res.diff, res.snapshot)
  }

  private def calcDiff(
    prev: VDomValue, next: VDomValue, needSnapshot: Long, failed: Boolean, left: Int = 50
  ): CalcDiffRes = try {
    val diffStr = diff.diff(prev, next).fold("")(jsonToString(_))
    val snapshotStr = if(needSnapshot < 0) "" else jsonToString(diff.diff(wasNoValue, next).get)
    val willNeedSnapshot = if(snapshotStr.nonEmpty) - snapshotStr.length else needSnapshot + diffStr.length
    new CalcDiffRes(next, willNeedSnapshot, failed, diffStr, snapshotStr)
  } catch {
    case error: DuplicateKeysException if left > 0 =>
      if(!failed) println(error.getMessage)
      calcDiff(prev, fixDuplicateKeys.fix(error, next), needSnapshot, failed = true, left = left - 1)
  }

  private def addMakingViewStat(was: MakingViewStats, startedAt: Long, now: Long): MakingViewStats = {
    val measured = now - startedAt
    val sum = was.sum + measured
    val (moreRecent,lessRecent) = was.recent.splitAt(2)
    val recent = MakingViewStat(now,measured) :: moreRecent ::: lessRecent.filter(m => now-m.at < 10000)
    val lowered = Math.min(was.stable, recent.maxBy(_.value).value)
    val stable = if(recent.size < 3) lowered else Math.max(lowered,recent.minBy(_.value).value)
    //println(s"AAA ${stable} ${recent.size}")
    MakingViewStats(sum,recent,stable)
  }

  private type Seeds = List[(String,Product)]
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
}

case class GatheredSeeds(pairs: List[(String,Product)])
