package ee.cone.c4actor

import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor.ElectorProtocol.{S_ReadyProcess, S_ScaledTxTr}
import ee.cone.c4actor.QProtocol.S_Firstborn
import ee.cone.c4actor.Types.SrcId
import ee.cone.c4assemble.{AbstractAll, All, Single, by, byEq, c4assemble}
import ee.cone.c4assemble.Types.{Each, Values}
import ee.cone.c4di.{c4, c4multi}
import ee.cone.c4proto.{Id, protocol}
import java.lang.management.ManagementFactory
import java.net.URI
import java.net.http.{HttpClient, HttpRequest}
import java.net.http.HttpRequest.BodyPublishers
import java.net.http.HttpResponse.BodyHandlers
import java.nio.file.{Files, Path, Paths}
import java.time.Instant
import java.util.UUID
import scala.annotation.tailrec
import scala.concurrent.{Await, ExecutionContext, Future, Promise}
import scala.concurrent.duration.Duration
import scala.util.Random
import scala.util.control.NonFatal
import scala.jdk.FutureConverters._

@protocol("ChildElectorClientApp") object ElectorProtocol {
  @Id(0x00B0) case class S_ReadyProcess(
    @Id(0x0011) srcId: SrcId,
    @Id(0x001A) txId: String,
    @Id(0x00B0) role: String,
  )
  @Id(0x00B1) case class S_ScaledTxTr(
    @Id(0x0011) srcId: SrcId,
    @Id(0x00B1) electorClientId: SrcId,
  )
}

trait EveryProcessTxTr

@c4assemble("ChildElectorClientApp") class EnableTxAssembleBase(
  config: Config,
  actorName: ActorName,
  readyProcessTxFactory: ReadyProcessTxFactory,
  purgeReadyProcessTxFactory: PurgeReadyProcessTxFactory,
)(
  electorClientIdOpt: List[String] = List(config.get("C4ELECTOR_CLIENT_ID")).filter(_.nonEmpty)
) {
  type CurrentElectorClientKey = SrcId
  type MasterAll = AbstractAll

  def gatherProcesses(
    key: SrcId,
    process: Each[S_ReadyProcess],
  ): Values[(CurrentElectorClientKey,S_ReadyProcess)] =
    electorClientIdOpt.filter(_=>process.role==actorName.value).map(_->process)

  def gatherFirstborn(
    key: SrcId,
    firstborn: Each[S_Firstborn],
  ): Values[(CurrentElectorClientKey,S_Firstborn)] =
    electorClientIdOpt.map(_->firstborn)

  def enableReadyProcessTx(
    key: SrcId,
    @by[CurrentElectorClientKey] firstborn: Each[S_Firstborn],
    @by[CurrentElectorClientKey] processes: Values[S_ReadyProcess],
  ): Values[(SrcId,TxTransform)] =
    WithPK(purgeReadyProcessTxFactory.create(
      s"PurgeReadyProcessTx-$key",
      processes.sortBy(_.srcId).toList
    )) :: (
      if(processes.exists(_.srcId==key)) Nil
      else List(WithPK(readyProcessTxFactory.create(
        s"ReadyProcessTx-$key",
        S_ReadyProcess(key, "", actorName.value)
      )))
    )

  def checkIsMaster(
    key: SrcId,
    @by[CurrentElectorClientKey] processes: Values[S_ReadyProcess],
  ): Values[(MasterAll,S_ReadyProcess)] =
    List(processes.minBy(_.txId)).filter(_.srcId == key).map(All->_)

  def enable(
    key: SrcId,
    txTr: Each[TxTransform],
    scaled: Values[S_ScaledTxTr],
    @byEq[MasterAll](All) master: Values[S_ReadyProcess],
  ): Values[(SrcId,EnabledTxTr)] =
    if(
      scaled.exists(s=>electorClientIdOpt.contains(s.electorClientId)) ||
      scaled.isEmpty && master.nonEmpty ||
      txTr.isInstanceOf[EveryProcessTxTr] ||
      electorClientIdOpt.isEmpty
    ) List(WithPK(EnabledTxTr(txTr))) else Nil
}

