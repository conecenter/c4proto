package ee.cone.logger

import ee.cone.c4actor.{Executable, PrintColored, ToStartApp}


trait LoggerUpdaterApp extends ToStartApp with C4LoggerApp{
  override def toStart: List[Executable] = new LoggerUpdaterExec(c4Logger,appLoggers)::super.toStart
  def appLoggers: List[LogSetting] = Nil
}


class LoggerUpdaterExec(logger:C4Logger,appLoggers:List[LogSetting]) extends Executable{
  def run(): Unit = concurrent blocking {
    logger.initialize(appLoggers)
    while(true) {
      PrintColored.makeColored("y")("check")
      logger.refresh("./log4j2-loggers.xml")
      Thread.sleep(60000)
    }
  }
}