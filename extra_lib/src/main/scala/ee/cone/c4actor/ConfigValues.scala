package ee.cone.c4actor

import java.lang.Math.toIntExact
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, Paths}

import ee.cone.c4actor.Types.SrcId
import ee.cone.c4assemble.Single

trait SrcIdValue {def srcId: SrcId}
trait StringValue {def value: String}
case class StringConstant(value: String) extends StringValue

trait ControversialBooleanConversion {
  def convert: String => Boolean = v => v.trim.toLowerCase match {
    case "" | "0" | "false" => false
    case _ => true
  }
}

trait BooleanValue {
  def value: Boolean
}
class BooleanTrue extends BooleanValue{def value = true}
class BooleanFalse extends BooleanValue{def value = false}

trait IntValue {def value: Int}
case class IntConstant(value: Int) extends IntValue

abstract class ConfigStringOptValue(envName: String, default: () => String = () => "") extends StringValue {
  def config: ListConfig
  def value: String = Single.option(config.get(envName)).getOrElse(default())
}
abstract class ConfigIntOptValue(envName: String, default: () => Int = () => 0) extends IntValue {
  def config: ListConfig
  def value: Int = Single.option(config.get(envName)).fold(default())(s=>toIntExact(s.toLong))
}
abstract class ConfigBooleanOptValue(envName: String, default: () => Boolean = () => false) extends BooleanValue with ControversialBooleanConversion {
  def config: ListConfig
  def value: Boolean = Single.option(config.get(envName)).fold(default())(convert)
}

abstract class ConfigStringValue(envName: String) extends StringValue{
  def config: Config
  def value: String = config.get(envName)
}
abstract class ConfigFileConfig(envName: String) extends StringValue{
  def config: Config
  def value: String = read(Paths.get(config.get(envName))).trim
  private def read(path: Path) = new String(Files.readAllBytes(path),UTF_8)
}
abstract class ConfigIntValue(envName: String) extends IntValue{
  def config: Config
  def value: Int = toIntExact(config.get(envName).toLong)
}
abstract class ConfigBooleanValue(envName: String) extends BooleanValue with ControversialBooleanConversion {
  def config: Config
  def value: Boolean = convert(config.get(envName))
}

