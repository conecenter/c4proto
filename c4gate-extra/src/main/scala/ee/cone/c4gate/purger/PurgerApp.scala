package ee.cone.c4gate.purger

import java.nio.file.{Files, Path, Paths}

import com.typesafe.scalalogging.LazyLogging
import ee.cone.c4actor._

import scala.collection.JavaConverters.iterableAsScalaIterableConverter

// KEEP_LAST=7 KEEP_HOURS=7 KEEP_DAYS=14 KEEP_MONTHS=6 BASE_DIR='../db4/' SNAPSHOTS_DIR='snapshots/' SLEEP_TIME=60 C4STATE_TOPIC_PREFIX=ee.cone.c4gate.purger.PurgerApp sbt ~'c4gate-extra/runMain ee.cone.c4actor.ServerMain'

class PurgerExecutable(
  execution: Execution, config: Config
) extends Executable with LazyLogging {
  def run(): Unit = {
    logger.info("Running with this config:")
    logger.info(config.toString)
    val keepLastN = 7 //config.get("KEEP_LAST").toInt
    val keepHours = 7 //config.get("KEEP_HOURS").toInt
    val keepDays = 7 //config.get("KEEP_DAYS").toInt
    val keepMonths = 7 //config.get("KEEP_MONTHS").toInt
    val directory = "../db4/" //config.get("BASE_DIR")
    val relativePath = "snapshots/" //config.get("SNAPSHOTS_DIR")
    val sleepTimer = 60 // config.get("SLEEP_TIME").toInt

    val hour = 60L * 60L * 1000L
    val dayRelative = 24L
    val monthRelative = 30L


    while (true) {
      logger.info("Scanning for targets...")
      val dirPath = Paths.get(directory).resolve(relativePath)
      if (Files.exists(dirPath)) {
        val now = System.currentTimeMillis()
        val files: List[(Path, Long)] =
          FinallyClose(Files.newDirectoryStream(dirPath))(_.asScala.toList)
            .map(path ⇒ path → findAge(path))
            .map { case (path, date) ⇒ path → (now - date) }
            .sortBy(_._2)
        logger.info(s"Got ${files.size}")

        println(files.mkString("\n"))
        println("-----------------------")
        val hourly: List[(Path, Long)] = files.drop(keepLastN).map { case (path, date) ⇒ path → (date / hour) }
        val (inHourly, afterHourly) = hourly.partition(_._2 < keepHours)
        val toDeleteHourly = removeSame(inHourly)


        println(hourly.mkString("\n"))
        println("-----------------------")
        val daily: List[(Path, Long)] = afterHourly.map { case (path, date) ⇒ path → (date / dayRelative) }
        val (inDaily, afterDaily) = daily.partition(_._2 < keepDays)
        val toDeleteDays = removeSame(inDaily)


        println(daily.mkString("\n"))
        println("-----------------------")
        val monthly: List[(Path, Long)] = afterDaily.map { case (path, date) ⇒ path → (date / monthRelative) }
        val inMonthly = monthly.takeWhile(_._2 < keepMonths)
        val toDeleteMonths = removeSame(inMonthly)

        println(monthly.mkString("\n"))
        val results = delete(toDeleteHourly ::: toDeleteDays ::: toDeleteMonths)
        logger.info(s"Deleted: \n${results.mkString("\n")}")
      }
      Thread.sleep(sleepTimer * 1000)
    }
    execution.complete()
  }

  def removeSame: List[(Path, Long)] ⇒ List[Path] =
    _.groupBy(_._2).transform { case (k, v) ⇒ v.sortBy(_._2).tail }.values.toList.flatMap(_.map(_._1))

  def delete(paths: List[Path]): List[(Path, Boolean)] =
    for {
      path ← paths
    } yield {
      path → Files.deleteIfExists(path)
    }

  def findAge: Path ⇒ Long =
    Files.getLastModifiedTime(_).toMillis
}

class PurgerApp extends ExecutableApp
  with VMExecutionApp
  with ToStartApp
  with EnvConfigApp {
  override def toStart: List[Executable] = new PurgerExecutable(execution, config) :: super.toStart

  def idGenUtil: IdGenUtil = IdGenUtilImpl()()
}
