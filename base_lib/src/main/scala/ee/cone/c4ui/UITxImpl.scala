package ee.cone.c4ui

import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor.Types.{LEvents, SrcId}
import ee.cone.c4actor._
import ee.cone.c4actor_branch._
import ee.cone.c4actor_branch.BranchProtocol.{N_BranchResult, N_RestPeriod}
import ee.cone.c4actor_branch.BranchTypes.BranchKey
import ee.cone.c4assemble.Types.{Each, Values}
import ee.cone.c4assemble.{Single, ToPrimaryKey, by, c4assemble}
import ee.cone.c4di.{c4, c4multi}
import ee.cone.c4gate.AlienProtocol.U_FromAlienState
import ee.cone.c4gate.AuthProtocol.U_AuthenticatedSession
import ee.cone.c4gate.{EventLogUtil, SessionUtil}
import ee.cone.c4vdom.Types.ViewRes
import ee.cone.c4vdom._
import ee.cone.c4vdom_impl.VPair
import okio.ByteString

import java.net.URL
import scala.Function.chain

@c4assemble("UICompApp") class UIAssembleBase(txFactory: UITxFactory, taskFactory: FromAlienTaskImplFactory){
  def mapTask(
    key: SrcId,
    session: Each[U_AuthenticatedSession],
    fromAlien: Each[U_FromAlienState]
  ): Values[(SrcId, FromAlienTask)] = new URL(fromAlien.location) match { case url =>
    List(WithPK(taskFactory.create(
      session.logKey, fromAlien, Option(url.getQuery).getOrElse(""), Option(url.getRef).getOrElse("")
    )))
  }

  def joinBranchHandler(
    key: SrcId,
    tasks: Values[FromAlienTask],
    @by[BranchKey] requests: Values[BranchMessage],
  ): Values[(SrcId,TxTransform)] =
    List(WithPK(txFactory.create(key, tasks.map(_.fromAlienState.sessionKey).sorted, requests.sortBy(ToPrimaryKey(_)))))
}

@c4multi("AlienExchangeCompApp") final case class FromAlienTaskImpl(
  branchKey: SrcId, fromAlienState: U_FromAlienState, locationQuery: String, locationHash: String
)(txAdd: LTxAdd, sessionUtil: SessionUtil) extends FromAlienTask with BranchTask {
  def branchTask: BranchTask = this
  def product: Product = fromAlienState
  def relocate(to: String): Context => Context = local => {
    val sessionKey = fromAlienState.sessionKey
    val locationWithoutHash = sessionUtil.location(local, sessionKey).split("#")
    match { case Array(l) => l case Array(l,_) => l }
    val lEvents = sessionUtil.setLocation(local, sessionKey, s"$locationWithoutHash#$to")
    txAdd.add(lEvents)(local)
  }
}

@c4("UICompApp") final class EnableBranchScaling extends EnableSimpleScaling(classOf[UITx])

