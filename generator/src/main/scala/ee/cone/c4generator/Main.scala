package ee.cone.c4generator

import java.nio.file.{Files, Path, Paths}
import java.util.UUID
import java.nio.charset.StandardCharsets.UTF_8

//import scala.collection.JavaConverters.iterableAsScalaIterableConverter
import scala.meta._
import scala.jdk.CollectionConverters.IterableHasAsScala

object FinallyClose {
  def apply[A<:AutoCloseable,R](o: A)(f: A=>R): R = try f(o) finally o.close()
  def apply[A,R](close: A=>Unit)(o: A)(f: A=>R): R = try f(o) finally close(o)
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
  def version: String = "-v112"
  def env(key: String): String = Option(System.getenv(key)).getOrElse(s"missing env $key")
  def main(args: Array[String]): Unit = {
    val rootPath = Paths.get(env("C4GENERATOR_PATH"))
    val rootFromPath = rootPath.resolve("src")
    val rootCachePath = rootPath.resolve("cache")
    val fromPostfix = ".scala"
    val toPrefix = "c4gen."
    val files = DirInfo.deepFiles(rootFromPath).filter(_.toString.endsWith(fromPostfix))
    val(wasFiles,fromFiles) = files.partition(_.getFileName.toString.startsWith(toPrefix))
    Files.createDirectories(rootCachePath)
    val was = (for { path <- wasFiles } yield path -> Files.readAllBytes(path)).toMap
    val will = withIndex(for {
      path <- fromFiles
      toData <- Option(pathToData(path,rootCachePath)) if toData.length > 0
    } yield path.getParent.resolve(s"$toPrefix${path.getFileName}") -> toData)
    for(path <- was.keySet -- will.toMap.keys) {
      println(s"removing $path")
      Files.delete(path)
    }
    for{
      (path,data) <- will if !java.util.Arrays.equals(data,was.getOrElse(path,Array.empty))
    } {
      println(s"saving $path")
      Files.write(path,data)
    }
  }
  def withIndex: List[(Path,Array[Byte])] => List[(Path,Array[Byte])] = args => {
    val pattern = """\((\S+)\s(\S+)\s(\S+)\)""".r
    val indexes = args.groupBy(_._1.getParent).toList.sortBy(_._1).flatMap{ case (parentPath,args) =>
      val links: Seq[GeneratedAppLink] = args.flatMap{ case (path,data) =>
        val pos = data.indexOf('\n'.toByte)
        // println((pos,path,new String(data)))
        val firstLine = new String(data,0,pos,UTF_8)
        pattern.findAllMatchIn(firstLine).map{ mat =>
          val Seq(pkg,app,comp) = mat.subgroups
          GeneratedAppLink(pkg,app,comp)
        }
      }
      if(links.isEmpty) Nil else {
        val Seq(pkg) = links.map(_.pkg).distinct
        val content =
          s"\npackage $pkg" +
          links.groupBy(_.app).toList.map{ case (app,links) =>
            s"\ntrait $app extends ee.cone.c4proto.ComponentsApp {" +
            s"\n  override def components: List[ee.cone.c4proto.Component] = " +
            links.map(c=> s"\n    ${c.component}.components.toList ::: ").mkString +
            s"\n    super.components" +
            s"\n}"
          }.sorted.mkString
        List(parentPath.resolve("c4gen.scala") -> content.getBytes(UTF_8))
      }
    }
    args ++ indexes
  }
  lazy val generators: (Stat, String)=>Seq[Generated] = {
    val generators = List(ImportGenerator,AssembleGenerator,ProtocolGenerator,FieldAccessGenerator,ComponentsGenerator,LensesGenerator)
    (stat, fileName) => generators.flatMap(_.get.lift(stat, fileName)).flatten
  }
  def pathToData(path: Path, rootCachePath: Path): Array[Byte] = {
    val fromData = Files.readAllBytes(path)
    val uuid = UUID.nameUUIDFromBytes(fromData).toString
    val cachePath = rootCachePath.resolve(s"$uuid$version")
    val Name = """.+/(\w+)\.scala""".r
    if(Files.exists(cachePath)) Files.readAllBytes(cachePath) else {
      println(s"parsing $path")
      val content = new String(fromData,UTF_8).replace("\r\n","\n")
      val source = dialects.Scala212(content).parse[Source]
      val Parsed.Success(source"..$sourceStatements") = source
      val resStatements: List[Generated] = for {
        q"package $n { ..$packageStatements }" <- sourceStatements
        generated: Seq[Generated] = for {
          packageStatement <- packageStatements
          generated <- generators(packageStatement, path.toString)
        } yield generated
        patches: Seq[Patch] = generated.collect{ case p: Patch => p }
        statements = generated.reverse.dropWhile(_.isInstanceOf[GeneratedImport]).reverse
        res <- if(patches.nonEmpty) patches
        else if(statements.nonEmpty){
          val components = statements.collect{ case c: GeneratedComponent => c }.toList
          val mStatements = if(components.isEmpty) statements else {
            val Name(name) = path.toString
            statements.filterNot(_.isInstanceOf[GeneratedComponent]) ++
            components.groupBy(_.app).toList.sortBy(_._1).flatMap{ case (app,comps) =>
              assert(app.nonEmpty)
              List(
                ComponentsGenerator.join(s"${name}${app}Components","",comps),
                GeneratedAppLink(n.syntax,app,s"${name}${app}Components")
              )
            }
          }
          List(GeneratedCode(s"\npackage $n {")) ++ mStatements ++ List(GeneratedCode("\n}"))
        }
        else Nil
      } yield res
      val patches = resStatements.collect{ case p: Patch => p }
      if(patches.nonEmpty){
        val patchedContent = patches.sortBy(_.pos).foldRight(content)((patch,cont)=>
          cont.substring(0,patch.pos) + patch.content + cont.substring(patch.pos)
        )
        println(s"patching $path")
        Files.write(path, patchedContent.getBytes(UTF_8))
        pathToData(path,rootCachePath)
      } else {
        val warnings = Lint.process(sourceStatements)
        val code = resStatements.flatMap{
          case c: GeneratedImport => List(c.content)
          case c: GeneratedCode => List(c.content)
          case c: GeneratedAppLink => Nil
          case c => throw new Exception(s"$c")
        }
        val content = (warnings ++ code).mkString("\n")
        val contentWithLinks = if(content.isEmpty) "" else
          s"// THIS FILE IS GENERATED; APPLINKS: " +
          resStatements.collect{ case s: GeneratedAppLink =>
            s"(${s.pkg} ${s.app} ${s.component})"
          }.mkString +
          "\n" +
          content
        val toData = contentWithLinks.getBytes(UTF_8)
        Files.write(cachePath,toData)
        toData
      }
    }
  }



/*
  def main(args: Array[String]): Unit = {
    val rootPath = Paths.get(env("C4GENERATOR_PATH"))
    val rootFromPath = rootPath.resolve("from")
    for {
      fromPath <- DirInfo.deepFiles(rootFromPath)
      toPath = rootPath.resolve("to").resolve(rootFromPath.relativize(fromPath))
      fromRealPath <- generate(fromPath,rootPath)
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
          q"package $n { ..$packageStatements }" <- sourceStatements
          ps <- packageStatements
        } yield ps


        //  sourceStatements.toList.flatMap{ case q"package $n { ..$packageStatements }" => packageStatements }

        val res: String = packageStatements.foldRight(content){ (stat,cont)=>
          AssembleGenerator.get
          .orElse(ProtocolGenerator.get)
          .orElse(FieldAccessGenerator.get)
          .lift(stat).fold(cont)(_(cont))
        }
        Files.write(cachePath,/*s"/* --==C4-GENERATED==-- */\n$res"*/res.getBytes(UTF_8))
      }
      Option(cachePath)
    }
  }*/
}

