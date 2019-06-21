package ee.cone.logger


trait C4LoggerApp{
  def c4Logger:C4Logger
}

trait C4Logger {
   def initialize(setting:List[LogSetting]):Unit
   def refresh(filePath: String):Unit
   def setLogLevel(log: LogSetting):Unit

}

case class LogSetting(loggerName:String, level:LogLevel)


sealed trait LogLevel{
  def name:String
}

object LogLevel {
  def apply(name: String): LogLevel = name.toLowerCase match{
    case INFO.name ⇒ INFO
    case DEBUG.name ⇒ DEBUG
}
}

case object INFO extends LogLevel {
  val name: String = "info"
}

case object DEBUG extends LogLevel {
  val name: String = "debug"
}