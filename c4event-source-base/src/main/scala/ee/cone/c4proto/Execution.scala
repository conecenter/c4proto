package ee.cone.c4proto

import java.util.concurrent.{ExecutorService, Executors, Future, TimeUnit}

class ExecutionImpl(
  toStart: List[CanStart]
) extends Executable {
  private def sleep() = Thread.sleep(1000)
  def run(ctx: ExecutionContext): Unit = {
    println(s"tracking ${toStart.size} services")
    ctx.executors.submit(new Runnable{
      def run(): Unit = Trace {
        toStart.filter(_.early.nonEmpty).foreach(_.start(ctx))
        while(toStart.flatMap(_.early).exists(e ⇒ !e.isReady)) Thread.sleep(100)
        toStart.filter(_.early.isEmpty).foreach(_.start(ctx))
      }
    })
    while(toStart.collectFirst{
      case s: CanFail if s.isDone ⇒
        println(s"$s is done")
        sleep()
        s
    }.isEmpty) sleep()
  }

}

class Main(f: ExecutionContext⇒Unit) {
  private def onShutdown(f: ()⇒Unit): Unit =
    Runtime.getRuntime.addShutdownHook(new Thread(){
      override def run(): Unit = f()
    })
  def main(args: Array[String]): Unit = try {
    Trace {
      val pool = Executors.newCachedThreadPool() //newWorkStealingPool
      onShutdown(()⇒{
        pool.shutdown()
        pool.awaitTermination(Long.MaxValue,TimeUnit.SECONDS)
      })
      f(new ExecutionContext(pool,onShutdown))
    }
  } finally System.exit(0)
}

class ServerFactoryImpl extends ServerFactory {
  def toServer(runnable: Executable): CanStart = new CanStartImpl(runnable)
}

class CanStartImpl(runnable: Executable) extends CanStart with CanFail {
  private var future: Option[Future[_]] = None
  def start(ctx: ExecutionContext): Unit = synchronized{
    future = Option(ctx.executors.submit(new Runnable{
      def run(): Unit = Trace {
        runnable.run(ctx)
      }
    }))
  }
  def isDone: Boolean = synchronized{
    future.exists(_.isDone)
  }
  def early: Option[ShouldStartEarly] =
    Some(runnable).collect{ case s: ShouldStartEarly ⇒ s }
}
