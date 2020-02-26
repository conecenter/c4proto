package ee.cone.c4generator

import java.nio.file.{Files, Path, Paths}
import java.util.UUID
import java.nio.charset.StandardCharsets.UTF_8
import scala.meta.parsers.Parsed.{Error, Success}
import scala.meta._
import scala.jdk.CollectionConverters.IterableHasAsScala
import scala.jdk.CollectionConverters.IteratorHasAsScala

object Main {
  def defaultGenerators(protocolStatsTransformers: List[ProtocolStatsTransformer]): List[Generator] = {
    val protocolGenerator = new ProtocolGenerator(protocolStatsTransformers)
    List(
      ImportGenerator,
      new TimeGenerator(protocolGenerator),
      new AssembleGenerator(TimeJoinParamTransformer :: Nil),
      protocolGenerator,
      MultiGenerator,
      FieldAccessGenerator,
      AppGenerator
    ) //,UnBaseGenerator
  }
  def main(args: Array[String]): Unit = new RootGenerator(defaultGenerators(Nil)).run()
  def toPrefix = "c4gen."
  def env(key: String): Option[String] = Option(System.getenv(key))
  def version: String = s"-w${env("C4GENERATOR_VER").getOrElse(throw new Exception(s"missing env C4GENERATOR_VER"))}"
  def rootPath = Paths.get(env("C4GENERATOR_PATH").getOrElse(throw new Exception(s"missing env C4GENERATOR_PATH")))
}
import Main.{toPrefix,rootPath,version}

case class WillGeneratorContext(fromFiles: List[Path], dirToModDir: Map[Path,ParentPath])
trait WillGenerator {
  def get(ctx: WillGeneratorContext): List[(Path,Array[Byte])]
}

class ParentPath(val path: Path, val removed: List[String])
class RootGenerator(generators: List[Generator]) {
  def willGenerators: List[WillGenerator] = List(
    new DefaultWillGenerator(generators),
    new PublicPathsGenerator,
    new ModRootsGenerator
  )
  //
  def isGenerated(path: Path): Boolean = path.getFileName.toString.startsWith(toPrefix)
  def getParentPaths(res: List[ParentPath]): List[ParentPath] = (for {
    parent <- Option(res.head.path.getParent)
    name <- Option(res.head.path.getFileName)
  } yield getParentPaths(new ParentPath(parent, name.toString::res.head.removed) :: res)).getOrElse(res)
  def getModDirs(paths: List[Path]): Map[Path,ParentPath] = {
    val srcScalaDirs: List[Path] =
      paths.filter(_.getFileName.toString.endsWith(".scala")).map(_.getParent).distinct
    val hasScala = srcScalaDirs.toSet
    srcScalaDirs.map(dir => dir ->
      getParentPaths(new ParentPath(dir,Nil)::Nil).find(p=>hasScala(p.path)).get
    ).toMap
  }
  def run(): Unit = {
    //println(s"1:${System.currentTimeMillis()}") //130
    val rootFromPath = rootPath.resolve("src")
    val(wasFiles,fromFiles) = Files.readAllLines(rootFromPath).asScala.map(Paths.get(_)).toList.partition(isGenerated)
    //println(s"5:${System.currentTimeMillis()}") //150
    val was = (for { path <- wasFiles } yield path -> Files.readAllBytes(path)).toMap
    //println(s"4:${System.currentTimeMillis()}") //1s
    val willGeneratorContext = WillGeneratorContext(fromFiles,getModDirs(fromFiles))
    val will = willGenerators.flatMap(_.get(willGeneratorContext))
    assert(will.forall{ case(path,_) => isGenerated(path) })
    //println(s"2:${System.currentTimeMillis()}")
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
    //println(s"3:${System.currentTimeMillis()}")
  }
}