object ImportGenerator extends Generator {
  def get: Get = {
    case (q"import ..$importers", _) =>
      val nextImporters = importers.map{
        case initialImporter@importer"$eref.{..$importeesnel}" =>
          importer"$eref._" match {
            case j@importer"ee.cone.c4assemble._" => j
            case j@importer"ee.cone.c4proto._" => j
            case _ => initialImporter
          }
      }
      List(GeneratedImport("\n" + q"import ..$nextImporters".syntax))
  }
}

object Util {
  /*
  def comment(stat: Stat): String=>String = cont =>
    cont.substring(0,stat.pos.start) + " /* " +
      cont.substring(stat.pos.start,stat.pos.end) + " */ " +
      cont.substring(stat.pos.end)*/

  def unBase(name: String, pos: Int)(f: String=>Seq[Generated]): Seq[Generated] = {
    val UnBase = """(\w+)Base""".r
    name match {
      case UnBase(n) => f(n)
      case n => List(Patch(pos,"Base"))
    }
  }
}

trait Generator {
  type Get = PartialFunction[(Stat,String),Seq[Generated]]
  def get: Get
}

sealed trait Generated
case class GeneratedImport(content: String) extends Generated
case class GeneratedCode(content: String) extends Generated
case class GeneratedComponent(app: String, name: String, cContent: String) extends Generated
case class Patch(pos: Int, content: String) extends Generated
case class GeneratedTraitDef(name: String) extends Generated
case class GeneratedTraitUsage(name: String) extends Generated
case class GeneratedAppLink(pkg: String, app: String, component: String) extends Generated

object Lint {
  def process(stats: Seq[Stat]): Seq[String] =
    stats.flatMap{ stat =>
      stat.collect{
        case q"..$mods class $tname[..$tparams] ..$ctorMods (...$paramss) extends $template"
          if mods.collect{ case mod"abstract" => true }.nonEmpty =>
          ("abstract class",tname,template)
        case q"..$mods trait $tname[..$tparams] extends $template" =>
          ("trait",tname,template)
      }.flatMap{
        case (tp,tName,template"{ ..$statsA } with ..$inits { $self => ..$statsB }") =>
          if(statsA.nonEmpty) println(s"warn: early initializer in $tName")
          statsB.collect{
            case q"..$mods val ..$patsnel: $tpeopt = $expr"
              if mods.collect{ case mod"lazy" => true }.isEmpty =>
              s"/*\nwarn: val ${patsnel.mkString(" ")} in $tp $tName \n*/"
          }
        case _ => Nil
      }
    }
}


/*
git --git-dir=../.git --work-tree=target/c4generator/to  diff
perl run.pl
git --git-dir=../.git --work-tree=target/c4generator/to  diff target/c4generator/to/c4actor-base
*/
