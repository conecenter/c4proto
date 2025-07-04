package ee.cone.c4ui

import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor.QProtocol.S_Updates
import ee.cone.c4actor_branch.BranchProtocol.{N_RestPeriod, S_BranchResult}
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4actor._
import ee.cone.c4actor_branch.{BranchOperations, _}
import ee.cone.c4assemble.Types.{Each, Values}
import ee.cone.c4assemble.{Assemble, assemble, by, c4assemble}
import ee.cone.c4di.{c4, c4multi}
import ee.cone.c4gate.{Metric, MetricLabel, MetricsFactory}
import ee.cone.c4proto.{HasId, ProtoAdapter}
import ee.cone.c4vdom.Types.{VDomKey, ViewRes}
import ee.cone.c4vdom._
import ee.cone.c4vdom_impl.SeedVDomValue
import okio.ByteString

//case object RelocateKey extends WorldKey[String]("")
//  with VDomLens[World, String]

@c4assemble("UICompApp") class VDomAssembleBase(
  factory: VDomBranchHandlerFactory
){
  def joinBranchHandler(
    key: SrcId,
    task: Each[BranchTask],
    view: Each[View]
  ): Values[(SrcId,BranchHandler)] =
    List(WithPK(factory.create(task.branchKey, VDomBranchSender(task),view)))
}

case class VDomBranchSender(pass: BranchTask) extends VDomSender[Context] {
  def branchKey: String = pass.branchKey
  def sending: Context => (Send,Send) = pass.sending
}

case class VDomMessageImpl(message: BranchMessage) extends VDomMessage {
  override def header: String => String = message.header
  override def body: ByteString = message.body
}

@c4multi("UICompApp") final case class VDomBranchHandler(branchKey: SrcId, sender: VDomSender[Context], view: VDomView[Context])(
  vDomHandlerFactory: VDomHandlerFactory,
  vDomUntilImpl: VDomUntilImpl
) extends BranchHandler {
  def vHandler: Receiver[Context] =
      vDomHandlerFactory.create(sender,view,vDomUntilImpl,VDomStateKey)
  def exchange: BranchMessage => Context => Context =
    message => local => {
      val vDomMessage = VDomMessageImpl(message)
      //println(s"act ${message("x-r-action")}")
      val handlePath = vDomMessage.header("x-r-vdom-path")
      Function.chain(Seq(
        CurrentBranchKey.set(branchKey),
        CurrentPathKey.set(handlePath),
        vHandler.receive(vDomMessage)
      ))(local)
    }
  def seeds: Context => List[S_BranchResult] =
    local => VDomStateKey.of(local).get.seeds.collect{
      case (k: String, r: S_BranchResult) => r.copy(position=k)
    }
}

////

@c4("UICompApp") final class VDomUntilImpl(
  qAdapterRegistry: QAdapterRegistry,
)(
  adapter: ProtoAdapter[N_RestPeriod] with HasId =
    qAdapterRegistry.byName(classOf[N_RestPeriod].getName)
    .asInstanceOf[ProtoAdapter[N_RestPeriod] with HasId],
) extends VDomUntil {
  //registry.byId.get(seed.valueTypeId).map(_.decode(seed.value.toByteArray))
  def get(seeds: Seq[Product]): Long =
    seeds.collect{ case u: S_BranchResult if u.valueTypeId == adapter.id => adapter.decode(u.value).value } match {
      case l if l.isEmpty => 0L
      case l => l.min
    }
}

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

case class SimpleSeedElement(seed: S_BranchResult) extends SeedVDomValue {
  def appendJson(builder: MutableJsonBuilder): Unit = {
    builder.startObject()
    builder.append("tp").append("span")
    builder.append("style").startObject(); {
      builder.append("display").append("none")
      builder.end()
    }
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
      logger.info(s"${metric}")
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
