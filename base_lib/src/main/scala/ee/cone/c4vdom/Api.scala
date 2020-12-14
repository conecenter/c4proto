
package ee.cone.c4vdom

//import ee.cone.c4connection_api.EventKey
import java.text.DecimalFormat

import ee.cone.c4vdom.OnChangeMode._
import ee.cone.c4vdom.Types.{ViewRes, _}

import scala.annotation.StaticAnnotation

class c4tags(a: String*) extends StaticAnnotation
class c4tag(a: String*) extends StaticAnnotation
class c4tagSwitch(a: String*) extends StaticAnnotation

trait ToJson {
  def appendJson(builder: MutableJsonBuilder): Unit
}
trait Resolvable extends Product
trait VDomValue extends ToJson with Resolvable

////

trait MutableJsonBuilder extends AbstractMutableJsonBuilder {
  def end(): Unit
  def append(value: String): FinMutableJsonBuilder
  def just: FinMutableJsonBuilder
}
trait FinMutableJsonBuilder extends AbstractMutableJsonBuilder {
  def append(value: String): Unit
}
trait AbstractMutableJsonBuilder {
  def startArray(): Unit
  def startObject(): Unit
  def append(value: BigDecimal, decimalFormat: DecimalFormat): Unit
  def append(value: Int): Unit
  def append(value: Boolean): Unit
}

trait GeneralJsonValueAdapter
trait JsonValueAdapter[-T] extends GeneralJsonValueAdapter {
  def appendJson(value: T, builder: MutableJsonBuilder): Unit
}

////

object Types {
  type VDomKey = String
  type ViewRes = List[ChildPair[_]]
  type VDom[C] = ChildPair[C]
}

trait ChildPair[-C] {
  @deprecated def key: VDomKey // it should be impl details
}

trait ChildPairFactory {
  def apply[C](key: VDomKey, theElement: VDomValue, elements: ViewRes): ChildPair[C]
}
// do not mix grouped and ungrouped elements: cf(cf.group(...) ::: badUngroupedElements)

trait VDomFactory {
  def create[C](key: VDomKey, theElement: VDomValue, elements: ViewRes): VDom[C]
  def addGroup(key: String, groupKey: String, elements: Seq[ChildPair[_]] , res: ViewRes): ViewRes
  def addGroup(key: String, groupKey: String, element: ChildPair[_] , res: ViewRes): ViewRes
}

trait ResolvingVDomValue extends VDomValue {
  def resolve(name: String): Option[Resolvable]
}

////

trait TagStyle {
  def appendStyle(builder: MutableJsonBuilder): Unit
}

////

trait VDomLens[C,I] {
  def of: C=>I
  def modify: (I=>I) => C=>C
  def set: I=>C=>C
}

trait VDomView[State] extends Product {
  def view: State => ViewRes
}

trait VDomSender[State] {
  def branchKey: String
  type Send = Option[(String,String) => State => State]
  def sending: State => (Send,Send)
}

trait VDomMessage {
  def header: String=>String
  def body: Object
}

trait GeneralReceiver extends Resolvable
trait Receiver[State] extends GeneralReceiver {
  type Handler = VDomMessage => State => State
  def receive: Handler
}
// if we want to introduce other type of receiver,
// we can tweak client sender-context to send short path + inner path


trait VDomResolver {
  def resolve(pathStr: String): Option[Resolvable] => Option[Resolvable]
}

trait VDomHandler[State] extends Receiver[State] {
  def seeds: State => List[(String,Product)]
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
sealed abstract class OnChangeMode(val value: String) extends Product
object OnChangeMode {
  case object ReadOnly extends OnChangeMode("")
  case object Send extends OnChangeMode("send")
  case object SendFirst extends OnChangeMode("send_first")
  case object Defer extends OnChangeMode("local")
}

trait TagJsonUtils {
  @deprecated def appendInputAttributes(builder: MutableJsonBuilder, value: String, deferSend: Boolean): Unit =
    appendInputAttributes(builder,value,if(deferSend) Defer else Send)
  def appendValue(builder: MutableJsonBuilder, value: String): Unit
  @deprecated def appendOnChange(builder: MutableJsonBuilder, value: String, deferSend: Boolean, needStartChanging: Boolean): Unit

  def appendInputAttributes(builder: MutableJsonBuilder, value: String, mode: OnChangeMode): Unit

  def jsonValueAdapter[T](inner: (T,MutableJsonBuilder)=>Unit): JsonValueAdapter[T]
}

////

trait OfDiv

@deprecated trait Tags
@deprecated trait TagStyles