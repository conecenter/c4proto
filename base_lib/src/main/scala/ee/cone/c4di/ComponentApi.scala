
package ee.cone.c4di

import scala.annotation.StaticAnnotation
import scala.collection.immutable.Seq

object Types {
  type ComponentFactory[T] = Seq[TypeKey]=>Seq[T]
}

object CreateTypeKey { // to use mostly from generated code
  def apply(cl: Class[_], alias: String, args: List[TypeKey]): TypeKey =
    Value(cl.getName, alias, args)(cl)
  private case class Value(clName: String, alias: String, args: List[TypeKey])(val cl: Class[_]) extends TypeKey {
    def copy(alias: String, args: List[TypeKey]): TypeKey =
      Value(clName,alias,args)(cl)
  }
}
trait TypeKey extends Product {
  def cl: Class[_]
  def clName: String
  def args: List[TypeKey]
  def alias: String
  def copy(alias: String = alias, args: List[TypeKey] = args): TypeKey
}

class c4(apps: String*) extends StaticAnnotation
class provide extends StaticAnnotation
class c4multi(apps: String*) extends StaticAnnotation

trait AbstractComponents {
  def components: Seq[Component]
}
class Component(val out: TypeKey, val nonFinalOut: Option[TypeKey], val in: Seq[TypeKey], val create: Seq[Object]=>Seq[Object]) extends AbstractComponents {
  def components: Seq[Component] = Seq(this)
}
/*
abstract class Components(componentsList: Seq[AbstractComponents]) extends AbstractComponents {
  def components: Seq[Component] = componentsList.flatMap(_.components)
}*/
trait ComponentsApp extends AbstractComponents {
  def components: List[Component] = Nil
}

class c4app extends StaticAnnotation

/*
abstract class Main(app: ExecutableApp){
  def main(args: Array[String]): Unit = app.execution.run()
}

t rait ExecutableApp {
  def execution: Runnable // we need this while we have componentRegistry.resolve to avoid 2 componentRegistry-s
}*/
/* target (w/o resolve):
object ExecutionRun {
  def apply(app: AbstractComponents): Unit = ComponentRegistry(app).resolveSingle(classOf[Execution])
}*/

abstract class AutoMixer(val components: List[Component], val dependencies: List[AutoMixer])

trait GeneralC4Factory0
trait GeneralC4Factory1
trait GeneralC4Factory2
trait GeneralC4Factory3
trait C4Factory0[+Out] { def create(): Out }
trait C4Factory1[In,+Out] { def create(in: In): Out }
trait C4Factory2[In1,In2,+Out] { def create(in1: In1, in2: In2): Out }
trait C4Factory3[In1,In2,In3,+Out] { def create(in1: In1, in2: In2, in3: In3): Out }

class c4ignoreProductCheck extends StaticAnnotation