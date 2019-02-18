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
  def version: Array[Byte] = Array(0,39)
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
        Lint.process(sourceStatements)
        val packageStatements = for {
          q"package $n { ..$packageStatements }" ← sourceStatements
          ps ← packageStatements
        } yield ps


        //  sourceStatements.toList.flatMap{ case q"package $n { ..$packageStatements }" ⇒ packageStatements }

        val res: String = packageStatements.foldRight(content){ (stat,cont)⇒
          AssembleGenerator.get
          .orElse(ProtocolGenerator.get)
          .orElse(FieldAccessGenerator.get)
          .lift(stat).fold(cont)(_(cont))
        }
        Files.write(cachePath,/*s"/* --==C4-GENERATED==-- */\n$res"*/res.getBytes(UTF_8))
      }
      Option(cachePath)
    }
  }
}

object Util {
  def comment(stat: Stat): String⇒String = cont ⇒
    cont.substring(0,stat.pos.start) + " /* " +
      cont.substring(stat.pos.start,stat.pos.end) + " */ " +
      cont.substring(stat.pos.end)
}

trait Generator {
  type Get = PartialFunction[Stat,String⇒String]
  def get: Get
}

object Lint {
  def process(stats: Seq[Stat]): Unit = {
    stats.foreach{ stat ⇒
      stat.collect{
        case q"..$mods class $tname[..$tparams] ..$ctorMods (...$paramss) extends $template"
          if mods.collect{ case mod"abstract" ⇒ true }.nonEmpty ⇒
          ("abstract class",tname,template)
        case q"..$mods trait $tname[..$tparams] extends $template" ⇒
          ("trait",tname,template)
      }.collect{
        case (tp,tName,template"{ ..$statsA } with ..$inits { $self => ..$statsB }") ⇒
          if(statsA.nonEmpty) println(s"warn: early initializer in $tName")
          statsB.collect{
            case q"..$mods val ..$patsnel: $tpeopt = $expr"
              if mods.collect{ case mod"lazy" ⇒ true }.isEmpty ⇒
              println(s"warn: val ${patsnel.mkString(" ")} in $tp $tName ")
          }
      }
    }
  }
}


/*
git --git-dir=../.git --work-tree=target/c4generator/to  diff
perl run.pl
git --git-dir=../.git --work-tree=target/c4generator/to  diff target/c4generator/to/c4actor-base
*/
