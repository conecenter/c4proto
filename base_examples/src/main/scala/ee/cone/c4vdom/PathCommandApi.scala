/*package ee.cone.c4vdom

import ee.cone.c4vdom.Types.VDomKey

t rait PathFactory {
  def path(key: VDomKey, children: ChildPair[OfPath]*): ChildPair[OfPathParent]
  def path(key: VDomKey, children: List[ChildPair[OfPath]]): ChildPair[OfPathParent]
  //
  def M(x:BigDecimal, y:BigDecimal): ChildPair[OfPath]
  def L(x:BigDecimal, y:BigDecimal): ChildPair[OfPath]
  def m(x:BigDecimal, y:BigDecimal): ChildPair[OfPath]
  def l(x:BigDecimal, y:BigDecimal): ChildPair[OfPath]
  def v(y:BigDecimal): ChildPair[OfPath]
  def h(x:BigDecimal): ChildPair[OfPath]
  def z: ChildPair[OfPath]
  def rect(a: Any*): ChildPair[OfPath]
  def ellipse(
    x: BigDecimal, y: BigDecimal, rx: BigDecimal, ry: BigDecimal, rotate: BigDecimal,
    startAngle: BigDecimal, endAngle: BigDecimal, counterclockwise: Boolean
  ): ChildPair[OfPath]
  def text(value: String, x: BigDecimal, y: BigDecimal) //textEx?
  //
  def transform(
    scaleX: BigDecimal, shearY: BigDecimal,
    shearX: BigDecimal, scaleY: BigDecimal,
    translateX: BigDecimal, translateY: BigDecimal
  ): ChildPair[OfPath]
  def translate(x: BigDecimal, y: BigDecimal): ChildPair[OfPath]
  def rotate(v: BigDecimal): ChildPair[OfPath]
}*/
