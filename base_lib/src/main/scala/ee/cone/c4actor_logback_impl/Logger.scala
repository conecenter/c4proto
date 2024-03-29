package ee.cone.c4actor_logback_impl

import java.io.ByteArrayInputStream
import java.nio.file.{Files, Path, Paths}
import java.nio.charset.StandardCharsets.UTF_8
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.joran.JoranConfigurator
import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor._
import ee.cone.c4di.c4
import org.slf4j.LoggerFactory

import scala.annotation.tailrec

@c4("BasicLoggingApp") final class LoggerTest extends Executable with Early with LazyLogging {
  def run(): Unit = if(Option(System.getenv("C4LOGBACK_TEST")).nonEmpty) iteration(0L)
  @tailrec private def iteration(v: Long): Unit = {
    Thread.sleep(1000)
    logger.warn(s"logger test $v")
    logger.debug(s"logger test $v")
    iteration(v+1L)
  }
}

@c4("BasicLoggingApp") final class DefLoggerConfigurator(
  config: ListConfig,
  catchNonFatal: CatchNonFatal
) extends LoggerConfigurator(
  config.get("C4LOGBACK_XML").map(Paths.get(_)) ::: Paths.get("/tmp/logback.xml") :: Nil,
  catchNonFatal,
  5000
) with Executable with Early

class LoggerConfigurator(paths: List[Path], catchNonFatal: CatchNonFatal, scanPeriod: Long) extends Executable with LazyLogging {
  def run(): Unit = concurrent.blocking{ iteration("") }
  @tailrec private def iteration(wasContent: String): Unit = {
    val content =
      s"""
      <configuration>
        <statusListener class="ch.qos.logback.core.status.NopStatusListener" />
        ${paths.map(path=>if(Files.exists (path)) new String(Files.readAllBytes(path), UTF_8) else "").mkString}
        <appender name="CON" class="ch.qos.logback.core.ConsoleAppender">
          <encoder><pattern>%d{HH:mm:ss.SSS} %-5level %logger{36} - %msg%n</pattern></encoder>
        </appender>
        <appender name="ASYNCFILE" class="ch.qos.logback.classic.AsyncAppender">
          <discardingThreshold>0</discardingThreshold>
          <queueSize>1000000</queueSize>
          <appender-ref ref="CON" />
        </appender>
        <root level="INFO">
          <appender-ref ref="ASYNCFILE" />
        </root>
        <shutdownHook/>
      </configuration>
      """
    if(wasContent != content) reconfigure(content)
    Thread.sleep(scanPeriod)
    iteration(content)
  }
  def reconfigure(content: String): Unit = catchNonFatal{
    println("logback reconfigure 2 started")
    val context = LoggerFactory.getILoggerFactory.asInstanceOf[LoggerContext]
    val configurator = new JoranConfigurator()
    configurator.setContext(context)
    context.reset()
    configurator.doConfigure(new ByteArrayInputStream(content.getBytes(UTF_8)))
    println("logback reconfigure 2 ok")
  }("reconfigure"){ e => () }
}
