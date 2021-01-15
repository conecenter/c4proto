package ee.cone.c4ui

trait CSSClassName extends Product {
  def name: String
}
case object NoCSSClassName extends CSSClassName { def name: String = "" }
