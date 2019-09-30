
package ee.cone.c4proto

import scala.annotation.StaticAnnotation
import scala.collection.immutable.Seq

case class TypeKey(clName: String, alias: String, args: List[TypeKey])

class c4component(apps: String*) extends StaticAnnotation

trait AbstractComponents {
  def components: Seq[Component]
}
class Component(val out: Seq[TypeKey], val in: Seq[TypeKey], val create: Seq[Object]=>Seq[Object]) extends AbstractComponents {
  def components: Seq[Component] = Seq(this)
}
/*
abstract class Components(componentsList: Seq[AbstractComponents]) extends AbstractComponents {
  def components: Seq[Component] = componentsList.flatMap(_.components)
}*/
trait ComponentsApp extends AbstractComponents {
  def components: List[Component] = Nil
}