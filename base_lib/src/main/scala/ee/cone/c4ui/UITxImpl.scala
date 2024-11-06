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
  ): Values[(SrcId,TxTransform)] = tasks match {
    case Seq(task) =>
      def long(v: String) = if(v.isEmpty) 0L else try v.toLong catch { case _: NumberFormatException => 0L }
      val req = requests.minByOption(r=>(long(r.header("x-r-index")),ToPrimaryKey(r)))
      List(WithPK(txFactory.create(key, task.fromAlienState.sessionKey, req)))
    case _ => Nil
  }
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

@c4multi("UICompApp") final case class UIPostHandler(
  branchKey: String, sessionKey: String, request: BranchMessage
)(
  listConfig: ListConfig, branchErrorSaver: Option[BranchErrorSaver], catchNonFatal: CatchNonFatal,
  sessionUtil: SessionUtil, vDomResolver: VDomResolver,
) extends LazyLogging {
  // do
  private val sessionTimeoutSec = Single.option(listConfig.get("C4STATE_REFRESH_SECONDS")).fold(100L)(_.toLong)
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
  private def dispatch(local: Context): (Context, LEvents) = catchNonFatal{
    assert(request.method == "POST")
    request.header("x-r-op") match {
      case "redraw" => (resetUntil(local), Nil)
      case "online" =>
        (local, sessionUtil.setStatus(local, sessionKey, sessionTimeoutSec, request.body.size > 0))
      case "" =>
        val path = request.header("x-r-vdom-path")
        if(path.nonEmpty) vDomResolver.resolve(path)(VDomStateKey.of(local).map(_.value)) match {
          case Some(v: Receiver[_]) =>
            (resetUntil(v.asInstanceOf[Receiver[Context]].receive(request.asInstanceOf[VDomMessage])(local)), Nil)
          case v =>
            logger.warn(s"$path ($v) can not receive")
            (local, Nil)
        } else (local, Nil)
    }
  }(s"branch $branchKey dispatch failed")(handleError(local, _))
  private def resetUntil: Context => Context = VDomStateKey.modify(_.map(st=>st.copy(until = 0)))
  private def dedupAck(local: Context): (Context, LEvents) = catchNonFatal{
    (request.header("x-r-reload"), request.header("x-r-index")) match {
      case ("", "") => dispatch(local)
      case (k, vStr) if k.nonEmpty && vStr.nonEmpty =>
        if(sessionUtil.ackList(local, sessionKey).exists(h => h.key == k && vStr.toLong <= h.value.toLong)) (local, Nil)
        else dispatch(local) match {
          case (nLocal, lEvents) => (nLocal, lEvents ++ sessionUtil.addAck(local, sessionKey, k, vStr))
        }
    }
  }(s"branch $branchKey dedupAck failed")(handleError(local, _))
  def handle(local: Context): (Context, LEvents) =
    measure(s"branch $branchKey tx begin ${request.header("x-r-alien-date")}"){() =>
      dedupAck(local)
    }(t => s"branch $branchKey tx done in $t ms")
}