class DefaultWillGenerator(generators: List[Generator]) extends WillGenerator {
  def get(ctx: WillGeneratorContext): List[(Path,Array[Byte])] = {
    val rootCachePath = rootPath.resolve("cache")
    val fromPostfix = ".scala"
    Files.createDirectories(rootCachePath)
    withIndex(ctx)(for {
      path <- ctx.fromFiles.filter(_.toString.endsWith(fromPostfix))
      toData <- Option(pathToData(path,rootCachePath)) if toData.length > 0
    } yield path.getParent.resolve(s"$toPrefix${path.getFileName}") -> toData)
  }
  def pkgNameToId(pkgName: String): String =
    """[\._]+([a-z])""".r.replaceAllIn(s".$pkgName",m=>m.group(1).toUpperCase)
  def withIndex(ctx: WillGeneratorContext): List[(Path,Array[Byte])] => List[(Path,Array[Byte])] = args => {
    val pattern = """\((\S+)\s(\S+)\s(\S+)\)""".r
    val indexes = (for {
      (path,data) <- args
      pos = data.indexOf('\n'.toByte)
      firstLine = new String(data,0,pos,UTF_8)
      dir = path.getParent
      mat <- pattern.findAllMatchIn(firstLine)
    } yield {
      val Seq(pkg,app,expr) = mat.subgroups
      if(app=="DefApp"){
        val modParentPath = ctx.dirToModDir(dir)
        val end = modParentPath.removed.map(n=>s".$n").mkString
        assert(pkg.endsWith(end))
        val appPkg = pkg.substring(0,pkg.length-end.length)
        modParentPath.path -> GeneratedAppLink(appPkg, s"${pkgNameToId(appPkg)}$app", s"$pkg.$expr")
      } else dir -> GeneratedAppLink(pkg,app,expr)
    }).groupMap(_._1)(_._2).toList.sortBy(_._1).map{ case(parentPath,links) =>
        val Seq(pkg) = links.map(_.pkg).distinct
        val content =
          s"\n// THIS FILE IS GENERATED; C4APPS: ${links.filter(_.expr=="CLASS").map(l=>s"$pkg.${l.app}").mkString(" ")}" +
            s"\npackage $pkg" +
            links.groupBy(_.app).toList.collect{ case (app,links) =>
              val(classLinks,exprLinks) = links.partition(_.expr=="CLASS")
              val tp = if(classLinks.nonEmpty) "class" else "trait"
              val base = if(app.endsWith("DefApp")) "ee.cone.c4di.ComponentsApp"
              else s"${app}Base with ee.cone.c4di.ComponentsApp"
              s"\n$tp $app extends $base {" +
                s"\n  override def components: List[ee.cone.c4di.Component] = " +
                exprLinks.map(c=> s"\n    ${c.expr} ::: ").mkString +
                s"\n    super.components" +
                s"\n}"
            }.sorted.mkString
        parentPath.resolve("c4gen.scala") -> content.getBytes(UTF_8)
    }
    args ++ indexes
  }
  def pathToData(path: Path, rootCachePath: Path): Array[Byte] = {
    val fromData = Files.readAllBytes(path)
    val uuidForPath = UUID.nameUUIDFromBytes(path.toString.getBytes(UTF_8)).toString
    val uuid = UUID.nameUUIDFromBytes(fromData).toString
    val cachePath = rootCachePath.resolve(s"$uuidForPath-$uuid-$version")
    val Name = """.+/(\w+)\.scala""".r
    if(Files.exists(cachePath)) Files.readAllBytes(cachePath) else {
      println(s"parsing $path")
      val content = new String(fromData,UTF_8).replace("\r\n","\n")
      val source = dialects.Scala213(content).parse[Source]
      val Parsed.Success(source"..$sourceStatements") = source
      val resStatements: List[Generated] = for {
        sourceStatement <- (sourceStatements:Seq[Stat]).toList
        q"package $n { ..$packageStatements }" = sourceStatement
        res <- {
          val packageStatementsList = (packageStatements:Seq[Stat]).toList
          val parseContext = new ParseContext(packageStatementsList, path.toString, n.syntax)
          val generatedWOComponents: List[Generated] = generators.flatMap(_.get(parseContext))
          val parsedGenerated = generatedWOComponents.collect{ case c: GeneratedCode => c.content.parse[Stat] match { // c.content.parse[Source].get.stats}.flatten
            case Success(stat) => stat
            case Error(position, str, exception) =>
              println(c.content)
              throw exception
          }}
          val parsedAll = packageStatementsList ::: parsedGenerated
          val compParseContext = new ParseContext(parsedAll, path.toString, n.syntax)
          val generated: List[Generated] = generatedWOComponents ::: ComponentsGenerator.get(compParseContext)
          // val patches: List[Patch] = generated.collect{ case p: Patch => p }
          val statements: List[Generated] = generated.reverse.dropWhile(_.isInstanceOf[GeneratedImport]).reverse
          // if(patches.nonEmpty) patches else
          if(statements.isEmpty) statements
            else List(GeneratedCode(s"\npackage $n {")) ::: statements ::: List(GeneratedCode("\n}"))
        }
      } yield res;
      {
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
            s"(${s.pkg} ${s.app} ${s.expr})"
          }.mkString +
          "\n" +
          content
        val toData = contentWithLinks.getBytes(UTF_8)
        Files.write(cachePath,toData)
        toData
      }
    }
  }
}

