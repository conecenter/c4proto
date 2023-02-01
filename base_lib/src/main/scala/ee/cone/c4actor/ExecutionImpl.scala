
package ee.cone.c4actor

import java.lang.Thread.UncaughtExceptionHandler
import java.util.concurrent.ForkJoinPool.ForkJoinWorkerThreadFactory
import java.util.concurrent.{ExecutorService, Executors, ForkJoinPool, ForkJoinWorkerThread, ThreadFactory, TimeUnit}
import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4assemble.Single
import ee.cone.c4di.c4

import scala.concurrent.duration.Duration
import scala.concurrent.{Await, ExecutionContext, Future, Promise}
import scala.util.Try
import scala.util.control.NonFatal

object VMExecution {
  def onShutdown(hint: String, f: () => Unit): ()=>Unit = {
    val thread = new Thread(new ShutdownRunnable(hint,f))
    Runtime.getRuntime.addShutdownHook(thread)
    () => try {
      val ok = Runtime.getRuntime.removeShutdownHook(thread) // do we need to report?
    } catch {
      case e: IllegalStateException => ()
    }
  }
  def newExecutorService(prefix: String, threadCount: Option[Int]): ExecutorService = {
    threadCount.fold{
      val defaultThreadFactory = Executors.defaultThreadFactory()
      val threadFactory = new RThreadFactory(defaultThreadFactory,prefix)
      Executors.newCachedThreadPool(threadFactory) //newWorkStealingPool
    }{ fixedThreadCount =>
      new RForkJoinPool(prefix,0,fixedThreadCount)()
      //@SuppressWarnings(Array("org.wartremover.warts.Null")) val handler: UncaughtExceptionHandler = null
    }
  }
  def setup[T<:Thread](prefix: String, thread: T): T = {
    thread.setName(s"$prefix${thread.getName}")
    thread.setUncaughtExceptionHandler(new RUncaughtExceptionHandler(thread.getUncaughtExceptionHandler))
    thread
  }
  def success[T](promise: Promise[T], value: T): Unit = ignorePromise(promise.success(value))
  private def ignorePromise[T](value: Promise[T]): Unit = () //same promise?
}

class ShutdownRunnable(hint: String, f: () => Unit) extends Runnable with LazyLogging {
  def run(): Unit = {
    logger.debug(s"hook-in $hint")
    f()
    logger.debug(s"hook-out $hint")
  }
}

class RThreadFactory(inner: ThreadFactory, prefix: String) extends ThreadFactory {
  def newThread(runnable: Runnable): Thread =
    VMExecution.setup(prefix,inner.newThread(runnable))
}

class RForkJoinWorkerThreadFactory(inner: ForkJoinWorkerThreadFactory, prefix: String) extends ForkJoinWorkerThreadFactory {
  def newThread(pool: ForkJoinPool): ForkJoinWorkerThread =
    VMExecution.setup(prefix,inner.newThread(pool))
}

@SuppressWarnings(Array("org.wartremover.warts.Null"))
class RForkJoinPool(prefix: String, depth: Int, threadCount: Int)(
  threadFactory: RForkJoinWorkerThreadFactory =
    new RForkJoinWorkerThreadFactory(
      ForkJoinPool.defaultForkJoinWorkerThreadFactory,s"$prefix$depth-"
    )
) extends ForkJoinPool(threadCount,threadFactory,null,false){
  lazy val deeper: ExecutionContext = ExecutionContext.fromExecutor(
    new RForkJoinPool(prefix, depth+1, threadCount)()
  )
}

/*
default exceptions in futures:
  Exception -- onComplete Failure
  no-fatal Error -- boxed -- onComplete Failure
  fatal error -- no catch by scala, no onComplete, passed to executor -- stderr deep by executor, no exit jvm
*/
class RUncaughtExceptionHandler(inner: UncaughtExceptionHandler) extends UncaughtExceptionHandler {
  def uncaughtException(thread: Thread, throwable: Throwable): Unit =
    try inner.uncaughtException(thread,throwable) finally System.exit(1)
}

@c4("VMExecutionApp") final class DefExecutionFilter extends ExecutionFilter(_=>true)

