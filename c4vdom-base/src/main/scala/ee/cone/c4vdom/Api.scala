
package ee.cone.c4vdom

//import ee.cone.c4connection_api.EventKey
import ee.cone.c4vdom.Types._

trait ToJson {
  def appendJson(builder: MutableJsonBuilder): Unit
}
trait VDomValue extends ToJson

////

trait MutableJsonBuilder {
  def startArray(): MutableJsonBuilder
  def startObject(): MutableJsonBuilder
  def end(): MutableJsonBuilder
  def append(value: String): MutableJsonBuilder
  def append(value: Boolean): MutableJsonBuilder
}

////

object Types {
  type VDomKey = String
}

trait ChildPair[C] {
  def key: VDomKey
}

trait ChildPairFactory {
  def apply[C](key: VDomKey, theElement: VDomValue, elements: List[ChildPair[_]]): ChildPair[C]
}

////

abstract class TagName(val name: String)

trait TagAttr
trait TagStyle extends TagAttr {
  def appendStyle(builder: MutableJsonBuilder): Unit
}

trait Color {
  def value: String
}

////

trait CurrentView {
  def invalidate(): Unit
  def until(value: Long): Unit
  def relocate(value: String): Unit
}

trait OnClickReceiver {
  def onClick: Option[()⇒Unit]
}

trait OnChangeReceiver {
  def onChange: Option[String⇒Unit]
}

trait OnResizeReceiver{
  def onResize: Option[String⇒Unit]
}

////

trait TagJsonUtils {
  def appendInputAttributes(builder: MutableJsonBuilder, value: String, deferSend: Boolean): Unit
}
