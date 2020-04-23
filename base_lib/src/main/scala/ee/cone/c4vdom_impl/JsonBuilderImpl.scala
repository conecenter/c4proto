package ee.cone.c4vdom_impl

import java.text.DecimalFormat

import ee.cone.c4vdom.{FinMutableJsonBuilder, MutableJsonBuilder, VDomValue}

object JsonToStringImpl extends JsonToString {
  def apply(value: VDomValue): String = {
    val builder = new JsonBuilderImpl()
    value.appendJson(builder)
    builder.result.toString
  }
}

class FinJsonBuilderImpl(outer: JsonBuilderImpl) extends FinMutableJsonBuilder {
  def startArray(): Unit = outer.startArray()
  def startObject(): Unit = outer.startObject()
  def append(value: String): Unit = outer.appendStr(value)
  def append(value: BigDecimal, decimalFormat: DecimalFormat): Unit = outer.append(value,decimalFormat)
  def append(value: Boolean): Unit = outer.append(value)
}

@SuppressWarnings(Array("org.wartremover.warts.Var","org.wartremover.warts.While")) class JsonBuilderImpl(val result: StringBuilder = new StringBuilder) extends MutableJsonBuilder {
  val just = new FinJsonBuilderImpl(this)

  private var checkStack: Long = 1L
  private var isOddStack: Long = 0L
  private var nonEmptyStack: Long = 0L
  private var isObjectStack: Long = 0L

  private def is(stack: Long) = (stack & 1L) != 0L
  private def objectNeedsValue = is(isObjectStack) && is(isOddStack)

  private def push(isObjectFlag: Long): Unit = {
    checkStack <<= 1
    if(checkStack == 0) Never() //maxDepth
    isOddStack <<= 1
    nonEmptyStack <<= 1
    isObjectStack = (isObjectStack << 1) | isObjectFlag
  }
  private def pop(): Unit = {
    checkStack >>>= 1
    if(checkStack == 0) Never() //minDepth
    isOddStack >>>= 1
    nonEmptyStack >>>= 1
    isObjectStack >>>= 1
  }

  private def ignoreTheSameBuilder(b: StringBuilder): Unit = ()

  private def startElement(): Unit =
    if(is(nonEmptyStack)) ignoreTheSameBuilder(result.append(if(objectNeedsValue) ':' else ','))
  private def endElement(): Unit = {
    nonEmptyStack |= 1L
    isOddStack ^= 1L
  }

  private def start(isObjectFlag: Long, c: Char): Unit = {
    startElement()
    push(isObjectFlag)
    //result.append('\n')
    ignoreTheSameBuilder(result.append(c))
  }
  def startArray(): Unit = start(0L, '[')
  def startObject(): Unit = start(1L, '{')
  def end(): Unit = {
    if(objectNeedsValue) throw new Exception("objectNeedsValue")
    ignoreTheSameBuilder(result.append(if(is(isObjectStack)) '}' else ']'))
    pop()
    endElement()
    if(objectNeedsValue) throw new Exception("objectNeedsKey")
  }
  def append(value: String): FinMutableJsonBuilder = {
    appendStr(value)
    just
  }
  def appendStr(value: String): Unit = {
    startElement()
    ignoreTheSameBuilder(result.append('"'))
    var j = 0
    while(j < value.length){
      val c = value(j)
      if(c == '\\' || c == '"' ||  c < '\u0020')
        ignoreTheSameBuilder(result.append(if(c < '\u0010')"\\u000" else "\\u00").append(Integer.toHexString(c)))
      else
        ignoreTheSameBuilder(result.append(c))
      j += 1
    }
    ignoreTheSameBuilder(result.append('"'))
    endElement()
  }
  def append(value: BigDecimal, decimalFormat: DecimalFormat): Unit = {
    startElement()
    ignoreTheSameBuilder(result.append(decimalFormat.format(value.bigDecimal)))
    endElement()
  }
  def append(value: Boolean): Unit = {
    startElement()
    ignoreTheSameBuilder(result.append(if(value) "true" else "false"))
    endElement()
  }
}
