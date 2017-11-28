
package ee.cone.c4gate

import ee.cone.c4actor.{c4component, listed}
import ee.cone.c4assemble._
import ee.cone.c4ui._

@c4component @listed case class ReactAppAssemble()(
  wrap: FromAlienTaskAssemble ⇒ Assembled
)(
  inner: Assembled = wrap(new FromAlienTaskAssemble("/react-app.html"))
) extends Assembled {
  def dataDependencies: List[DataDependencyTo[_]] = inner.dataDependencies
}