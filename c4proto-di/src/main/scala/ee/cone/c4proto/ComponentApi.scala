
package ee.cone.c4proto

import scala.annotation.StaticAnnotation
import scala.collection.immutable.Seq

case class TypeKey(clName: String, alias: String, args: List[TypeKey])

class c4(apps: String*) extends StaticAnnotation
class provide extends StaticAnnotation

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