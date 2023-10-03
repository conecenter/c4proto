package ee.cone.c4actor

import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor.ElectorProtocol._
import ee.cone.c4actor.QProtocol.S_Firstborn
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4assemble.{AbstractAll, All, OutFactory, Single, by, byEq, c4assemble, ignore}
import ee.cone.c4assemble.Types.{Each, Outs, Values}
import ee.cone.c4di.{c4, c4multi}
import ee.cone.c4proto.{Id, protocol}

import java.lang.management.ManagementFactory
import java.net.URI
import java.net.http.{HttpClient, HttpRequest}
import java.net.http.HttpRequest.BodyPublishers
import java.net.http.HttpResponse.BodyHandlers
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, Paths}
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import scala.concurrent.{Await, ExecutionContext, Future, Promise}
import scala.concurrent.duration.{Duration, MILLISECONDS}
import scala.util.Random
import scala.util.control.NonFatal
import scala.jdk.FutureConverters._

@protocol("ChildElectorClientApp") object ElectorProtocol {
  @Id(0x00B0) case class S_ReadyProcess(
    @Id(0x00B1) electorClientId: SrcId,
    @Id(0x001A) txId: String,
    @Id(0x00B0) role: String,
    @Id(0x0075) startedAt: Long,
    @Id(0x00B4) hostname: String,
    @Id(0x00B5) refDescr: String,
  )
  @Id(0x00B2) case class S_CompletionReq(
    @Id(0x00B7) requestId: SrcId,
    @Id(0x00B1) electorClientId: SrcId,
    @Id(0x00B6) at: Long,
  )
}

@c4("ChildElectorClientApp") final class ReadyProcessSnapshotPatchIgnore
  extends SnapshotPatchIgnore(classOf[S_ReadyProcess])

case class ReadyProcessesImpl(srcId: SrcId, all: List[ReadyProcess], enabledForCurrentRole: List[SrcId])
  extends ReadyProcesses

case class ReadyProcessImpl(orig: S_ReadyProcess, completionRequests: List[S_CompletionReq]) extends ReadyProcess {
  def id: SrcId = orig.electorClientId
  def txId: String = orig.txId
  def role: String = orig.role
  def startedAt: Long = orig.startedAt
  def hostname: String = orig.hostname
  def refDescr: String = orig.refDescr
  def completionReqAt: Option[Instant] = completionRequests.headOption.map(r=>Instant.ofEpochMilli(r.at))
  def complete(at: Instant): Seq[LEvent[Product]] =
    LEvent.update(S_CompletionReq(UUID.randomUUID.toString, id, at.toEpochMilli))
  def halt: Seq[LEvent[Product]] = LEvent.delete(orig)
}

@c4("ChildElectorClientApp") final class CurrentProcessImpl(config: Config)(
  val idSeq: List[SrcId] = List(config.get("C4ELECTOR_CLIENT_ID")).filter(_.nonEmpty),
) extends CurrentProcess {
  def id: SrcId = Single(idSeq)
}