@c4("VMExecutionApp") final class VMExecution(getToStart: DeferredSeq[Executable], executionFilter: ExecutionFilter)(
  txThreadPool: ExecutorService = VMExecution.newExecutorService("tx-",Option(Runtime.getRuntime.availableProcessors)),
  ubThreadPool: ExecutorService = VMExecution.newExecutorService("ub-",None)
)(
  mainExecutionContext: ExecutionContext = ExecutionContext.fromExecutor(txThreadPool),
  unboundedExecutionContext: ExecutionContext = ExecutionContext.fromExecutor(ubThreadPool),
) extends Execution with LazyLogging {
  def run(): Unit = {
    val toStart = getToStart.value.filter(executionFilter.check)
    logger.info(s"tracking ${toStart.size} services")
    toStart.foreach(f => unboundedFatal(Future(f.run())(_)))
  }
  def unboundedFatal[T](future: ExecutionContext=>Future[T]): Future[T] =
    fatal(future,unboundedExecutionContext)
  private def ignoreRootFuture[T](value: Future[T]): Unit = ()
  def fatal[T](future: ExecutionContext=>Future[T]): Unit =
    ignoreRootFuture(fatal(future, mainExecutionContext))
  def fatal[T](future: ExecutionContext=>Future[T], ec: ExecutionContext): Future[T] = future(ec).recover{
    case NonFatal(e) =>
      System.err.println(s"FATAL ${e.getMessage}")
      e.printStackTrace()
      System.exit(1)
      throw e
  }(ec)
  def onShutdown(hint: String, f: () => Unit): ()=>Unit =
    VMExecution.onShutdown(hint,f)
  def complete(): Unit = { // exit from pooled thread will block itself
    logger.info("exiting")
    val runtime = Runtime.getRuntime
    logger.info(s"totalMemory:${runtime.totalMemory} freeMemory:${runtime.freeMemory}")
    System.exit(0)
  }
  def skippingFuture[T](value: T): SkippingFuture[T] =
    new SkippingFutureImpl[T](Future.successful(value),Promise[Unit]())(mainExecutionContext)
  def newExecutorService(prefix: String, threadCount: Option[Int]): ExecutorService =
    VMExecution.newExecutorService(prefix,threadCount)
  def success[T](promise: Promise[T], value: T): Unit =
    VMExecution.success(promise,value)
  def aWait[T](body: ExecutionContext=>Future[T]): T = {
    val ec = Thread.currentThread match {
      case t: ForkJoinWorkerThread => t.getPool match{
        case p: RForkJoinPool => p.deeper
        case _ => mainExecutionContext
      }
      case _ => mainExecutionContext
    }
    Await.result(body(ec), Duration.Inf)
  }
}

class SkippingFutureImpl[T](inner: Future[T], isNotLast: Promise[Unit])(implicit executionContext: ExecutionContext) extends SkippingFuture[T] with LazyLogging {
  /*
  private def canSkip[T](future: Future[T]) = future match {
    case a: AtomicReference[_] => a.get() match {
      case s: Seq[_] => s.nonEmpty
      case u => logger.warn(s"no skip rule for inner ${u.getClass.getName}"); false
    }
    case u => logger.warn(s"no skip rule for outer ${u.getClass.getName}"); false
  }*/
  def map(body: T => T): SkippingFuture[T] = {
    /*
    lazy val nextFuture: Future[T] = inner.map(from =>
      if(canSkip(nextFuture)) from else body(from)
    )
    new SkippingFutureImpl(nextFuture)
    */
    val nextIsNotLast = Promise[Unit]()
    val nextFuture = inner.map(from =>
      if(nextIsNotLast.isCompleted) from else body(from)
    )
    VMExecution.success(isNotLast,())
    new SkippingFutureImpl(nextFuture,nextIsNotLast)
  }
  def value: Option[Try[T]] = inner.value
  //f[scala.concurrent.impl.Promise.Transformation]
}

abstract class BaseServerMain(app: ExecutableApp){
  def main(args: Array[String]): Unit = try {
    Trace { // keep Trace here <-- execution construction may silently fail
      //ExecutionRun(app)
      println("this should be instant (actually not)")
      Option(app).collect{ case pApp: PrepareApp => pApp }.foreach{ pApp => pApp.prepare() }
      val execution = app.execution
      println("before run")
      execution.run()
      //println("main is about to sleep")
      Thread.sleep(Long.MaxValue) //ctx.serving.get()
    }
  } finally System.exit(0)
}

object ServerMain extends BaseServerMain(
  Option(Class.forName((new SingleConfigImpl(new EnvConfigImpl)).get("C4APP_CLASS"))).get
    .newInstance().asInstanceOf[ExecutableApp]
)

@c4("EnvConfigCompApp") final class EnvConfigImpl extends ListConfig {
  def get(key: String): List[String] = Option(System.getenv(key)).toList
}
@c4("EnvConfigCompApp") final class SingleConfigImpl(inner: ListConfig) extends Config {
  def get(key: String): String =
    Single[String](inner.get(key), (l:Seq[String])=>new Exception(s"Need single ENV: $key: $l"))
}

@c4("EnvConfigCompApp") final class ActorNameImpl(config: Config)
  extends ActorName(config.get("C4STATE_TOPIC_PREFIX"))

@c4("CatchNonFatalApp") final class CatchNonFatalImpl extends CatchNonFatal with LazyLogging {
  def apply[T](aTry: =>T)(getHint: =>String)(aCatch: Throwable=>T): T = try { aTry } catch {
    case NonFatal(e) =>
      logger.error(getHint,e)
      aCatch(e)
  }
}