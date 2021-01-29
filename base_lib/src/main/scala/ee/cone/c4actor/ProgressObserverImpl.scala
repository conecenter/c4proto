package ee.cone.c4actor

import java.lang.management.ManagementFactory
import java.nio.file.{Files, Path, Paths}
import java.util.UUID
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpRequest.BodyPublishers
import java.net.http.HttpResponse.BodyHandlers

import scala.annotation.tailrec
import scala.compat.java8.FutureConverters
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.Random
import scala.util.control.NonFatal

import com.typesafe.scalalogging.LazyLogging

import ee.cone.c4actor.Types.NextOffset
import ee.cone.c4assemble.Single
import ee.cone.c4di.{c4, c4multi}

////

@c4("ServerCompApp") final class ProgressObserverFactoryImpl(
  inner: TxObserver, execution: Execution, getToStart: DeferredSeq[Executable],
  readyObserverImplFactory: ReadyObserverImplFactory
) extends ProgressObserverFactory {
  def create(endOffset: NextOffset): Observer[RichContext] = {
    val lateExObserver: Observer[RichContext]  = new LateExecutionObserver(execution,getToStart.value,inner.value)
    val readyObserver = readyObserverImplFactory.create(lateExObserver, 0L)
    new ProgressObserverImpl(readyObserver,endOffset)
  }
}

// states:
//   loading
//   loading ready
//   master
// trans:
//   loading -> loading
//   loading -> loading ready
//   loading ready -> loading ready
//   loading ready -> master

class ProgressObserverImpl(inner: Observer[RichContext], endOffset: NextOffset, until: Long=0) extends Observer[RichContext] with LazyLogging {
  def activate(rawWorld: RichContext): Observer[RichContext] =
    if (rawWorld.offset < endOffset) {
      val now = System.currentTimeMillis
      if(now < until) this else {
        logger.debug(s"loaded ${rawWorld.offset}/$endOffset")
        new ProgressObserverImpl(inner, endOffset, now+1000)
      }
    } else {
      logger.info(s"Stats OK -- loaded ALL/$endOffset -- uptime ${ManagementFactory.getRuntimeMXBean.getUptime}ms")
      inner.activate(rawWorld)
    }
}

@c4multi("ServerCompApp") final class ReadyObserverImpl(
  inner: Observer[RichContext], until: Long=0
)(
  config: Config, isMaster: IsMaster,
) extends Observer[RichContext] with LazyLogging {
  private def ignoreTheSamePath(path: Path): Unit = ()
  def activate(rawWorld: RichContext): Observer[RichContext] = {
    if(until == 0) ignoreTheSamePath(Files.write(Paths.get(config.get("C4ROLLING")).resolve("c4is-ready"),Array.empty[Byte]))
    val now = System.currentTimeMillis
    if(now < until) this
    else if(isMaster.get()) {
      logger.info(s"becoming master")
      inner.activate(rawWorld)
    } else {
      logger.debug(s"ready/waiting")
      new ReadyObserverImpl(inner, now+1000)(config,isMaster)
    }
  }

}
/*
@c4assemble("ServerCompApp") class BuildVerAssembleBase(config: ListConfig, execution: Execution){
  def join(
    srcId: SrcId,
    firstborn: Each[S_Firstborn]
  ): Values[(SrcId,TxTransform)] = for {
    path <- config.get("C4BUILD_VER_PATH")
    value <- config.get("C4BUILD_VER_VALUE")
  } yield WithPK(BuildVerTx("BuildVerTx",Paths.get(path),value)(execution))
}
case class BuildVerTx(srcId: SrcId, path: Path, value: String)(execution: Execution) extends TxTransform {
  def transform(local: Context): Context = {
    if(new String(Files.readAllBytes(path), UTF_8) != value) execution.complete()
    SleepUntilKey.set(Instant.ofEpochMilli(System.currentTimeMillis+1000))(local)
  }
}*/

@c4("ServerCompApp") final class LocalElectorDeath(config: ListConfig, execution: Execution) extends Executable with Early {
  def run(): Unit =
    for(path <- config.get("C4ELECTOR_PROC_PATH")) iteration(Paths.get(path))
  @tailrec private def iteration(path: Path): Unit =
    if(Files.notExists(path)) execution.complete() else {
      Thread.sleep(1000)
      iteration(path)
    }
}