@c4multi("UICompApp") final case class UITx(
  branchKey: String, sessionKeys: List[String], requests: List[BranchMessage with VDomMessage]
)(
  getView: GetByPK[View],
  txAdd: LTxAdd, catchNonFatal: CatchNonFatal, listConfig: ListConfig,
  branchErrorSaver: Option[BranchErrorSaver], branchOperations: BranchOperations,
  eventLogUtil: EventLogUtil, sessionUtil: SessionUtil,
  vDomResolver: VDomResolver, vDomHandler: VDomHandler, vDomUntil: VDomUntil, child: ChildPairFactory,
  rootTags: RootTags, setLocationReceiverFactory: SetLocationReceiverFactory,
) extends TxTransform with LazyLogging {
  private val sessionTimeoutSec = Single.option(listConfig.get("C4STATE_REFRESH_SECONDS")).fold(100L)(_.toLong)
  private def ack(local: Context, request: BranchMessage): LEvents = {
    val (k,v) = (request.header("x-r-reload"), request.header("x-r-index"))
    if(k.nonEmpty && v.nonEmpty) sessionUtil.addAck(local, Single(sessionKeys), k, v) else Nil
  }
  private def eventLogChanges(local: Context, res: PostViewResult): LEvents =
    if(res.diff.isEmpty && res.snapshot.isEmpty) Nil
    else eventLogUtil.write(local, branchKey, res.diff, Option(res.snapshot).filter(_.nonEmpty))
  private def handleError(local: Context, err: Throwable) =
    (local, branchErrorSaver.toSeq.flatMap(_.saveErrors(local, branchKey, err)))
  private def measure[T](before: String)(f: ()=>T)(after: Long=>String): T = {
    logger.debug(before)
    val end = NanoTimer()
    val res = f()
    logger.debug(after(end.ms))
    res
  }
  //dispatches incoming message // can close / set refresh time
  private def dispatch(local: Context, request: VDomMessage with BranchMessage): (Context, LEvents) = {
    assert(request.method == "POST")
    request.header("x-r-op") match {
      case "redraw" => (resetUntil(local), Nil)
      case "online" =>
        (local, sessionUtil.setStatus(local, Single(sessionKeys), sessionTimeoutSec, request.body.size > 0))
      case "" =>
        val path = request.header("x-r-vdom-path")
        if(path.nonEmpty) vDomResolver.resolve(path)(VDomStateKey.of(local).map(_.value)) match {
          case Some(v: Receiver[_]) =>
            (resetUntil(v.asInstanceOf[Receiver[Context]].receive(request)(local)), Nil)
          case v =>
            logger.warn(s"$path ($v) can not receive")
            (local, Nil)
        } else (local, Nil)
    }
  }
  private def resetUntil: Context => Context = VDomStateKey.modify(_.map(st=>st.copy(until = 0)))
  private def handle(local: Context, request: BranchMessage with VDomMessage): (Context, LEvents) =
    measure(s"branch $branchKey tx begin ${request.header("x-r-alien-date")}"){() =>
      val (nLocal, lEvents) =
        catchNonFatal{ dispatch(local, request) }(s"branch $branchKey tx failed")(handleError(local, _))
      (nLocal, lEvents ++ ack(local, request))
    }(t => s"branch $branchKey tx done in $t ms")
  private def handleView(local: Context): (Context, LEvents) =
    vDomHandler.preView(VDomStateKey.of(local)).fold((local,Nil:LEvents)){ preViewRes =>
      handleReView(chain(Seq(
        VDomStateKey.set(Option(preViewRes.clean)),
        CurrentBranchKey.set(branchKey),
      ))(local), preViewRes)
    }
  private def handleReView(local: Context, preViewRes: PreViewResult): (Context, LEvents) = {
    val sessionKey = Single(sessionKeys)
    val location = sessionUtil.location(local, sessionKey)
    val locationEl = rootTags.locationElement("location", location, setLocationReceiverFactory.create(sessionKey))
    val ackList = sessionUtil.ackList(local, sessionKey)
      .map(h => rootTags.ackElement(s"ack-${h.key}", h.key, h.value))
    val viewOpt = getView.ofA(local).get(branchKey)
    val children = catchNonFatal{ viewOpt.fold(Nil:ViewRes)(_.view(local)) }("view failed"){ err =>
      val message = err match { case b: BranchError => b.message(local) case _ => "Internal Error" }
      rootTags.failureElement("failure", message).toChildPair :: Nil
    }
    val nextDom = rootTags.rootElement("root", (locationEl::ackList).map(_.toChildPair) ::: children)
      .toChildPair.asInstanceOf[VPair].value
    val res = vDomHandler.postView(preViewRes, nextDom)
    val seeds = res.seeds.collect{ case r: N_BranchResult => r }
    val nState = res.cache.copy(until = System.currentTimeMillis + vDomUntil.get(seeds))
    val seedChanges = branchOperations.saveChanges(local, branchKey, seeds)
    (VDomStateKey.set(Option(nState))(local), seedChanges ++ eventLogChanges(local, res))
  }
  private def purge(local: Context) =
    (local, sessionUtil.purge(local, Single(sessionKeys)) ++ branchOperations.purge(local, branchKey))
  def transform(local: Context): Context = {
    val requestOpt = requests.collect{ case m: VDomMessage => m }
      .minByOption(_.header("x-r-index") match { case "" => 0L case s => s.toLong })
    val (nLocal, lEvents): (Context, LEvents) =
      if(sessionKeys.isEmpty) (local, Nil:LEvents)
      else if(sessionUtil.expired(local, Single(sessionKeys))) purge(local)
      else requestOpt.fold(handleView(local))(handle(local, _))
    txAdd.add(lEvents ++ requestOpt.toSeq.flatMap(_.deletes))(nLocal)
  }
}

@c4("UICompApp") final class VDomUntilImpl(branchOperations: BranchOperations) extends VDomUntil {
  def get(seeds: Seq[N_BranchResult]): Long = branchOperations.collect(seeds, classOf[N_RestPeriod]) match {
    case l if l.isEmpty => 0L case l => l.map(_.value).min
  }
}

@c4multi("UICompApp") case class SetLocationReceiver(sessionKey: String)(
  sessionUtil: SessionUtil, txAdd: LTxAdd,
) extends Receiver[Context] {
  def receive: Handler = message => local => {
    val value = message.body match { case b: ByteString => b.utf8() }
    val lEvents = sessionUtil.setLocation(local, sessionKey, value)
    txAdd.add(lEvents)(local)
  }
}

@c4tags("UICompApp") trait RootTags {
  @c4el("RootElement") def rootElement(key: String, children: ViewRes): ToChildPair
  @c4el("AckElement") def ackElement(key: String, clientKey: String, index: String): ToChildPair
  @c4el("LocationElement") def locationElement(key: String, value: String, change: Receiver[Context]): ToChildPair
  @c4el("FailureElement") def failureElement(key: String, value: String): ToChildPair
}
