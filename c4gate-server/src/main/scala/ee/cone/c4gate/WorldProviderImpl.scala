package ee.cone.c4gate

import ee.cone.c4actor.Types.NextOffset
import ee.cone.c4actor._
import ee.cone.c4proto.c4component

import scala.collection.immutable
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, ExecutionContext, Future, Promise}

@c4component("WorldProviderApp") class WorldProviderInitialObserverProvider(
  worldProvider: WorldProvider
) extends InitialObserverProvider(Option(worldProvider match { case w: Observer[RichContext] => w }))

@c4component("WorldProviderApp") class WorldProviderImpl(qMessages: QMessages, execution: Execution, statefulReceiverFactory: StatefulReceiverFactory)(
  receiverPromise: Promise[StatefulReceiver[WorldMessage]] = Promise()
) extends WorldProvider with Observer[RichContext] with Executable {
  def sync(localOpt: Option[Context])(implicit executionContext: ExecutionContext): Future[Context] = {
    val offsetOpt = localOpt.map(local => ReadAfterWriteOffsetKey.of(qMessages.send(local)))
    val promise = Promise[Context]()
    for {
      receiver <- receiverPromise.future
      res <- {
        receiver.send(new WorldConsumerMessage(promise,offsetOpt))
        promise.future
      }
    } yield res
  }
  def activate(world: RichContext): immutable.Seq[Observer[RichContext]] = {
    Await.result(receiverPromise.future, Duration.Inf).send(new WorldProviderMessage(world))
    List(this)
  }
  def run(): Unit = execution.fatal { implicit ec =>
    val receiverF = statefulReceiverFactory.create(List(new WorldProviderReceiverImpl(None,Nil)))
    receiverPromise.completeWith(receiverF)
    receiverF
  }
}

class WorldProviderReceiverImpl(localOpt: Option[Context], waitList: List[WorldConsumerMessage]) extends Observer[WorldMessage] {
  def activate(message: WorldMessage): immutable.Seq[Observer[WorldMessage]] = message match {
    case incoming: WorldProviderMessage =>
      val global = incoming.global
      val(toHold,toResolve) = waitList.partition(sm=>sm.offsetOpt.exists(global.offset < _))
      val local = new Context(global.injected, global.assembled, global.executionContext, Map.empty)
      toResolve.foreach(_.promise.success(local))
      List(new WorldProviderReceiverImpl(Option(local), toHold))
    case incoming: WorldConsumerMessage if incoming.offsetOpt.isEmpty && localOpt.nonEmpty =>
      incoming.promise.success(localOpt.get)
      List(this)
    case incoming: WorldConsumerMessage =>
      List(new WorldProviderReceiverImpl(localOpt, incoming :: waitList))
  }
}

sealed trait WorldMessage
class WorldConsumerMessage(val promise: Promise[Context], val offsetOpt: Option[NextOffset]) extends WorldMessage
class WorldProviderMessage(val global: RichContext) extends WorldMessage