object ImportGenerator extends Generator {
  def get(parseContext: ParseContext): List[Generated] = parseContext.stats.flatMap{
    case q"import ..$importers" =>
      val nextImporters = importers.map{
        case initialImporter@importer"$eref.{..$importeesnel}" =>
          importer"$eref._" match {
            case j@importer"ee.cone.c4di._" => j
            case j@importer"ee.cone.c4assemble._" => j
            case j@importer"ee.cone.c4proto._" => j
            case _ => initialImporter
          }
      }
      List(GeneratedImport("\n" + q"import ..$nextImporters".syntax))
    case _ => Nil
  }
}

object AppGenerator extends Generator {
  def get(parseContext: ParseContext): List[Generated] = for {
    cl <- Util.matchClass(parseContext.stats) if cl.mods.collectFirst{ case mod"@c4app" => true }.nonEmpty
    res <- cl.name match {
      case Util.UnBase(app) => List(GeneratedAppLink(parseContext.pkg,app,"CLASS"))
      case _ => Nil
    }
  } yield res
}

object Util {
  val UnBase = """(\w+)Base""".r
  /*
  def comment(stat: Stat): String=>String = cont =>
    cont.substring(0,stat.pos.start) + " /* " +
      cont.substring(stat.pos.start,stat.pos.end) + " */ " +
      cont.substring(stat.pos.end)*/

  def unBase(name: String, pos: Int)(f: String=>Seq[Generated]): Seq[Generated] =
    name match {
      case UnBase(n) => f(n)
      case n => throw new Exception(s"can not unBase $n") //List(Patch(pos,"Base"))
    }
  def matchClass(stats: List[Stat]): List[ParsedClass] = stats.flatMap{
    case q"..$cMods class ${nameNode@Type.Name(name)}[..$typeParams] ..$ctorMods (...$params) extends ..$ext { ..$stats }" =>
      List(new ParsedClass(cMods.toList,nameNode,name,typeParams.toList,params.map(_.toList).toList,ext.map{ case t:Tree=>t }.toList,stats.toList))
    case _ => Nil
  }


  def singleSeq[I](l: Seq[I]): Seq[I] = {
    assert(l.size<=1)
    l
  }

  def matchThisOrParent(cond: Path=>Boolean): Path=>Option[Path] = {
    def inner(path: Path): Option[Path] =
      if(cond(path)) Option(path) else Option(path.getParent).flatMap(inner)
    inner
  }

  def pkgInfo(pkgRootPath: Path, path: Path): Option[PkgInfo] =
    if(path.startsWith(pkgRootPath)){
      val pkgPath = pkgRootPath.relativize(path)
      val pkgName = pkgPath.iterator.asScala.mkString(".")
      Option(PkgInfo(pkgPath,pkgName))
    } else None



