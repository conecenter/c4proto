import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, Paths}
import java.util.UUID

/*
import sbt._
import sbt.Keys.{unmanagedSources, _}
import sbt.plugins.JvmPlugin

object C4GeneratorPlugin extends AutoPlugin {
  override def requires = JvmPlugin
  override lazy val projectSettings = Seq(
    sourceGenerators in Compile += Def.task {
      println(("AA1",(unmanagedSources in Compile).value))
      val toDir = (sourceManaged in Compile).value.toPath
      Files.createDirectories(toDir)
      for {
        path ← (unmanagedSources in Compile).value.map(_.toPath)
        if !path.toString.contains("-macros/")
      } yield {
        val toFile = toDir.resolve(path.getFileName).toFile
        val out = Generator.genPackage(path.toFile).mkString("\n")

        IO.write(toFile, out)
        toFile
      }
    }.taskValue
  )
}
*/

object Main {
  private def version = Array[Byte](70)
  private def getToPath(path: Path): Option[Path] = path.getFileName.toString match {
    case "scala" ⇒ Option(path.resolveSibling("java"))
    case name ⇒ Option(path.getParent).flatMap(getToPath).map(_.resolve(name))
  }
  def main(args: Array[String]): Unit = {
    val files = DirInfoImpl.deepFiles(Paths.get(args(0)))
      .filter(_.toString.endsWith(".scala"))
      .filterNot(_.toString.contains("-macros/"))

    val keep = (for {
      path ← files
      toParentPath ← getToPath(path.getParent)
      data = Files.readAllBytes(path)
      content = new String(data,UTF_8) if content.contains("@c4") || content.contains("@protocol") || content.contains("@assemble")
      uuid = UUID.nameUUIDFromBytes(data ++ version)
      toPath = toParentPath.resolve(s"c4gen.$uuid.${path.getFileName}")
    } yield {
      if(Files.notExists(toPath)) {
        println(s"generating $path --> $toPath")
        Files.createDirectories(toParentPath)
        Files.write(toPath, Generator.genPackage(content).getBytes(UTF_8))
      }
      toPath
    }).toSet

    for {
      path ← files if path.toString.contains("/c4gen.") && !keep(path)
    } {
      println(s"removing $path")
      Files.delete(path)
    }
  }
}

/******************************************************************************/

object FinallyClose {
  def apply[A<:AutoCloseable,R](o: A)(f: A⇒R): R = try f(o) finally o.close()
  def apply[A,R](close: A⇒Unit)(o: A)(f: A⇒R): R = try f(o) finally close(o)
}


import scala.collection.JavaConverters.iterableAsScalaIterableConverter

trait DirInfo {
  def deepFiles(path: Path): List[Path]
}

object DirInfoImpl extends DirInfo {
  def sortedList(dir: Path): List[Path] =
    FinallyClose(Files.newDirectoryStream(dir))(_.asScala.toList).sorted
  def deepFiles(path: Path): List[Path] = {
    if(!Files.exists(path)) Nil
    else if(Files.isDirectory(path)) sortedList(path).flatMap(deepFiles)
    else List(path) //Files.isRegularFile(path)
  }
}