case object PurgeReadyProcessStateKey extends TransientLens[(Option[HttpClient],List[(S_ReadyProcess,ElectorDeferredResponse)])]((None,Nil))
@c4multi("ChildElectorClientApp") final case class PurgeReadyProcessTx(
  srcId: SrcId, processes: List[S_ReadyProcess]
)(
  txAdd: LTxAdd,
  electorRequestsFactory: ElectorRequestsFactory,
)(
  requests: List[(S_ReadyProcess,ElectorRequests)] =
    processes.map(p=>(p,electorRequestsFactory.createCheck(p.srcId))),
) extends TxTransform with EveryProcessTxTr with LazyLogging {
  def transform(local: Context): Context = {
    val (clientOpt,wasRes) = PurgeReadyProcessStateKey.of(local)
    val toDel = wasRes.collect{ case (p,r) if r.get().nonEmpty => p }
    for(p <- toDel) logger.info(s"${p.srcId}")
    val client = clientOpt.getOrElse(HttpClient.newHttpClient)
    val willRes = requests.map{ case (p,r) => (p, r.send(client)) }
    Function.chain(Seq(
      txAdd.add(LEvent.delete(toDel)),
      PurgeReadyProcessStateKey.set((Option(client),willRes)),
      SleepUntilKey.set(Instant.now.plusSeconds(2))
    ))(local)
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
  srcId: SrcId, toAdd: S_ReadyProcess
)(
  txAdd: LTxAdd,
  once: ReadyProcessOnce,
) extends TxTransform with EveryProcessTxTr with LazyLogging {
  def transform(local: Context): Context = { // register self / track no self -- activity like snapshot-put can drop S_ReadyProcess
    once.check()
    txAdd.add(LEvent.update(toAdd))(local)
  }
}

////

@c4("ElectorClientApp") final class ElectorRequestsFactory(
  config: Config, listConfig: ListConfig, execution: Execution
){
  def createRequest(ownerObj: String, ownerSubj: String, address: String, period: Int): HttpRequest =
    HttpRequest.newBuilder
      .timeout(java.time.Duration.ofSeconds(1))
      .uri(URI.create(s"$address/lock/$ownerObj"))
      .headers("x-r-lock-owner",ownerSubj,"x-r-lock-period",s"$period")
      .POST(BodyPublishers.noBody()).build()
  def createRequests(ownerObj: String, ownerSubj: String, period: Int, modeHint: String): ElectorRequests = {
    val serversStr = config.get("C4ELECTOR_SERVERS")
    val addresses = serversStr.split(",").toList
    val hint = s"$serversStr $ownerObj $ownerSubj $modeHint"
    new ElectorRequests(addresses.map(createRequest(ownerObj,ownerSubj,_,period)), period, hint, execution)
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
  requests: List[HttpRequest], lockPeriod: Int, hint: String, execution: Execution
) extends LazyLogging {
  def msNow: Long = System.nanoTime / 1000000
  def send(client: HttpClient): ElectorDeferredResponse =
    new ElectorDeferredResponse(execution.unboundedFatal(sendInner(client)(_)))
  def sendInner(client: HttpClient)(implicit ec: ExecutionContext): Future[Option[Long]] = {
    val started = msNow
    Future.sequence(requests.map(req=>
      client.sendAsync(req, BodyHandlers.discarding()).asScala
        .map(resp=>resp.statusCode==200)
        .recover{ case NonFatal(e) =>
          logger.error("elector-post",e)
          false
        }
    )).map{ results =>
      val (ok,nok) = results.partition(r=>r)
      logger.debug(s"$hint -- ${ok.size}/${requests.size}")
      if(ok.size > nok.size) Option(started+lockPeriod) else None
    }
  }
}

class ElectorDeferredResponse(f: Future[Option[Long]]) {
  def get(): Option[Long] = f.value.flatMap(_.toOption).flatten
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
    processBuilder.start()
  }
  def ignoreTheSameProcess(p: Process): Unit = ()
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
      ignoreTheSameProcess(process.destroyForcibly())
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
  def sendSleep(client: HttpClient, requests: ElectorRequests): Option[Long] = {
    val next = requests.send(client)
    Thread.sleep(1000+Random.nextInt(300))
    next.get()
  }
  def run(): Unit = {
    val lockRequests = electorRequestsFactory.createLock(owner)
    val unlockRequests = electorRequestsFactory.createUnlock(owner)
    val client = HttpClient.newHttpClient
    val initUntil = Iterator.continually(sendSleep(client,lockRequests))
      .flatten.take(1).toList.head // before process start
    val appClass = config.get("C4APP_CLASS_INNER")
    val env = Map("C4APP_CLASS" -> appClass,"C4ELECTOR_CLIENT_ID" -> owner)
    val args = Seq("java","ee.cone.c4actor.ServerMain")
    processTree.withProcess(args, env, process => {
      Iterator.iterate(initUntil){ wasUntil =>
        val until = (wasUntil :: sendSleep(client,lockRequests).toList).max
        if(until - lockRequests.msNow < 4000)
          throw new Exception("can not stay")
        until
      }.takeWhile(_=>process.isAlive).foreach(_=>())
      val ignoredUntil = sendSleep(client,unlockRequests)
    })
    execution.complete()
  }
}




/*
  def sendMore(last: Future[Option[Long]])(implicit ec: ExecutionContext): Future[Option[Long]] = for {
    wasUntil <- last
    until <- send(lockRequests)
    _ <- if(until.isEmpty && wasUntil.isEmpty) send(unlockRequests) else Future.successful()
  } yield until
*/


/*
in child: if no parent: halt self
    # things went wrong, normally parent waits for child
in parent: onShutdown: signal child, Await leaser
    # normal; leaser will see child death (check leaser will stay active)
in parent leaser:
    if no parent: signal child
        # normal; leaser will see child death
    if lease ends: return
        # finally will do rest
    if not isAlive child: unlock lease, return
    finally: halt child, unlock hook
*/
/*
in parent:
    onShutdown: signal child, Await leaser
        # normal; leaser will see child death (check leaser will stay active)
    in leaser loop:
        lock proc-uid
        if no parent: signal child
            # normal; leaser will see child death
        if proc-uid lease ends: return
            # finally will do rest
        if not isAlive child: unlock proc-uid, return
    finally:
        halt child, unlock hook
in child:
    watch:
        if no parent: halt self
            # things went wrong, normally parent waits for child
    purger actor in any child:
        once add proc-uid orig
        try lock all proc-uid by orig-s; delete orig-s for succeeded
    any actor can reassign self to other proc-uid by orig
    assigned actor is activated only on their proc-uid
    non-assigned actor is activated on proc-uid with minimal registration tx-id
 */

/* check:
check
    LateInit: one time, one process
    random txtr -- same
    purging
process death order
    make child sleep in hook;
    case soft kill child
    case soft kill parent
    check leaser will stay active
    check both will die
    check fast unlock
test real
    split brain
    elector node off
    app node off
    child gc

refactor
    C4ELECTOR_PROC_PATH => C4PARENT_PID
    C4APP_CLASS + C4APP_CLASS_INNER -- what for test apps?
move to elector api



 */