package ee.cone.c4actor

import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor.ElectorProtocol._
import ee.cone.c4actor.QProtocol.S_Firstborn
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4assemble.{Single, by, byEq, c4assemble, ignore}
import ee.cone.c4assemble.Types.{Each, Values}
import ee.cone.c4di.{c4, c4multi}
import ee.cone.c4proto.{Id, protocol}

import java.lang.management.ManagementFactory
import java.net.URI
import java.net.http.{HttpClient, HttpRequest}
import java.net.http.HttpRequest.BodyPublishers
import java.net.http.HttpResponse.BodyHandlers
import java.nio.file.{Files, Paths}
import java.util.UUID
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
  )
}

case class ReadyProcessesImpl(
  srcId: SrcId,
  processesByTxId: List[S_ReadyProcess],
  val isMaster: Boolean,
  currentIdOpt: Option[SrcId]
)(
  val ids: List[SrcId] = processesByTxId.map(_.electorClientId)
) extends ReadyProcesses {
  def currentId: SrcId = currentIdOpt.get
}

@c4assemble("ChildElectorClientApp") class EnableTxAssembleBase(
  config: Config,
  actorName: ActorName,
  readyProcessTxFactory: ReadyProcessTxFactory,
  purgeReadyProcessTxFactory: PurgeReadyProcessTxFactory,
  enableScaling: Option[EnableScaling],
)(
  electorClientIdOpt: List[String] =
    List(config.get("C4ELECTOR_CLIENT_ID")).filter(_.nonEmpty)
){
  type ActorNameKey = SrcId

  @ignore def enable(t: TxTransform): Values[(SrcId,EnabledTxTr)] =
    List(WithPK(EnabledTxTr(t)))

  def gatherProcesses(
    key: SrcId,
    process: Each[S_ReadyProcess],
  ): Values[(ActorNameKey,S_ReadyProcess)] =
    if(process.role==actorName.value) List(actorName.value->process) else Nil

  def makeReadyProcesses(
    key: SrcId,
    firstborn: Each[S_Firstborn],
    @by[ActorNameKey] processes: Values[S_ReadyProcess],
  ): Values[(SrcId,ReadyProcesses)] = {
    val processesByTxId = processes.sortBy(_.txId).toList
    val isMaster = processesByTxId.take(1).map(_.electorClientId) == electorClientIdOpt
    //val currentDestinations = if(isMaster) "" :: electorClientIdOpt else electorClientIdOpt
    List(WithPK(ReadyProcessesImpl(key,processesByTxId,isMaster,Single.option(electorClientIdOpt))()))
  }

  def enableReadyProcessTx(
    key: SrcId,
    processes: Each[ReadyProcesses]
  ): Values[(SrcId,EnabledTxTr)] =
    for{
      id <- electorClientIdOpt if !processes.ids.contains(id)
      process = S_ReadyProcess(id, "", key)
      res <- enable(readyProcessTxFactory.create(s"ReadyProcessTx", process))
    } yield res

  def enablePurgeReadyProcessTx(
    key: SrcId,
    processes: Values[ReadyProcesses]
  ): Values[(SrcId,EnabledTxTr)] =
    for{
      id <- electorClientIdOpt
      processesImpl <- processes.map{ case p: ReadyProcessesImpl => p } if processesImpl.processesByTxId.size > 1
      procList = processesImpl.processesByTxId
      process = procList((procList.indexWhere(_.electorClientId==id)+1)%procList.size)
      res <- enable(purgeReadyProcessTxFactory.create(s"PurgeReadyProcessTx",process))
    } yield res

  def enableTxTr(
    key: SrcId,
    txTrs: Values[TxTransform],
    @byEq[SrcId](actorName.value) processes: Each[ReadyProcesses],
  ): Values[(SrcId,EnabledTxTr)] =
    if(processes.isMaster && enableScaling.isEmpty) txTrs.flatMap(enable) else Nil
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
  srcId: SrcId, process: S_ReadyProcess
)(
  txAdd: LTxAdd,
  once: ReadyProcessOnce,
) extends TxTransform with LazyLogging {
  def transform(local: Context): Context = { // register self / track no self -- activity like snapshot-put can drop S_ReadyProcess
    once.check()
    logger.info(process.electorClientId)
    txAdd.add(LEvent.update(process))(local)
  }
}

////

@c4("ElectorClientApp") final class ElectorRequestsFactory(
  config: Config, listConfig: ListConfig, val execution: Execution,
  val clientPromise: Promise[HttpClient] = Promise(),
) extends Executable with Early {
  def run(): Unit = execution.success(clientPromise, HttpClient.newHttpClient)
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
}

class ElectorRequests(
  requests: List[HttpRequest], lockPeriod: Int, hint: String,
  parent: ElectorRequestsFactory,
  wasLockedUntil: Long = 0,
  val response: Future[Option[Long]] = Future.successful(None), skipUntil: Long = 0,
) extends LazyLogging {
  def msNow(): Long = System.nanoTime / 1000000
  def mayBeSend(): ElectorRequests = {
    val started = msNow()
    if(started < skipUntil) this else {
      val resF = parent.execution.unboundedFatal(sendInner(started)(_))
      val until = started + parent.timeout + Random.nextInt(200)
      new ElectorRequests(requests, lockPeriod, hint, parent, lockedUntil(), resF, until)
    }
  }
  def sendInner(started: Long)(implicit ec: ExecutionContext): Future[Option[Long]] =
    for {
      client <- parent.clientPromise.future
      results <- Future.sequence(requests.map(req=>
        client.sendAsync(req, BodyHandlers.discarding()).asScala
          .map(resp=>resp.statusCode==200)
          .recover{ case NonFatal(e) =>
            logger.error("elector-post",e)
            false
          }
      ))
    } yield {
      val (ok,nok) = results.partition(r=>r)
      logger.debug(s"$hint -- ${ok.size}/${requests.size}")
      if(ok.size > nok.size) Option(started+lockPeriod) else None
    }
  def lockedUntil(): Long = response.value.flatMap(_.toOption).flatten
    .fold(wasLockedUntil)(v=>Math.max(v,wasLockedUntil))
  def lockedUntilRel(): Long = lockedUntil() - msNow()
}

////


@c4("ElectorClientApp") final class ProcessParentDeathTracker(
  listConfig: ListConfig
) extends Executable with Early {
  def run(): Unit = for{
    pid <- Single.option(listConfig.get("C4PARENT_PID"))
    path = Paths.get(s"/proc/$pid")
  } {
    Iterator.continually(Thread.sleep(1000)).takeWhile(_=>Files.exists(path)).foreach(_=>())
    if(listConfig.get("C4PARENT_FORCE").nonEmpty) Runtime.getRuntime.halt(2)
    throw new Exception(s"$path not found")
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
    val args = Seq("java","ee.cone.c4actor.ServerMain")
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