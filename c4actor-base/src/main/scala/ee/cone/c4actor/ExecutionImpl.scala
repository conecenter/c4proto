
package ee.cone.c4actor

import java.util.concurrent._

class ExecutionImpl(
  toStart: List[Executable]
) extends Executable {
  def run(ctx: ExecutionContext): Unit = {
    println(s"tracking ${toStart.size} services")
    toStart.foreach(f ⇒ CompletableFuture.runAsync(new Runnable {
      def run(): Unit = f.run(ctx)
    },ctx.executors).exceptionally(new java.util.function.Function[Throwable, Void] {
      def apply(t: Throwable): Void = {
        ctx.serving.completeExceptionally(t)
        ().asInstanceOf[Void]
      }
    }))
    ctx.serving.get()
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
      f(new ExecutionContext(pool, onShutdown, new CompletableFuture))
    }
  } finally System.exit(0)
}


class EnvConfigImpl extends Config {
  def get(key: String): String =
    Option(System.getenv(key)).getOrElse(throw new Exception(s"Need ENV: $key"))
}

