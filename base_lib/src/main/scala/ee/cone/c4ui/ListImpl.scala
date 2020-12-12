package ee.cone.c4ui

import java.text.{DecimalFormat, NumberFormat}

import ee.cone.c4di._
import ee.cone.c4actor.Context
import ee.cone.c4vdom._

@c4("UICompApp") final class ListJsonAdapterProvider(util: TagJsonUtils){
  @provide def forString: Seq[JsonValueAdapter[String]] =
    List(util.jsonValueAdapter((value, builder) => builder.just.append(value)))
  @provide def forToJson: Seq[JsonValueAdapter[ToJson]] =
    List(util.jsonValueAdapter((value,builder) => value.appendJson(builder)))
  private lazy val IntDecimalFormat = new DecimalFormat("#")
  @provide def forInt: Seq[JsonValueAdapter[Int]] =
    List(util.jsonValueAdapter((value, builder) => builder.just.append(BigDecimal(value), IntDecimalFormat)))
  @provide def forBoolean: Seq[JsonValueAdapter[Boolean]] =
    List(util.jsonValueAdapter((value,builder) => builder.just.append(value)))
  //
  @provide def forCSSClassName: Seq[JsonValueAdapter[CSSClassName]] =
    List(util.jsonValueAdapter((value, builder) => builder.just.append(value.name)))
  @provide def forReceiver: Seq[JsonValueAdapter[Receiver[Context]]] =
    List(util.jsonValueAdapter((value, builder) => builder.just.append("send")))
}
