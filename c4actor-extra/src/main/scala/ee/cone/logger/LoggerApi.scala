package ee.cone.logger


trait C4LoggerApp{
  def c4Logger:C4Logger
}

trait C4Logger {
   def initialize(setting:List[LogSetting]):Unit
   def refresh(filePath: String,firstRun:Boolean):Unit
   def setLogLevel(log: LogSetting):Unit

}

case class LogSetting(loggerName:String, level:LogLevel)


sealed trait LogLevel{
  def name:String
}

object LogLevel {
  def apply(name: String): LogLevel = name.toLowerCase match{
    case DEBUG.name ⇒ DEBUG
    case INFO.name ⇒ INFO
    case WARN.name ⇒ WARN
    case ERROR.name ⇒ ERROR
}
}

case object INFO extends LogLevel {
  val name: String = "info"
}

case object DEBUG extends LogLevel {
  val name: String = "debug"
}

case object WARN extends LogLevel{
  val name: String = "warn"
}

case object ERROR extends LogLevel{
  val name: String = "error"
}