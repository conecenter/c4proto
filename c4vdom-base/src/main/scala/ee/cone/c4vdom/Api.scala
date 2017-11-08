
package ee.cone.c4vdom

//import ee.cone.c4connection_api.EventKey
import java.text.DecimalFormat

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
  def append(value: BigDecimal, decimalFormat: DecimalFormat): MutableJsonBuilder
  def append(value: Boolean): MutableJsonBuilder
}

////

object Types {
  type VDomKey = String
  type ViewRes = List[ChildPair[_]]
}

trait ChildPair[-C] {
  def key: VDomKey
}

trait ChildPairFactory {
  def apply[C](key: VDomKey, theElement: VDomValue, elements: ViewRes): ChildPair[C]
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
  def of: C⇒I
  def modify: (I⇒I) ⇒ C⇒C
  def set: I⇒C⇒C
}

trait VDomView[State] extends Product {
  def view: State ⇒ ViewRes
}

trait VDomSender[State] {
  def branchKey: String
  type Send = Option[(String,String) ⇒ State ⇒ State]
  def sending: State ⇒ (Send,Send)
}

trait VDomMessage {
  def header: String⇒String
  def body: Object
}

trait Receiver[State] {
  type Handler = VDomMessage ⇒ State ⇒ State
  def receive: Handler
}

trait VDomHandler[State] extends Receiver[State] {
  def seeds: State ⇒ List[(String,Product)]
}

trait VDomHandlerFactory {
  def create[State](
    sender: VDomSender[State],
    view: VDomView[State],
    vDomUntil: VDomUntil,
    vDomStateKey: VDomLens[State,Option[VDomState]]
  ): VDomHandler[State]
}

case class VDomState(value: VDomValue, until: Long)

trait VDomUntil {
  def get(pairs: ViewRes): (Long, ViewRes)
}

////

trait TagJsonUtils {
  def appendInputAttributes(builder: MutableJsonBuilder, value: String, deferSend: Boolean): Unit
  def appendStyles(builder: MutableJsonBuilder, styles: List[TagStyle]): Unit
}