@c4assemble("ChildElectorClientApp") class EnableTxAssembleBase(
  currentProcess: CurrentProcessImpl,
  actorName: ActorName,
  readyProcessTxFactory: ReadyProcessTxFactory,
  purgeReadyProcessTxFactory: PurgeReadyProcessTxFactory,
  completionTxFactory: CompletionTxFactory,
  purgeCompletionTxFactory: PurgeCompletionTxFactory,
){
  type ActorNameKey = SrcId

  @ignore def enable(t: TxTransform): (SrcId,EnabledTxTr) = WithPK(EnabledTxTr(t))

  def gatherCompletionRequests(
    key: SrcId,
    req: Each[S_CompletionReq]
  ): Values[(ActorNameKey,S_CompletionReq)] =
    List(actorName.value -> req)

  def gatherProcesses(
    key: SrcId,
    process: Each[S_ReadyProcess],
  ): Values[(ActorNameKey,S_ReadyProcess)] =
    List(actorName.value -> process)

  def makeReadyProcesses(
    key: SrcId,
    firstborn: Each[S_Firstborn],
    @by[ActorNameKey] processes: Values[S_ReadyProcess],
    @by[ActorNameKey] requests: Values[S_CompletionReq],
  ): Values[(SrcId,ReadyProcesses)] = {
    val processesByTxId = processes.sortBy(_.txId).toList
    val reqByPid = requests.groupBy(_.electorClientId).withDefaultValue(Nil)
    val richProcesses = processesByTxId.map(p => ReadyProcessImpl(p, reqByPid(p.electorClientId).sortBy(_.at).toList))
    val processesForCurrentRole = richProcesses.filter(_.role == key)
    val enabledForCurrentRole =
      processesForCurrentRole.filter(_.refDescr == processesForCurrentRole.head.refDescr).map(_.id)
    Seq(WithPK(ReadyProcessesImpl(key, richProcesses, enabledForCurrentRole)))
  }

  def enableReadyProcessTx(
    key: SrcId,
    processes: Each[ReadyProcesses]
  ): Values[(SrcId,EnabledTxTr)] =
    for{
      id <- currentProcess.idSeq if !processes.all.exists(_.id==id)
    } yield enable(readyProcessTxFactory.create(s"ReadyProcessTx", id, key))

  def enablePurgeReadyProcessTx(
    key: SrcId,
    processes: Each[ReadyProcesses]
  ): Values[(SrcId,EnabledTxTr)] =
    for{
      id <- currentProcess.idSeq
      (p, i) <- processes.all.zipWithIndex if p.id == id && processes.all.size > 1
      siblingProcess = processes.all((i+1)%processes.all.size)
      siblingOrig = siblingProcess match { case p: ReadyProcessImpl => p.orig }
    } yield enable(purgeReadyProcessTxFactory.create(s"PurgeReadyProcessTx-${siblingProcess.id}", siblingOrig))

  def enableCompletionTx(
    key: SrcId,
    processes: Each[ReadyProcesses]
  ): Values[(SrcId, EnabledTxTr)] =
    for {
      id <- currentProcess.idSeq
      p <- processes.all if p.id == id
      at <- p.completionReqAt.toSeq
    } yield enable(completionTxFactory.create("CompletionTx", at))

  def enablePurgeCompletionTx(
    key: SrcId,
    processes: Each[ReadyProcesses],
    @by[ActorNameKey] requests: Values[S_CompletionReq],
  ): Values[(SrcId, EnabledTxTr)] =
    for {
      id <- currentProcess.idSeq
      p <- processes.all.headOption if p.id == id
      pidSet = processes.all.map(_.id).toSet
      purgeRequests <- Option(requests.filterNot(r=>pidSet(r.electorClientId))) if purgeRequests.nonEmpty
    } yield enable(purgeCompletionTxFactory.create("PurgeCompletionTx", purgeRequests.sortBy(_.requestId).toList))
}

/*
@c4assemble("NoScalingApp") class NoScalingAssembleBase(currentProcess: CurrentProcessImpl, actorName: ActorName){
    def enableTxTr(
      key: SrcId,
      txTr: Each[TxTransform],
      @byEq[SrcId](actorName.value) processes: Each[ReadyProcesses],
    ): Values[(SrcId,EnabledTxTr)] =
      for {
        id <- currentProcess.idSeq
        pId <- processes.enabledForCurrentRole.headOption if pId == id
      } yield WithPK(EnabledTxTr(txTr))
}*/

@c4multi("ChildElectorClientApp") final case class PurgeCompletionTx(id: SrcId, requests: List[S_CompletionReq])(
  txAdd: LTxAdd
) extends TxTransform with LazyLogging {
  def transform(local: Context): Context = {
    logger.info("purging completion requests")
    txAdd.add(requests.flatMap(LEvent.delete))(local)
  }
}

