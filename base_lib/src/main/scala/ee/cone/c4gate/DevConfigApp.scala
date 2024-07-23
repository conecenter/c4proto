package ee.cone.c4gate

import ee.cone.c4actor.ListConfig
import ee.cone.c4di.c4

import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters.ListHasAsScala

object FileConfigFactory {
  def create(paths: List[Path]): Map[String, List[String]] = (for {
    path <- paths if Files.exists(path)
    l <- Files.readAllLines(path).asScala
    p <- l.indexOf("=") match { case -1 => Nil case pos => Seq(l.substring(0,pos) -> l.substring(pos+1)) }
  } yield p).groupMap(_._1)(_._2)
}

@c4("DevConfigApp") final class DevConfig(inner: ListConfig)(
  fileEnvMap: Map[String, List[String]] = FileConfigFactory.create(inner.get("C4DEV_ENV").map(Paths.get(_)))
) extends ListConfig {
  def get(key: String): List[String] = fileEnvMap.getOrElse(key,Nil) ::: inner.get(key)
}
