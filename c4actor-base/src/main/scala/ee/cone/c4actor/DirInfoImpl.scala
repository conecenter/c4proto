package ee.cone.c4actor

import java.nio.file.{Files, Path}
import scala.collection.JavaConverters.iterableAsScalaIterableConverter

object DirInfoImpl extends DirInfo {
  def sortedList(dir: Path): List[Path] =
    FinallyClose(Files.newDirectoryStream(dir))(_.asScala.toList).sorted
  def deepFiles(path: Path): List[Path] =
    if(!Files.exists(path)) Nil
    else if(Files.isRegularFile(path)) List(path)
    else if(Files.isDirectory(path)) sortedList(path).flatMap(deepFiles)
    else throw new Exception(s"$path found")
}