@c4multi("ChildElectorClientApp") final case class CompletionTx(id: SrcId, at: Instant)(
  execution: Execution
) extends TxTransform with LazyLogging {
  def transform(local: Context): Context = {
    if(Instant.now.isAfter(at)){
      logger.info("completing as requested")
      execution.complete()
    }
    local
  }
}

case object PurgeReadyProcessStateKey extends TransientLens[Option[ElectorRequests]](None)
@c4multi("ChildElectorClientApp") final case class PurgeReadyProcessTx(
  srcId: SrcId, process: S_ReadyProcess
)(
  txAdd: LTxAdd,
  electorRequestsFactory: ElectorRequestsFactory,
) extends TxTransform with LazyLogging {
  def transform(local: Context): Context = {
    val wasReq = PurgeReadyProcessStateKey.of(local)
      .getOrElse(electorRequestsFactory.createCheck(process.electorClientId))
    if(wasReq.lockedUntil() > 0) {
      logger.info(process.electorClientId)
      txAdd.add(LEvent.delete(process))(local)
    } else {
      val willReq = wasReq.mayBeSend()
      if(wasReq == willReq) local
      else PurgeReadyProcessStateKey.set(Option(willReq))(local)
    }
  }
}

@c4("ChildElectorClientApp") final class ReadyProcessOnce(
  execution: Execution,
  value: Promise[Unit] = Promise()
){
  def check(): Unit = {
    if(value.isCompleted) Runtime.getRuntime.halt(2)
    execution.success(value,())
  }
}
@c4multi("ChildElectorClientApp") final case class ReadyProcessTx(
  srcId: SrcId, electorClientId: SrcId, fullActorName: String
)(
  txAdd: LTxAdd,
  once: ReadyProcessOnce,
  config: Config,
) extends TxTransform with LazyLogging {
  def transform(local: Context): Context = { // register self / track no self -- activity like snapshot-put can drop S_ReadyProcess
    once.check()
    val process = S_ReadyProcess(
      electorClientId, "", fullActorName, System.currentTimeMillis, config.get("HOSTNAME"),
      readTextOrEmpty("/c4ref_descr")
    )
    logger.info(process.toString)
    txAdd.add(LEvent.update(process))(local)
  }
  private def readTextOrEmpty(pathStr: String): String =
    Option(Paths.get(pathStr)).filter(Files.exists(_)).fold("")(path => new String(Files.readAllBytes(path), UTF_8))
}

////

@c4("ElectorClientApp") final class ElectorRequestsFactory(
  config: Config, listConfig: ListConfig, val execution: Execution,
  val clientProvider: HttpClientProvider
) {
  def timeout: Long = 1000
  def createRequest(ownerObj: String, ownerSubj: String, address: String, period: Int): HttpRequest =
    HttpRequest.newBuilder
      .timeout(java.time.Duration.ofMillis(timeout))
      .uri(URI.create(s"$address/lock/$ownerObj"))
      .headers("x-r-lock-owner",ownerSubj,"x-r-lock-period",s"$period")
      .POST(BodyPublishers.noBody()).build()
  def createRequests(ownerObj: String, ownerSubj: String, period: Int, modeHint: String): ElectorRequests = {
    val serversStr = config.get("C4ELECTOR_SERVERS")
    val addresses = serversStr.split(",").toList
    val hint = s"$serversStr $ownerObj $ownerSubj $modeHint"
    new ElectorRequests(addresses.map(createRequest(ownerObj,ownerSubj,_,period)), period, hint, this)
  }
  def createLock(owner: String): ElectorRequests = {
    val lockPeriod = Single.option(listConfig.get("C4ELECTOR_LOCKING_PERIOD"))
      .fold(10000)(_.toInt)
    createRequests(owner, owner, lockPeriod,s"  lock")
  }
  def createUnlock(owner: String): ElectorRequests = {
    createRequests(owner, owner, 1, s"unlock")
  }
  def createCheck(owner: String): ElectorRequests = {
    createRequests(owner, "checker", 1, s" check")
  }
  def checkDebug(): Boolean =
    listConfig.get("C4ELECTOR_DEBUG").exists(v=>Files.exists(Paths.get(v)))
}