@c4multi("UICompApp") final case class UIViewer(branchKey: String, sessionKey: String)(
  catchNonFatal: CatchNonFatal, sessionUtil: SessionUtil, eventLogUtil: EventLogUtil,
  branchOperations: BranchOperations, getView: GetByPK[View], vDomHandler: VDomHandler, vDomUntil: VDomUntil,
  rootTagsProvider: RootTagsProvider, setLocationReceiverFactory: SetLocationReceiverFactory,
){
  val rootTags: RootTags[Context] = rootTagsProvider.get[Context]
  private def eventLogChanges(local: Context, res: PostViewResult): LEvents =
    if(res.diff.isEmpty && res.snapshot.isEmpty) Nil
    else eventLogUtil.write(local, branchKey, res.diff, Option(res.snapshot).filter(_.nonEmpty))
  private def handleReView(local: Context, preViewRes: PreViewResult): (Context, LEvents) = {
    val location = sessionUtil.location(local, sessionKey)
    val statusEl = rootTags.statusElement("status", location, setLocationReceiverFactory.create(sessionKey))
    val ackList = sessionUtil.ackList(local, sessionKey)
      .map(h => rootTags.ackElement(s"ack-${h.key}", h.key, h.value))
    val viewOpt = getView.ofA(local).get(branchKey)
    val children = catchNonFatal{ viewOpt.fold(Nil:ViewRes)(_.view(local)) }("view failed"){ err =>
      val message = err match { case b: BranchError => b.message(local) case _ => "Internal Error" }
      rootTags.failureElement("failure", message).toChildPair :: Nil
    }
    val nextDom = rootTags.rootElement("root", (statusEl::ackList).map(_.toChildPair) ::: children)
      .toChildPair.asInstanceOf[VPair].value
    val res = vDomHandler.postView(preViewRes, nextDom)
    val seeds = res.seeds.collect{ case r: N_BranchResult => r }
    val nState = res.cache.copy(until = System.currentTimeMillis + vDomUntil.get(seeds))
    val seedChanges = branchOperations.saveChanges(local, branchKey, seeds)
    (VDomStateKey.set(Option(nState))(local), seedChanges ++ eventLogChanges(local, res))
  }
  def handle(local: Context): (Context, LEvents) =
    vDomHandler.preView(VDomStateKey.of(local)).fold((local,Nil:LEvents)){ preViewRes =>
      handleReView(chain(Seq(
        VDomStateKey.set(Option(preViewRes.clean)),
        CurrentBranchKey.set(branchKey),
      ))(local), preViewRes)
    }
}

@c4multi("UICompApp") final case class UITx(
  branchKey: String, sessionKey: String, reqOpt: Option[BranchMessage]
)(
  txAdd: LTxAdd, sessionUtil: SessionUtil, branchOperations: BranchOperations,
  uiViewerFactory: UIViewerFactory, uiPostHandlerFactory: UIPostHandlerFactory,
) extends TxTransform with LazyLogging {
  private def purge(local: Context) =
    (local, sessionUtil.purge(local, sessionKey) ++ branchOperations.purge(local, branchKey))
  def transform(local: Context): Context = {
    val (nLocal, lEvents): (Context, LEvents) =
      if(sessionUtil.expired(local, sessionKey)) purge(local)
      else reqOpt.fold(uiViewerFactory.create(branchKey, sessionKey).handle(local))(
        uiPostHandlerFactory.create(branchKey, sessionKey, _).handle(local)
      )
    txAdd.add(lEvents)(nLocal)
  }
}

@c4("UICompApp") final class VDomUntilImpl(branchOperations: BranchOperations) extends VDomUntil {
  def get(seeds: Seq[N_BranchResult]): Long = branchOperations.collect(seeds, classOf[N_RestPeriod]) match {
    case l if l.isEmpty => 0L case l => l.map(_.value).min
  }
}

@c4multi("UICompApp") final case class SetLocationReceiver(sessionKey: String)(
  sessionUtil: SessionUtil, txAdd: LTxAdd,
) extends Receiver[Context] {
  def receive: Handler = message => local => {
    val value = message.body match { case b: ByteString => b.utf8() }
    val lEvents = sessionUtil.setLocation(local, sessionKey, value)
    txAdd.add(lEvents)(local)
  }
}

@c4tags("UICompApp") trait RootTags[C] {
  @c4el("RootElement") def rootElement(key: String, children: ViewRes): ToChildPair
  @c4el("AckElement") def ackElement(key: String, clientKey: String, index: String): ToChildPair
  @c4el("StatusElement") def statusElement(key: String, location: String, locationChange: Receiver[C]): ToChildPair
  @c4el("FailureElement") def failureElement(key: String, value: String): ToChildPair
}
