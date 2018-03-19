package ee.cone.c4vdom

trait TagStyles {
  def none: TagStyle

  def displayInlineBlock: TagStyle
  def displayBlock: TagStyle
  def displayCell: TagStyle
  def displayTable: TagStyle
  def displayFlex: TagStyle

  def margin: Int⇒TagStyle
  def marginLeftAuto: TagStyle
  def marginRightAuto: TagStyle
  def padding: Int⇒TagStyle

  def width: Int⇒TagStyle
  def minWidth: Int⇒TagStyle
  def maxWidth: Int⇒TagStyle
  def widthAll: TagStyle
  def widthAllBut: Int⇒TagStyle
  def height: Int⇒TagStyle
  def minHeight: Int⇒TagStyle
  def heightAll: TagStyle

  def alignLeft: TagStyle
  def alignCenter: TagStyle
  def alignRight: TagStyle
  def alignBottom: TagStyle
  def alignMiddle: TagStyle
  def alignTop: TagStyle

  def relative: TagStyle
  def color: Color⇒TagStyle
  def noWrap: TagStyle
  def flexWrap: TagStyle
  def fontSize: Int⇒TagStyle
}
