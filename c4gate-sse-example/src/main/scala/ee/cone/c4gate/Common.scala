
package ee.cone.c4gate

import ee.cone.c4actor.{c4component, listed}
import ee.cone.c4assemble._
import ee.cone.c4ui.FromAlienTaskAssemble

@c4component @listed case class ReactAppAssemble(
  inner: Assemble = FromAlienTaskAssemble("/react-app.html")
) extends Assemble {
  override def dataDependencies = inner.dataDependencies
}