class ElectorRequests(
  requests: List[HttpRequest], lockPeriod: Int, hint: String,
  parent: ElectorRequestsFactory,
  wasLockedUntil: Long = 0,
  val response: Future[Option[Long]] = Future.successful(None), skipUntil: Long = 0,
  debug: Boolean = false,
) extends LazyLogging {
  def msNow(): Long = System.nanoTime / 1000000
  def mayBeSend(): ElectorRequests = {
    val started = msNow()
    if(debug) logger.debug(s"st:${started} su:${skipUntil}")
    if(started < skipUntil) this else {
      val resF = parent.execution.unboundedFatal(sendInner(started)(_))
      val until = started + parent.timeout + Random.nextInt(200)
      new ElectorRequests(requests, lockPeriod, hint, parent, lockedUntil(), resF, until, parent.checkDebug())
    }
  }
  def sendInner(started: Long)(implicit ec: ExecutionContext): Future[Option[Long]] =
    for {
      client <- parent.clientProvider.get
      results <- Future.sequence(requests.map(req=>
        client.sendAsync(req, BodyHandlers.discarding()).asScala
          .map{ resp =>
            if(debug) logger.debug(s"resp ${resp.statusCode} ${resp.uri()}")
            resp.statusCode==200
          }
          .recover{ case NonFatal(e) =>
            logger.error("elector-post",e)
            false
          }
      ))
    } yield {
      val (ok,nok) = results.partition(r=>r)
      if(debug) logger.debug(s"$hint -- ${ok.size}/${requests.size}")
      if(ok.size > nok.size) Option(started+lockPeriod) else None
    }
  def lockedUntil(): Long = response.value.flatMap(_.toOption).flatten
    .fold(wasLockedUntil)(v=>Math.max(v,wasLockedUntil))
  def lockedUntilRel(): Long = lockedUntil() - msNow()
}

////


@c4("ElectorClientApp") final class ProcessParentDeathTracker(
  listConfig: ListConfig, execution: Execution
) extends Executable with Early {
  def run(): Unit = for{
    pid <- Single.option(listConfig.get("C4PARENT_PID")).map(_.toLong)
  } {
    val force = listConfig.get("C4PARENT_FORCE").nonEmpty
    ProcessHandle.of(pid).ifPresent{ parentProc =>
      val remove = if(force) execution.onShutdown("parentTracker", ()=>{
        val ignoreOk = parentProc.destroy()
      }) else ()=>()
      val ignoreProc = parentProc.onExit.get()
      remove()
    }
    if(force) Runtime.getRuntime.halt(2)
    throw new Exception(s"$pid not found")
  }
}

@c4("ParentElectorClientApp") final class ProcessTree(execution: Execution){
  def startProcess(args: Seq[String], env: Map[String,String]): Process = {
    val processBuilder = new ProcessBuilder(args:_*)
    for((k,v) <- env) processBuilder.environment().put(k,v)
    processBuilder.inheritIO().start() // by default stdout pipes to parent, so most easy is inheritIO here
  }
  def ignoreTheSameProcess(p: Process): Unit = ()
  def destroyForcibly(process: Process): Unit =
    ignoreTheSameProcess(process.destroyForcibly())
  def withProcess(args: Seq[String], env: Map[String,String], inner: Process=>Unit): Unit = {
    val allowExit = Promise[Unit]()
    val pid = ManagementFactory.getRuntimeMXBean.getPid
    val addEnv = Map("C4PARENT_PID" -> s"$pid","C4PARENT_FORCE" -> "1")
    val process = startProcess(args, env ++ addEnv)
    val remove = execution.onShutdown("controlSubProcess", ()=>{
      process.destroy()
      val end = NanoTimer()
      if(!process.waitFor(3, TimeUnit.SECONDS)) destroyForcibly(process)
      println(s"waitFor ${end.ms} ms")
      Await.result(allowExit.future,Duration.Inf)
    })
    try {
      inner(process)
    } finally {
      destroyForcibly(process)
      execution.success(allowExit,())
      remove()
    }
  }
}

