package ee.cone.c4proto

import java.util.concurrent.{ExecutorService, Executors, Future, TimeUnit}

class TwoPhaseStart(executors: ExecutorService, toStart: List[CanStart]) extends Runnable {
  def run(): Unit = {
    toStart.filter(_.early.nonEmpty).foreach(_.start(executors))
    while(toStart.flatMap(_.early).exists(e ⇒ !e.isReady)) Thread.sleep(100)
    toStart.filter(_.early.isEmpty).foreach(_.start(executors))
  }
}

class ExecutionImpl(
  toStart: List[CanStart]
) extends Runnable {
  private def sleep() = Thread.sleep(1000)
  def run(): Unit = {
    val pool = Executors.newCachedThreadPool() //newWorkStealingPool
    OnShutdown(()⇒{
      pool.shutdown()
      pool.awaitTermination(Long.MaxValue,TimeUnit.SECONDS)
    })
    pool.submit(new TwoPhaseStart(pool, toStart))
    while(toStart.collectFirst{
      case s: CanFail if s.isDone ⇒ println(s"$s is done"); sleep(); s
      case _ ⇒ sleep()
    }.isEmpty){}
  }
}

class ServerFactoryImpl extends ServerFactory {
  def toServer(runnable: Runnable): CanStart = new CanStartImpl(runnable)
}

class CanStartImpl(runnable: Runnable) extends CanStart with CanFail {
  private var future: Option[Future[_]] = None
  def start(pool: ExecutorService): Unit = synchronized{
    future = Option(pool.submit(runnable))
  }
  def isDone: Boolean = synchronized{
    future.exists(_.isDone)
  }
  def early: Option[ShouldStartEarly] =
    Some(runnable).collect{ case s: ShouldStartEarly ⇒ s }
}
