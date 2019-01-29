package ee.cone.c4generator

import java.nio.file.{Files, Path, Paths}
import java.util.UUID
import java.nio.charset.StandardCharsets.UTF_8

import scala.collection.JavaConverters.iterableAsScalaIterableConverter
import scala.collection.immutable
import scala.meta._

object FinallyClose {
  def apply[A<:AutoCloseable,R](o: A)(f: A⇒R): R = try f(o) finally o.close()
  def apply[A,R](close: A⇒Unit)(o: A)(f: A⇒R): R = try f(o) finally close(o)
}

object DirInfo {
  def sortedList(dir: Path): List[Path] =
    FinallyClose(Files.newDirectoryStream(dir))(_.asScala.toList).sorted
  def deepFiles(path: Path): List[Path] = {
    if(!Files.exists(path)) Nil
    else if(Files.isDirectory(path)) sortedList(path).flatMap(deepFiles)
    else List(path) //Files.isRegularFile(path)
  }
}

object Main {
  def version: Array[Byte] = Array(0,15)
  def env(key: String): String = Option(System.getenv(key)).getOrElse(s"missing env $key")
  def main(args: Array[String]): Unit = {
    val rootPath = Paths.get(env("C4GENERATOR_PATH"))
    val rootFromPath = rootPath.resolve("from")
    for {
      fromPath ← DirInfo.deepFiles(rootFromPath)
      toPath = rootPath.resolve("to").resolve(rootFromPath.relativize(fromPath))
      fromRealPath ← generate(fromPath,rootPath)
    } yield {
      Files.createDirectories(toPath.getParent)
      Files.createLink(toPath,fromRealPath)
    }
  }
  def generate(fromPath: Path, rootPath: Path): Option[Path] = {
    if(!fromPath.toString.endsWith(".scala")) Option(fromPath) else {
      val data = Files.readAllBytes(fromPath)
      val uuid = UUID.nameUUIDFromBytes(data ++ version).toString
      val rootCachePath = rootPath.resolve("cache")
      Files.createDirectories(rootCachePath)
      val cachePath = rootCachePath.resolve(uuid)
      if(!Files.exists(cachePath)){
        println(s"parsing $fromPath")
        val content = new String(data,UTF_8).replace("\r\n","\n")
        val source = dialects.Scala211(content).parse[Source]
        val Parsed.Success(source"..$sourceStatements") = source
        val packageStatements = for {
          q"package $n { ..$packageStatements }" ← sourceStatements
          ps ← packageStatements
        } yield ps


        //  sourceStatements.toList.flatMap{ case q"package $n { ..$packageStatements }" ⇒ packageStatements }

        val res: String = packageStatements.foldRight(content){ (stat,cont)⇒
          AssembleGenerator.get
            .orElse(ProtocolGenerator.get)
            .orElse(FieldAccessGenerator.get)
            .lift(stat).fold(cont){ nStr ⇒
              //Option(stat.structure).filter(_ contains "TestTodoAccess").foreach(println)
              //cont.substring(0,stat.pos.start) + nStr + cont.substring(stat.pos.end)
              cont.substring(0,stat.pos.start) + " /* " +
              cont.substring(stat.pos.start,stat.pos.end) + " */ " +
              cont.substring(stat.pos.end) + "\n\n" + nStr
            }
        }
        Files.write(cachePath,/*s"/* --==C4-GENERATED==-- */\n$res"*/res.getBytes(UTF_8))
      }
      Option(cachePath)
    }
  }
}

trait Generator {
  type Get = PartialFunction[Stat,String]
  def get: Get
}

/*
git --git-dir=../.git --work-tree=target/c4generator/to  diff
perl run.pl
git --git-dir=../.git --work-tree=target/c4generator/to  diff target/c4generator/to/c4actor-base
*/
