package ee.cone.c4actor

import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor.QProtocol.N_Update
import ee.cone.c4assemble._
import ee.cone.c4actor._
import ee.cone.c4di._

import scala.collection.immutable.{Map, Seq}

object ComponentProvider {
  private def toTypeKey[T](cl: Class[T]): TypeKey =
    CreateTypeKey(cl,cl.getSimpleName,Nil)
  def provide[T<:Object](cl: Class[T], get: ()=>Seq[T]): Component =
    new Component(toTypeKey(cl),None,Nil,_=>get())
  def resolveSingle[T](cl: Class[T])(componentRegistry: ComponentRegistry): T =
    componentRegistry.resolve(cl,Nil).value match {
      case Seq(r) => r
      case r => throw new Exception(s"external resolution of $cl fails with $r")
    }
}
trait ComponentProviderApp {
  def componentRegistry: ComponentRegistry
  def resolveSingle[T](cl: Class[T]): T = ComponentProvider.resolveSingle(cl)(componentRegistry)
}

abstract class AppChecker {
  def executeFor(app: ComponentProviderApp): Unit
}

import ComponentProvider._

trait ToStartApp extends ComponentsApp {
  private lazy val executableComponent = provide(classOf[Executable], ()=>toStart)
  override def components: List[Component] = executableComponent :: super.components
  def toStart: List[Executable] = Nil
}

trait AssemblesApp extends ComponentsApp {
  private lazy val assembleComponent = provide(classOf[Assemble], ()=>assembles)
  override def components: List[Component] = assembleComponent :: super.components
  def assembles: List[Assemble] = Nil
}

trait ToInjectApp extends ComponentsApp {
  private lazy val toInjectComponent = provide(classOf[ToInject], ()=>toInject)
  override def components: List[Component] = toInjectComponent :: super.components
  def toInject: List[ToInject] = Nil
}

@deprecated trait PreHashingApp

trait ServerApp extends ServerCompApp with RichDataApp with DeadlockDetectApp //e-only

trait TestVMRichDataApp extends TestVMRichDataCompApp
  with RichDataApp
  with ToStartApp
// extra-only
  //actorName: String = getClass.getName

@deprecated trait TreeIndexValueMergerFactoryApp

trait RichDataAppBase extends RichDataCompApp
  with DefaultKeyFactoryApp
  with DefaultUpdateProcessorApp
  with ExpressionsDumpersApp
  with ComponentProviderApp
  with AssemblesApp

abstract class GeneralCompatHolder {
  def value: Any
}
class CompatHolder[T](val value: T) extends GeneralCompatHolder

@c4("RichDataApp") final class ModelConditionFactoryHolder(value: ModelConditionFactory[Unit])
  extends CompatHolder[ModelConditionFactory[Unit]](value)

trait DefaultKeyFactoryApp extends ComponentsApp {
  def origKeyFactoryOpt: Option[KeyFactory] = None
  private lazy val origKeyFactoryComponent =
    provide(classOf[OrigKeyFactoryProposition],()=>origKeyFactoryOpt.map(new OrigKeyFactoryProposition(_)).toList)
  override def components: List[Component] = origKeyFactoryComponent :: super.components
}

trait DefaultUpdateProcessorApp extends ComponentsApp {
  def updateProcessor: UpdateProcessor = new DefaultUpdateProcessor
  private lazy val updateProcessorComponent =
    provide(classOf[UpdateProcessor],()=>List(updateProcessor))
  override def components: List[Component] = updateProcessorComponent :: super.components
}

class ExpressionsDumperHolder(value: ExpressionsDumper[Unit])
  extends CompatHolder[ExpressionsDumper[Unit]](value)

@c4("ExpressionsDumpersApp") final class ExpressionsDumpersProvider(holders: List[ExpressionsDumperHolder]){
  @provide def get: Seq[ExpressionsDumper[Unit]] = holders.map(_.value)
}

trait ExpressionsDumpersAppBase extends ComponentsApp {
  private lazy val expressionsDumpersComponent = provide(classOf[ExpressionsDumperHolder], ()=>expressionsDumpers.map(new ExpressionsDumperHolder(_)))
  override def components: List[Component] = expressionsDumpersComponent :: super.components
  def expressionsDumpers: List[ExpressionsDumper[Unit]] = Nil
}

trait UMLClientsApp {
  lazy val umlExpressionsDumper: ExpressionsDumper[String] = UMLExpressionsDumper
}

trait EnvConfigApp extends EnvConfigCompApp

trait UpdatesProcessorsApp extends ComponentsApp {
  private lazy val processorsComponent = provide(classOf[UpdatesPreprocessor], ()=>processors)
  override def components: List[Component] = processorsComponent :: super.components
  def processors: List[UpdatesPreprocessor] = Nil
}

//// injectable api
@deprecated trait ToInject {
  def toInject: List[Injectable]
}
class Injectable(val pair: (SharedComponentKey[_],Object))
trait InjectableGetter[C,I] extends Getter[C,I] {
  def set: I => List[Injectable]
}
@deprecated abstract class SharedComponentKey[D_Item<:Object] extends InjectableGetter[Context,D_Item] with LazyLogging {
  def of: Context => D_Item = context => Single(SharedContextKey.of(context)) match {
    case r: ToInjectRegistry =>
      r.values.getOrElse(this, throw new Exception(s"$this was not injected")).asInstanceOf[D_Item]
  }
  def set: D_Item => List[Injectable] = item => {
    logger.debug(s"injecting ${getClass.getName}")
    List(new Injectable((this,item)))
  }
}
//// injectable impl
object Merge {
  def apply[A](path: List[Any], values: List[A]): A =
    if(values.size <= 1) Single(values)
    else {
      val maps = values.collect{ case m: Map[_,_] => m.toList }
      assert(values.size == maps.size, s"can not merge $values of $path")
      maps.flatten
        .groupBy(_._1).transform((k,kvs)=>Merge(k :: path, kvs.map(_._2)))
        .asInstanceOf[A]
    }
}
@c4("RichDataApp") final class ToInjectRegistry(
  toInjects: List[ToInject],
)(
  val values: Map[SharedComponentKey[_], Object] = {
    val injectedList = for{
      toInject <- toInjects
      injected <- toInject.toInject
    } yield Map(injected.pair)
    if(injectedList.isEmpty) Map.empty else Merge(Nil,injectedList)
  }
) extends Injected
//// TxAdd impl
@c4("RichDataApp") final class TxAddInject(
  txAdd: LTxAdd,
  rawTxAdd: RawTxAdd,
) extends ToInject {
  def toInject: List[Injectable] =
    TxAddKey.set(txAdd) ::: WriteModelAddKey.set(rawTxAdd.add)
}
@deprecated case object TxAddKey extends SharedComponentKey[LTxAdd]
@deprecated object TxAdd {
    def apply[M<:Product](out: Seq[LEvent[M]]): Context=>Context = context =>
      TxAddKey.of(context).add(out)(context)
}
@deprecated case object WriteModelAddKey extends SharedComponentKey[Seq[N_Update]=>Context=>Context]
////
@deprecated case object SendToAlienKey extends SharedComponentKey[(Seq[String],String,String)=>Context=>Context]
