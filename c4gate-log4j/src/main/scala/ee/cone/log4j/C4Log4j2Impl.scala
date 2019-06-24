package ee.cone.log4j

import java.nio.file.{Files, Path, Paths}
import java.util

import ee.cone.logger._
import org.apache.logging.log4j.core.LoggerContext
import org.apache.logging.log4j.core.config.{Configuration, LoggerConfig}
import org.apache.logging.log4j.{Level, LogManager, Logger}

trait C4Log4j2App extends LoggerUpdaterApp {
  def c4Logger: C4Logger = new C4Log4j2Impl
}

class C4Log4j2Impl extends C4Logger {
  private def logger: Logger = org.apache.logging.log4j.LogManager.getLogger()

  lazy val ctx: LoggerContext = LogManager.getContext(false).asInstanceOf[LoggerContext]
  lazy val config: Configuration = ctx.getConfiguration
  lazy val loggerConfig: LoggerConfig = config.getLoggerConfig(LogManager.ROOT_LOGGER_NAME)

  def initialize(setting: List[LogSetting]): Unit = {
    setting.foreach(setLogLevel)
  }

  def refresh(filePath: String,firstRun:Boolean): Unit = {
    try {
      val path: Path = Paths.get(filePath)

      if (System.currentTimeMillis - 90000L <= Files.getLastModifiedTime(path).toMillis || firstRun) {
        val settings: util.List[String] = Files.readAllLines(path)
        settings.forEach(s ⇒ {
          val splits = s.split('=')
          setLogLevel(LogSetting(splits(0), LogLevel(splits(1))))
        }
        )
      }
    }catch {
      case _: Exception ⇒ ""
    }
  }

  def setLogLevel(log: LogSetting): Unit = {
    if (loggerConfig.getName != logger.getName) {
      val notRootloggerConfig = config.getLoggerConfig(log.loggerName)
      if (notRootloggerConfig.getName.equalsIgnoreCase(log.loggerName))
          notRootloggerConfig.setLevel(logLevelToJavaLevel(log.level))
      else {
        val specificConfig = new LoggerConfig(log.loggerName, logLevelToJavaLevel(log.level), true)
        specificConfig.setParent(loggerConfig)
        config.addLogger(log.loggerName, specificConfig)
        specificConfig.setLevel(logLevelToJavaLevel(log.level))
      }
    }
    else loggerConfig.setLevel(logLevelToJavaLevel(log.level))
    ctx.updateLoggers()

  }

  private def logLevelToJavaLevel(level: LogLevel): Level = {
    level match {
      case DEBUG ⇒ Level.DEBUG
      case INFO ⇒ Level.INFO
      case WARN ⇒ Level.WARN
    }
  }
}