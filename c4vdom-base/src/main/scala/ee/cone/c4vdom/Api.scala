
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

trait VDomLens[C,I] {
  def of(container: C): I
  def transform(f: I⇒I)(container: C): C
}

trait RootView[State] {
  def view(state: State): (List[ChildPair[_]], Long)
}

trait CurrentVDom[State] {
  def fromAlien: (String⇒Option[String]) ⇒ State ⇒ State
  def toAlien: State ⇒ (State,List[(String,String)])
}

case class VDomState(
    value: VDomValue,
    until: Long,
    hashOfLastView: String, hashFromAlien: String, hashTarget: String,
    ackFromAlien: List[String]
)

trait OnClickReceiver[State] {
  def onClick: Option[State ⇒ State]
}

trait OnChangeReceiver[State] {
  def onChange: Option[String ⇒ State ⇒ State]
}

////

trait TagJsonUtils {
  def appendInputAttributes(builder: MutableJsonBuilder, value: String, deferSend: Boolean): Unit
}
