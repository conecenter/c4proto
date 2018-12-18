package ee.cone.c4actor

import java.text.DecimalFormat

object JsonBuildingImpl extends MutableJsonBuilding {
  def process(body: RMutableJsonBuilder⇒Unit): String = {
    val stringBuilder = new StringBuilder
    val builder = new JsonBuilderImpl(stringBuilder)
    body(builder)
    stringBuilder.toString
  }
}

class JsonBuilderImpl(result: StringBuilder) extends RMutableJsonBuilder {
  private var checkStack: Long = 1L
  private var isOddStack: Long = 0L
  private var nonEmptyStack: Long = 0L
  private var isObjectStack: Long = 0L

  private def is(stack: Long) = (stack & 1L) != 0L
  private def objectNeedsValue = is(isObjectStack) && is(isOddStack)

  private def push(isObjectFlag: Long): Unit = {
    checkStack <<= 1
    assert(checkStack != 0) //maxDepth
    isOddStack <<= 1
    nonEmptyStack <<= 1
    isObjectStack = (isObjectStack << 1) | isObjectFlag
  }
  private def pop(): Unit = {
    checkStack >>>= 1
    assert(checkStack != 0) //minDepth
    isOddStack >>>= 1
    nonEmptyStack >>>= 1
    isObjectStack >>>= 1
  }

  private def startElement(): Unit =
    if(is(nonEmptyStack)) result.append(if(objectNeedsValue) ':' else ',')
  private def endElement(): Unit = {
    nonEmptyStack |= 1L
    isOddStack ^= 1L
  }

  private def start(isObjectFlag: Long, c: Char): RMutableJsonBuilder = {
    startElement()
    push(isObjectFlag)
    //result.append('\n')
    result.append(c)
    this
  }
  def startArray() = start(0L, '[')
  def startObject() = start(1L, '{')
  def end() = {
    if(objectNeedsValue) throw new Exception("objectNeedsValue")
    result.append(if(is(isObjectStack)) '}' else ']')
    pop()
    endElement()
    if(objectNeedsValue) throw new Exception("objectNeedsKey")
    this
  }
  def append(value: String) = {
    startElement()
    result.append('"')
    var j = 0
    while(j < value.length){
      val c = value(j)
      if(c == '\\' || c == '"' ||  c < '\u0020')
        result.append(if(c < '\u0010')"\\u000" else "\\u00").append(Integer.toHexString(c))
      else
        result.append(c)
      j += 1
    }
    result.append('"')
    endElement()
    this
  }
  def append(value: BigDecimal, decimalFormat: DecimalFormat) = {
    startElement()
    result.append(decimalFormat.format(value.bigDecimal))
    endElement()
    this
  }
  def append(value: Boolean) = {
    startElement()
    result.append(if(value) "true" else "false")
    endElement()
    this
  }
}

