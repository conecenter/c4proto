package ee.cone.logger

import ee.cone.c4actor.{Executable, PrintColored, ToStartApp}


trait LoggerUpdaterApp extends ToStartApp with C4LoggerApp{
  override def toStart: List[Executable] = new LoggerUpdaterExec(c4Logger,appLoggers)::super.toStart
  def appLoggers: List[LogSetting] = Nil
}


class LoggerUpdaterExec(logger:C4Logger,appLoggers:List[LogSetting]) extends Executable{
  def run(): Unit = concurrent blocking {
    logger.initialize(appLoggers)
    val fileName  = "./log4j2-loggers.xml"
    logger.refresh(fileName,true)
    while(true){
      logger.refresh(fileName,false)
      Thread.sleep(60000)
    }
  }
}