  def toBinFileList(in: Iterable[(Path,List[String])]): List[(Path,Array[Byte])] =
    in.map{ case (path,lines) => path->lines.mkString("\n").getBytes(UTF_8) }.toList.sortBy(_._1)
}
case class PkgInfo(pkgPath: Path, pkgName: String)
class ParsedClass(
  val mods: List[Mod], val nameNode: Type.Name, val name: String,
  val typeParams: List[Type.Param], val params: List[List[Term.Param]],
  val ext: List[Tree], val stats: List[Stat]
)
class ParseContext(val stats: List[Stat], val path: String, val pkg: String)
trait Generator {
  def get(parseContext: ParseContext): List[Generated]
}

sealed trait Generated
case class GeneratedImport(content: String) extends Generated
case class GeneratedCode(content: String) extends Generated
//case class Patch(pos: Int, content: String) extends Generated
case class GeneratedTraitDef(name: String) extends Generated
case class GeneratedTraitUsage(name: String) extends Generated
case class GeneratedAppLink(pkg: String, app: String, expr: String) extends Generated

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

case class PublicPathRoot(mainPublicPath: Path, pkgName: String, genPath: Path, publicPath: Path)
class PublicPathsGenerator extends WillGenerator {
  def get(ctx: WillGeneratorContext): List[(Path, Array[Byte])] = {
    val mainScalaPathOpt = Main.env("C4GENERATOR_MAIN_SCALA_PATH").map(Paths.get(_))
    val mainPublicPathOpt = Main.env("C4GENERATOR_MAIN_PUBLIC_PATH").map(Paths.get(_))
    val roots: List[PublicPathRoot] = for {
      mainScalaPath <- mainScalaPathOpt.toList
      mainPublicPath <- mainPublicPathOpt.toList
      path <- ctx.fromFiles.filter(_.getFileName.toString == "ht.scala")
      pkgInfo <- Util.pkgInfo(mainScalaPath,path.getParent)
    } yield {
      val genPath = path.resolveSibling("c4gen.htdocs.scala")
      val publicPath = mainPublicPath.resolve(pkgInfo.pkgPath)
      PublicPathRoot(mainPublicPath,pkgInfo.pkgName,genPath,publicPath)
    }
    val isModRoot = roots.map(_.publicPath).toSet
    val toModRoot = Util.matchThisOrParent(isModRoot)
    val pathByRoot = ctx.fromFiles.groupBy(toModRoot)
    val RefOk = """([\w\-\./]+)""".r
    Util.toBinFileList(roots.flatMap { (root:PublicPathRoot) =>
      val defs = pathByRoot.getOrElse(Option(root.publicPath),Nil)
        .map{ path =>
            val RefOk(r) = root.publicPath.relativize(path).toString
            val rel = root.mainPublicPath.relativize(path)
            val ref = s"/mod/main/$rel"
            (
              s"""    def `/$r` = "$ref" """,
              s"main.${root.pkgName} $ref $rel"
            )
        }
      val lines = if(defs.isEmpty) Nil else
        "/** THIS FILE IS GENERATED; CHANGES WILL BE LOST **/" ::
        s"package ${root.pkgName}" ::
        "object PublicPath {" :: defs.map(_._1) ::: "}" :: Nil
      List(root.genPath -> lines, root.mainPublicPath.resolve("c4gen.ht.links") -> defs.map(_._2))
    }.groupMap(_._1)(_._2).transform((k,v)=>v.flatten))
  }
}

class ModRootsGenerator extends WillGenerator {
  def get(ctx: WillGeneratorContext): List[(Path, Array[Byte])] = {
    for {
      mainScalaPath <- Main.env("C4GENERATOR_MAIN_SCALA_PATH").map(Paths.get(_)).toList
      pp <- ctx.dirToModDir.values
      pkgInfo <- Util.pkgInfo(mainScalaPath,pp.path)
    } {
      assert(!pkgInfo.pkgName.contains("._"),s"unclear name: ${pkgInfo.pkgName}; create dummy *.scala file or remove '_' prefix")
      assert(pp.removed.isEmpty || pp.removed.head.startsWith("_"), s"bad sub: $pp")
    }
    Nil
  }
}


/*
git --git-dir=../.git --work-tree=target/c4generator/to  diff
perl run.pl
git --git-dir=../.git --work-tree=target/c4generator/to  diff target/c4generator/to/c4actor-base
*/
