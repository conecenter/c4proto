package ee.cone.c4actor

import ee.cone.c4actor.Types.SrcId
import ee.cone.c4assemble.Types.ReadModel
import ee.cone.c4assemble._

case class AssembleProfilingEnable(srcId: SrcId)
case class AssembleProfilingResult(srcId: SrcId, counters: List[AssembleProfilingCounter])
case class AssembleProfilingCounter(joinKey: JoinKey, keyCount: Long, objCount: Long)

case class AssembleFinalProfilerImpl(
  indexUtil: IndexUtil,
  origKeyFactory: OrigKeyFactory,
  actorName: String
)(
  enableWKey: JoinKey = origKeyFactory.rawKey(classOf[AssembleProfilingEnable].getName)
) extends AssembleFinalProfiler {
  def transform(transition: WorldTransition) = {
    val world = transition.result
    if(!indexUtil.nonEmpty(enableWKey.of(world),actorName)) transition else {
      val resWKey = origKeyFactory.rawKey(classOf[AssembleProfilingResult].getName)
      val resIndex = resWKey.of(world)
      def gatherDiffs(transition: WorldTransition): List[ReadModel] =
        transition.diff :: transition.prev.fold(Nil:List[ReadModel])(gatherDiffs)
      val worldDiffs = gatherDiffs(transition)
      val currentCounters = for{
        (value:AssembleProfilingResult) ← indexUtil.getValues(resIndex,actorName,"").toList
        counter ← value.counters
      } yield counter

      val newCounters = for {
        worldDiff ← worldDiffs
        (joinKey: JoinKey, indexDiff) ← worldDiff
        keys = indexUtil.keySet(indexDiff)
        nonStrictCount = keys.map(key⇒indexUtil.getValues(indexDiff,key,"").length).sum
      } yield AssembleProfilingCounter(joinKey,keys.size,nonStrictCount)
      val nextCounters = newCounters ::: currentCounters

      val nextAcc = AssembleProfilingResult(actorName, nextCounters)
      val nextResIndex = indexUtil.result(actorName,nextAcc,1)
      transition.copy(result = world + (resWKey → nextResIndex))
    }
  }
}

/*val nextCounters = (currentCounters ++ newCounters).groupBy(_.joinKey).map{
        case (joinKey, counters) ⇒
          AssembleProfilingCounter(
            joinKey,
            counters.map(_.keyCount).sum,
            counters.map(_.objCount).sum,
          )
      }.toList.sortBy(_.joinKey)*/