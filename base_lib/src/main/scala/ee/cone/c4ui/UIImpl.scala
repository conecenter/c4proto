package ee.cone.c4ui

import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor_branch.BranchProtocol.N_RestPeriod
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor._
import ee.cone.c4actor_branch.BranchTypes.BranchResult
import ee.cone.c4actor_branch.{BranchOperations, BranchTask}
import ee.cone.c4assemble.Types.{Each, Values}
import ee.cone.c4assemble.{by, c4assemble}
import ee.cone.c4di.c4
import ee.cone.c4gate.{Metric, MetricLabel, MetricsFactory}
import ee.cone.c4vdom.Types.{VDomKey, ViewRes}
import ee.cone.c4vdom._
import ee.cone.c4vdom_impl.SeedVDomValue

@c4("UICompApp") final class DefaultUntilPolicy(
  viewRestPeriodProvider: ViewRestPeriodProvider, seedFactory: SeedFactory
) extends UntilPolicy {
  def wrap(view: Context=>ViewRes): Context=>ViewRes = local => {
    val restPeriod = ViewRestPeriodKey.of(local).getOrElse(viewRestPeriodProvider.get(local))
    val res = view(ViewRestPeriodKey.set(Option(restPeriod))(local))
    val branchKey = CurrentBranchKey.of(local)
    seedFactory.create("until", N_RestPeriod(branchKey,restPeriod.valueMillis)) ::: res
  }
}

@c4("UICompApp") final class SeedFactoryImpl(
  branchOperations: BranchOperations, childFactory: ChildPairFactory
) extends SeedFactory {
  def create(key: VDomKey, value: Product): ViewRes =
    childFactory[OfDiv](key: VDomKey, SimpleSeedElement(branchOperations.toSeed(value)), Nil) :: Nil
}

case class SimpleSeedElement(seed: BranchResult) extends SeedVDomValue {
  def appendJson(builder: MutableJsonBuilder): Unit = {
    builder.startObject()
    builder.append("tp").append("span")
    builder.end()
  }
}

@c4("UICompApp") final class DynamicViewRestPeriodProvider() extends ViewRestPeriodProvider {
  def get(local: Context): ViewRestPeriod = {
    val state = VDomStateKey.of(local).get
    //val age = System.currentTimeMillis() - state.startedAtMillis
    //val allowedMaking = age / 10
    //DynamicViewRestPeriod(Math.max(state.wasMakingViewMillis - allowedMaking, 500))
    //val age = System.currentTimeMillis() - state.startedAtMillis
    //DynamicViewRestPeriod(Math.max(Math.min(age / 4, state.wasMakingViewMillis * 10 - age), 500))
    val default = 500L
    DynamicViewRestPeriod(Math.max(state.wasMakingViewMillis.stable * 10, default))
  }
}

@c4("UICompApp") final class ViewRestMetricsFactory(
  getAggrRestPeriod: GetByPK[AggrRestPeriod],
) extends MetricsFactory with LazyLogging {
  def measure(local: Context): List[Metric] =
    for{
      aggr <- getAggrRestPeriod.ofA(local).values.toList.sortBy(_.location)
      labels = List(MetricLabel("location", aggr.location))
      values = aggr.periods.map(_.value)
      metric <- List(
        Metric("c4view_rest_period_sum", labels, values.sum),
        Metric("c4view_rest_period_max", labels, values.max),
      )
    } yield {
      logger.debug(s"${metric}")
      metric
    }
}

case class AggrRestPeriod(location: String, periods: List[N_RestPeriod])

@c4assemble("UICompApp") class ViewRestAssembleBase {
  type BranchKey = SrcId
  type Location = SrcId

  def mapToRestPeriod(
    key: SrcId,
    task: Each[BranchTask]
  ): Values[(BranchKey, N_RestPeriod)] = task.product match {
    case s: N_RestPeriod => List(s.branchKey -> s)
    case _ => Nil
  }

  def mapByLocation(
    key: SrcId,
    @by[BranchKey] periods: Values[N_RestPeriod],
    task: Each[FromAlienTask]
  ): Values[(Location,N_RestPeriod)] = {
    val Word = """(\w*).*""".r
    periods.minByOption(_.value).map{ period =>
      val Word(word) = task.locationHash
      word -> period
    }.toList
  }

  def joinByLocation(
    key: SrcId,
    @by[Location] periods: Values[N_RestPeriod],
  ): Values[(SrcId,AggrRestPeriod)] =
    List(WithPK(AggrRestPeriod(key,periods.sortBy(_.branchKey).toList)))
}

@c4("UICompApp") final class ViewFailedImpl extends ViewFailed {
  def of(local: Context): Boolean = VDomStateKey.of(local).exists(_.failed)
}