////

@c4("ParentElectorClientApp") final class ElectorSystem(
  config: Config,
  listConfig: ListConfig,
  electorRequestsFactory: ElectorRequestsFactory,
  processTree: ProcessTree,
  execution: Execution,
  owner: String = s"${UUID.randomUUID()}"
) extends Executable with Early {
  def sendWhile(initReq: ElectorRequests)(cond: ElectorRequests=>Boolean): ElectorRequests =
    Iterator.iterate(initReq){ req =>
      Thread.sleep(100)
      req.mayBeSend()
    }.dropWhile(cond).take(1).toList.head
  def notSafelyLocked(req: ElectorRequests): Boolean = req.lockedUntilRel() < 4000
  def run(): Unit = {
    val lockRequests = electorRequestsFactory.createLock(owner)
    val unlockRequests = electorRequestsFactory.createUnlock(owner)
    val initLockReq = sendWhile(lockRequests)(notSafelyLocked) // before process start
    val appClass = config.get("C4APP_CLASS_INNER")
    val env = Map("C4APP_CLASS" -> appClass,"C4ELECTOR_CLIENT_ID" -> owner)
    val args = Seq("java") ++ debug ++ Seq("ee.cone.c4actor.ServerMain")
    processTree.withProcess(args, env, process => {
      val finLockReq = sendWhile(initLockReq){ req =>
        if(notSafelyLocked(req)) processTree.destroyForcibly(process)
        process.isAlive
      }
      val unlockRes = unlockRequests.mayBeSend().response
      val unlockTimeout = Duration(electorRequestsFactory.timeout+100, MILLISECONDS)
      ignoreExiting(Await.result(unlockRes, unlockTimeout))
    })
    execution.complete()
  }
  def ignoreExiting(v: Option[Long]): Unit = ()
  def debug: Seq[String] = listConfig.get("C4JDWP_ADDRESS")
      .map(a=>s"-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=$a")
}




/*
  def sendMore(last: Future[Option[Long]])(implicit ec: ExecutionContext): Future[Option[Long]] = for {
    wasUntil <- last
    until <- send(lockRequests)
    _ <- if(until.isEmpty && wasUntil.isEmpty) send(unlockRequests) else Future.successful()
  } yield until
*/

/*
in parent:
    onShutdown: signal child, Await leaser
        # normal; leaser will see child death (check leaser will stay active)
    watch:
        if no parent: exit
            # normal; hook will run; give signal child; leaser will see child death
    in leaser:
        while isAlive child:
          lock proc-uid
          if proc-uid lease ends: halt child
            # isAlive will become false
        unlock proc-uid
        finally:
            halt child, unlock hook
in child:
    watch:
        if no parent: halt self
            # things went wrong, normally parent waits for child
    PurgeReadyProcessTx -- purger actor in any child:
        try lock all proc-uid by orig-s; delete orig-s for succeeded
    ReadyProcessTx
        register self with proc-uid orig
        if orig deleted (ex put snapshot): halts
    any actor can reassign self to other proc-uid by orig
    assigned actor is activated only on their proc-uid
    non-assigned actor is activated on proc-uid with minimal registration tx-id
    late executables run from tx
*/

/*
checking 1 on dev_server:
  made FailOverTest Tx and Executable
  make everything up and see their log from single replica
  kill this replica parent and see in order:
  -->parent 'hook-in controlSubProcess'
  --> child kafka dies --> parent 'unlock 3/3' --> elector 'locked by checker'
  --> other replica PurgeReadyProcessTx
  --> other replica 'tracking 1 late services'
  --> new replica ProducerConfig
  --> new replica ReadyProcessTx

de- works

todo:

add tx code
c43e
see res5tart
push
make sp- with 2 replicas


check in sp

check more in real:
    split brain
    elector node off
    app node off
    child gc

check proto test apps

 */