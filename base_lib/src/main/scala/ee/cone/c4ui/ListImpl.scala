package ee.cone.c4ui

import java.text.{DecimalFormat, DecimalFormatSymbols, NumberFormat}
import ee.cone.c4di._
import ee.cone.c4actor.Context
import ee.cone.c4ui.FrontTypes.Em
import ee.cone.c4vdom._

@c4("UICompApp") final class ListJsonAdapterProvider(util: TagJsonUtils){
  @provide def forString: Seq[JsonValueAdapter[String]] =
    List(util.jsonValueAdapter((value, builder) => builder.just.append(value)))
  @provide def forToJson: Seq[JsonValueAdapter[ToJson]] =
    List(util.jsonValueAdapter((value,builder) => value.appendJson(builder)))
  @provide def forInt: Seq[JsonValueAdapter[Int]] =
    List(util.jsonValueAdapter((value, builder) => builder.just.append(value)))
  /*
  @provide def forLong: Seq[JsonValueAdapter[Long]] =
      We can not send Long, because JS can add error to it when decoding
      e.g. 9223372036854775807 = 9223372036854776000 for JS
      use String instead
  */
  @provide def forBoolean: Seq[JsonValueAdapter[Boolean]] =
    List(util.jsonValueAdapter((value,builder) => builder.just.append(value)))
  //
  @provide def forCSSClassName: Seq[JsonValueAdapter[CSSClassName]] =
    List(util.jsonValueAdapter((value, builder) => builder.just.append(value.name)))
  @provide def forReceiver: Seq[JsonValueAdapter[Receiver[Context]]] =
    List(util.jsonValueAdapter((value, builder) => builder.just.append("send")))
}

@c4("UICompApp") final class EmAdapterProvider(util: TagJsonUtils)(
  symbols: DecimalFormatSymbols = {
    val result = DecimalFormatSymbols.getInstance
    result.setDecimalSeparator('.')
    result
  }
)(
  emFormat: DecimalFormat = new DecimalFormat("#0.###", symbols)
) {
  @provide def emAdapter: Seq[JsonValueAdapter[Em]] =
    List(util.jsonValueAdapter((value, builder) => builder.just.append(value, emFormat)))
}