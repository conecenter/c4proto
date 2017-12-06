
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, Paths}
import java.util.UUID
import scala.annotation.tailrec
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
  private def version = Array[Byte](86)
  case class Generate(dir: Path, path: Path, fromContent: String, uuid: String)
  private def getToPath(path: Path): Option[Path] = path.getFileName.toString match {
    case "scala" ⇒ Option(path.resolveSibling("java"))
    case name ⇒ Option(path.getParent).flatMap(getToPath).map(_.resolve(name))
  }
  def main(args: Array[String]): Unit = iteration(args(0),args(1).toInt)
  @tailrec def iteration(rootDir: String, left: Int): Unit = if(left > 0){
    val files = DirInfoImpl.deepFiles(Paths.get(rootDir))
      .filter(_.toString.endsWith(".scala"))
    val tasks: List[Generate] = for {
      path ← files
      toParentPath ← getToPath(path.getParent)
      data = Files.readAllBytes(path)
      content = new String(data,UTF_8) if content.contains("@c4") || content.contains("@protocol") || content.contains("@assemble")
      toPath = toParentPath.resolve(s"c4gen.${path.getFileName}")
      uuid = UUID.nameUUIDFromBytes(data ++ version).toString
    } yield Generate(toParentPath, toPath, content, uuid)
    val keep = (for {
      Generate(toParentPath, toPath, content, uuid) ← tasks
    } yield toPath).toSet

    for(
      Generate(toParentPath, toPath, content, uuid)←tasks if
        Files.notExists(toPath) ||
        !(new String(Files.readAllBytes(toPath), UTF_8)).contains(uuid)
    ){
      //println(Files.notExists(toPath))
      //println(!(new String(Files.readAllBytes(toPath), UTF_8)).contains(uuid))
      println(s"generating $toPath")
      Files.createDirectories(toParentPath)
      Files.write(toPath, s"/* GENERATED ${uuid} */\n${Generator.genPackage(content)}".getBytes(UTF_8))
    }

    for {
      path ← files if path.toString.contains("/c4gen.") && !keep(path)
    } {
      println(s"removing $path")
      Files.delete(path)
    }
    Thread.sleep(1000)
    iteration(rootDir, left-1)
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
