
package ee.cone.c4actor

import java.util.concurrent._

object Async {
  def run(executor: Executor)(body: ()⇒Unit)(fail: Throwable⇒Unit): Unit =
    CompletableFuture.runAsync(new Runnable {
      def run(): Unit = body()
    },executor).exceptionally(new java.util.function.Function[Throwable, Void] {
      def apply(t: Throwable): Void = fail(t).asInstanceOf[Void]
    })
}

class ExecutionImpl(
  toStart: List[Executable]
) extends Executable {
  def run(ctx: ExecutionContext): Unit = {
    println(s"tracking ${toStart.size} services")
    toStart.foreach(f ⇒
      Async.run(ctx.executors)(()⇒f.run(ctx))(t⇒ctx.complete(Option(t)))
    )
  }
}

object ExecutionContextFactory {
  private def onShutdown(hint: String, f: ()⇒Unit): Unit =
    Runtime.getRuntime.addShutdownHook(new Thread(){
      override def run(): Unit = {
        //println(s"hook-in $hint")
        f()
        //println(s"hook-out $hint")
      }
    })
  private def complete(error: Option[Throwable]): Unit =
    Async.run(Executors.newFixedThreadPool(1)){ () ⇒ // exit from pooled thread will block itself
      error.foreach(_.printStackTrace())
      println("exiting")
      System.exit(error.size)
    }{ error ⇒ error.printStackTrace() }


  def create(): ExecutionContext = {
    val pool = Executors.newCachedThreadPool() //newWorkStealingPool
    onShutdown("Pool",()⇒{
      val tasks = pool.shutdownNow()
      pool.awaitTermination(Long.MaxValue,TimeUnit.SECONDS)
    })
    new ExecutionContext(pool, onShutdown, complete)
  }
}

object ServerMain {
  def main(args: Array[String]): Unit = try {
    Trace {
      val ctx = ExecutionContextFactory.create()
      val app = Option(Class.forName(args(0))).get.newInstance().asInstanceOf[ServerApp]
      app.execution.run(ctx)
      //println("main is about to sleep")
      Thread.sleep(Long.MaxValue) //ctx.serving.get()
    }
  } finally System.exit(0)
}

class EnvConfigImpl extends Config {
  def get(key: String): String =
    Option(System.getenv(key)).getOrElse(throw new Exception(s"Need ENV: $key"))
}