////

@c4("ServerCompApp") final class ServerExecutionFilter(inner: ExecutionFilter)
  extends ExecutionFilter(e=>inner.check(e) && e.isInstanceOf[Early])

class LateExecutionObserver(
  execution: Execution, toStart: Seq[Executable], inner: Observer[RichContext]
) extends Observer[RichContext] with LazyLogging {
  def activate(world: RichContext): Observer[RichContext] = {
    logger.info(s"tracking ${toStart.size} late services")
    toStart.filterNot(_.isInstanceOf[Early]).foreach(f => execution.fatal(Future(f.run())(_)))
    inner.activate(world)
  }
}

////

trait IsMaster {
  def get(): Boolean
}

@c4("ServerCompApp") final class IsMasterImpl(
  val promise: Promise[Unit] = Promise()
) extends IsMaster {
  def get(): Boolean = promise.isCompleted
}

@c4("ServerCompApp") final class ElectorSystem(
  config: Config, actorName: ActorName, factory: ElectorWorkerFactory
) extends Executable with Early {
  def run(): Unit = concurrent.blocking {
    val inboxTopicPrefix = config.get("C4INBOX_TOPIC_PREFIX")
    val addresses = config.get("C4ELECTOR_SERVERS").split(",").toList
    val owner = s"${UUID.randomUUID()}"
    def createRequest(address: String, period: String): HttpRequest =
      HttpRequest.newBuilder
        .timeout(java.time.Duration.ofSeconds(1))
        .uri(URI.create(s"$address/lock/$inboxTopicPrefix/${actorName.value}"))
        .headers("x-r-lock-owner",owner,"x-r-lock-period",period)
        .POST(BodyPublishers.noBody()).build()
    val lockRequests = addresses.map(createRequest(_,"10000"))
    val unlockRequests = addresses.map(createRequest(_,"1"))
    val client = HttpClient.newHttpClient
    factory.create(client,lockRequests,unlockRequests).iter(None,Future.successful(None),Nil)
  }
}

@c4multi("ServerCompApp") final class ElectorWorker(
  client: HttpClient, lockRequests: List[HttpRequest], unlockRequests: List[HttpRequest]
)(
  isMaster: IsMasterImpl, execution: Execution
) extends LazyLogging {
  def msNow: Long = System.nanoTime / 1000000

  def send(requests: List[HttpRequest])(implicit ec: ExecutionContext): Future[Boolean] =
    Future.sequence(requests.map(req=>
      FutureConverters.toScala(client.sendAsync(req, BodyHandlers.discarding()))
        .map(resp=>resp.statusCode==200)
        .recover{ case NonFatal(e) =>
          logger.error("elector-post",e)
          false
        }
    )).map{ results =>
      val (ok,nok) = results.partition(r=>r)
      ok.size > nok.size
    }

  def sendMore(last: Future[Option[Long]])(implicit ec: ExecutionContext): Future[Option[Long]] = for {
    wasUntil <- last
    started = msNow
    wasMaster = wasUntil.nonEmpty
    ok <- send(lockRequests)
    until = if(ok) Option(started+10000) else wasUntil
    _ <- if(!ok && !wasMaster) send(unlockRequests) else Future.successful()

  } yield until

  @tailrec def iter(wasUntilOpt: Option[Long], last: Future[Option[Long]], wasUncompleted: List[Future[Option[Long]]]): Unit = {
    val next = sendMore(last)(execution.mainExecutionContext)
    execution.fatal(_=>next)
    Thread.sleep(1000)
    val (completed,uncompleted) =
      (next :: wasUncompleted).partition(_.isCompleted)
    val untilOpt: Option[Long] =
      (completed.flatMap(_.value).flatMap(_.toOption).flatten ::: wasUntilOpt.toList).maxOption
    for(until <- untilOpt) {
      if(wasUntilOpt.isEmpty){
        logger.info("getting master")
        isMaster.promise.success()
      }
      val left = until - msNow
      if(left < 4000){
        logger.error("can not stay master 0")
        execution.fatal(_=>Future.failed(new Exception("can not stay master")))
        logger.error("can not stay master 1")
        Thread.sleep(left)
        Runtime.getRuntime.halt(2)
      }
    }
    Thread.sleep(1000+Random.nextInt(300))
    iter(untilOpt,next,uncompleted)
  }
}