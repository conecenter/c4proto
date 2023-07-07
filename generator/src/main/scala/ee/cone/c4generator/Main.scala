package ee.cone.c4generator

import java.nio.file.{Files, Path, Paths}
import java.util.UUID
import java.nio.charset.StandardCharsets.UTF_8
import scala.concurrent.{Await, ExecutionContextExecutor, Future}
import scala.concurrent.duration.Duration
import scala.meta.parsers.Parsed.{Error, Success}
import scala.meta._
import scala.jdk.CollectionConverters.IterableHasAsScala
import scala.jdk.CollectionConverters.IteratorHasAsScala

object Main {
  def defaultGenerators(protocolStatsTransformers: List[ProtocolStatsTransformer]): List[Generator] = {
    val protocolGenerator = new ProtocolGenerator(protocolStatsTransformers)
    val metaGenerator = new MetaGenerator(protocolStatsTransformers)
    List(
      ImportGenerator,
      new TimeGenerator(protocolGenerator, metaGenerator),
      new AssembleGenerator(TimeJoinParamTransformer :: Nil),
      metaGenerator,
      protocolGenerator,
      MultiGenerator,
      FieldAccessGenerator,
      AppGenerator,
      TagGenerator,
      ProtoChangerGenerator,
      //ProductCheckGenerator,
    ) //,UnBaseGenerator
  }
  def main(args: Array[String]): Unit = new RootGenerator(defaultGenerators(Nil) ::: List(
    ValInNonFinalGenerator
  )).run(args)
}

case class WillGeneratorContext(
  fromFiles: List[Path], dirToModDir: Map[Path,ParentPath],
  workPath: Path, version: String,
  srcRoots: Map[String, List[Path]], pubRoots: Map[String, List[Path]],
  deps: Map[String, List[String]], modNames: List[String]
)
trait WillGenerator {
  def get(ctx: WillGeneratorContext): List[(Path,Array[Byte])]
}

class ParentPath(val path: Path, val removed: List[String])
case class ConfItem(from: String, to: String)
class RootGenerator(generators: List[Generator], fromTextGenerators: List[FromTextGenerator]=Nil) {
  def willGenerators: List[WillGenerator] = List(
    new DefaultWillGenerator(generators),
    new PublicPathsGenerator,
    new ModRootsGenerator,
    new FromTextWillGenerator(fromTextGenerators)
  )
  //
  def isGenerated(fileName: String): Boolean = fileName.startsWith("c4gen.") || fileName.startsWith("c4gen-")
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
  def run(args: Array[String]): Unit = {
    val opt = args.grouped(2).map{ case Array(a,b) => (a,b) }.toMap
    val workPath = Paths.get(opt("--context"))
    val version = opt("--ver")
    //println(s"1:${System.currentTimeMillis()}") //130
    val conf = Files.readAllLines(workPath.resolve("target/c4/generator.conf")).asScala.toSeq.grouped(3).toSeq
      .groupMap(_(0))(_.tail match{ case Seq(fr,to) => ConfItem(fr,to) }).withDefaultValue(Nil)
    val dirs = for(it <- (conf("C4SRC") ++ conf("C4PUB")).distinct) yield workPath.resolve(it.to).toString
    val cmd = "find" :: dirs.toList ::: "-type" :: "f" :: Nil
    val filesA = new String(new ProcessBuilder(cmd:_*).start().getInputStream.readAllBytes(), UTF_8).split("\n") // Files.walk is much slower
    val (wasFiles,fromFilesAll) = filesA.partition{ path => isGenerated(path.substring(path.lastIndexOf("/")+1)) }
    val skipDirs = (for(it <- conf("C4GENERATOR_DIR_MODE") if it.from == "OFF") yield workPath.resolve(it.to)).toSet
    val fromFiles = fromFilesAll.map(Paths.get(_)).filterNot(path => skipDirs(path.getParent)).sorted.toList
    val was = (for { pathStr <- wasFiles } yield {
      val path = Paths.get(pathStr)
      path -> Files.readAllBytes(path)
    }).toMap
    //println(s"4:${System.currentTimeMillis()}") //1s
    val deps = conf("C4DEP").toList.groupMap(_.from)(_.to).withDefaultValue(Nil)
    val srcRoots = conf("C4SRC").toList.groupMap(_.from)(it=>workPath.resolve(it.to)).withDefaultValue(Nil)
    val pubRoots = conf("C4PUB").toList.groupMap(_.from)(it=>workPath.resolve(it.to)).withDefaultValue(Nil)
    val modNames = (conf("C4DEP").map(_.from) ++ conf("C4DEP").map(_.to)).distinct.sorted.toList
    val willGeneratorContext =
      WillGeneratorContext(fromFiles, getModDirs(fromFiles), workPath, version, srcRoots, pubRoots, deps, modNames)
    val will = willGenerators.flatMap(_.get(willGeneratorContext))
    assert(will.forall{ case(path,_) => isGenerated(path.getFileName.toString) })
    //println(s"2:${System.currentTimeMillis()}")
    for(path <- was.keySet -- will.toMap.keys) {
      println(s"removing $path")
      Files.delete(path)
    }
    for{
      (path,data) <- will if !java.util.Arrays.equals(data,was.getOrElse(path,Array.empty))
    } {
      println(s"saving $path")
      Util.ignoreTheSamePath(Files.write(path,data))
    }
    //println(s"3:${System.currentTimeMillis()}")
  }
}

object Cached {
  def apply(ctx: WillGeneratorContext, postfix: String, tasks: List[(Path,String=>String)]): List[(Path,Array[Byte])] = {
    val rootCachePath = Files.createDirectories(ctx.workPath.resolve("target/c4/gen/cache"))
    Await.result({
      implicit val ec: ExecutionContextExecutor = scala.concurrent.ExecutionContext.global
      Future.sequence(tasks.map(pt=>Future{
        val (path,transform) = pt
        val fromData = Files.readAllBytes(path)
        val uuidForPath = UUID.nameUUIDFromBytes(s"$path$postfix".getBytes(UTF_8)).toString
        val uuid = UUID.nameUUIDFromBytes(fromData).toString
        val cachePath = rootCachePath.resolve(s"$uuidForPath-$uuid-${ctx.version}")
        val out = if (Files.exists(cachePath)) Files.readAllBytes(cachePath) else {
          println(s"parsing $path")
          val toData = transform(new String(fromData,UTF_8)).getBytes(UTF_8)
          Util.ignoreTheSamePath(Files.write(cachePath, toData))
          toData
        }
        (path,out)
      }))
    },Duration.Inf).collect {
      case (path, toData) if toData.nonEmpty =>
        path.getParent.resolve(s"c4gen.${path.getFileName}$postfix") -> toData
    }
  }
}

case class FromTextGeneratorContext(pkgInfo: PkgInfo, content: String)
trait FromTextGenerator {
  def ext: String
  def get(context: FromTextGeneratorContext): String
}

class FromTextWillGenerator(innerList: List[FromTextGenerator]) extends WillGenerator {
  def get(ctx: WillGeneratorContext): List[(Path,Array[Byte])] = {
    val ExtRegEx = """.*\.(\w+)""".r
    val handlers = innerList.groupMapReduce(_.ext)(h=>h)((a,b)=>throw new Exception("ext conflict"))
    val tasks = for {
      mainScalaPath <- Util.singleSeq(ctx.srcRoots("main")).toList
      path <- ctx.fromFiles
      ExtRegEx(ext) <- List(path.toString) if handlers.contains(ext)
      pkgInfo <- Util.pkgInfo(mainScalaPath, path.getParent)
    } yield (path, (content: String)=>handlers(ext).get(FromTextGeneratorContext(pkgInfo, content)))
    Cached(ctx, ".scala", tasks)
  }
}

object JoinStr {
  def apply(parts: String*): String = parts.mkString
}

case class AutoMixerDescr(path: Path, name: String, content: String)
class DefaultWillGenerator(generators: List[Generator]) extends WillGenerator {
  def get(ctx: WillGeneratorContext): List[(Path,Array[Byte])] =
    withIndex(ctx)(Cached(ctx, "", ctx.fromFiles.filter(_.toString.endsWith(".scala")).map(path=>(path, pathToData(path)))))
  private def withIndex(ctx: WillGeneratorContext): List[(Path,Array[Byte])] => List[(Path,Array[Byte])] = args => {
    val pattern = """\((\S+)\s(\S+)\s(\S+)\)""".r
    val rawAppLinks = for {
      (path,data) <- args
      pos = data.indexOf('\n'.toByte)
      firstLine = new String(data,0,pos,UTF_8)
      dir = path.getParent
      mat <- pattern.findAllMatchIn(firstLine)
    } yield {
      val Seq(pkg,app,expr) = mat.subgroups
      dir -> GeneratedAppLink(pkg,app,expr)
    }
    val (rawDefAppLinks, customAppLinks) = rawAppLinks.partition{ case (_,link) => link.app == "DefApp" }
    val defAppLinks = rawDefAppLinks.map{ case (dir,link) =>
      val modParentPath = ctx.dirToModDir(dir)
      val end = modParentPath.removed.map(n => s".$n").mkString
      assert(link.pkg.endsWith(end), s"${link.pkg} $end")
      val pkg = link.pkg.substring(0, link.pkg.length - end.length)
      val app = s"${Util.pkgNameToId(s".$pkg")}${link.app}"
      val expr = s"${link.pkg}.${link.expr}"
      modParentPath.path -> GeneratedAppLink(pkg, app, expr)
    }
    val indexes = (defAppLinks++customAppLinks).groupMap(_._1)(_._2).toList.sortBy(_._1).map{ case(parentPath,links) =>
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
    val defAppsByModDir = defAppLinks.groupMap(_._1){ case (k,v) => s"${v.pkg}.${v.app}" }.transform((k,v)=>v.distinct)
    val MainPkg = """(\w+)\.(.+)""".r
    val autoMixers = (new ByPriority[String,Option[AutoMixerDescr]](
      modName=>(ctx.deps(modName), depOpts => {
        val deps: List[AutoMixerDescr] = depOpts.flatten
        val MainPkg(main, pkg) = modName
        val infix = pkg.replace('.', '/')
        val dirs = for(srcRoot <- ctx.srcRoots(main) if main == "main") yield srcRoot.resolve(infix)
        val defApps = for(dir <- dirs; defApp <- defAppsByModDir.getOrElse(dir,Nil)) yield defApp
        if (deps.isEmpty && defApps.isEmpty) None else {
          val Seq(dir) = dirs
          val defApp = defApps.mkString(" with ")
          val compContent = if(defApp.isEmpty) "Nil" else s"(new ${defApp} {}).components"
          val mixerName = s"${Util.pkgNameToId(s".$pkg")}AutoMixer"
          val content = JoinStr(
              s"package $pkg",
              s"\nobject $mixerName extends ee.cone.c4di.AutoMixer(",
              s"\n  () => $compContent,",
              deps.map(d => s"\n  ${d.name} ::").mkString,
              s"\n  Nil",
              s"\n)",
          )
          Option(AutoMixerDescr(dir.resolve("c4gen-base.scala"), s"$pkg.$mixerName", content))
        }
      })
    ))(ctx.modNames)
    val autoMixerIndexes = autoMixers.flatten.map(am=>am.path->am.content.getBytes(UTF_8))
    args ++ indexes ++ autoMixerIndexes
  }
  private def pathToData(path: Path): String => String = contentRaw => {
      val source = dialects.Scala213(contentRaw.replace("\r\n","\n")).parse[Source]
      val sourceStatements = source match {
        case Parsed.Success(source"..$sourceStatements") => sourceStatements
        case Parsed.Error(position, string, ex) => throw new Exception(s"Parse exception in ($path:${position.startLine})", ex)
      }
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

        val code = resStatements.flatMap{
          case c: GeneratedImport => List(c.content)
          case c: GeneratedCode => List(c.content)
          case c: GeneratedAppLink => Nil
          case c => throw new Exception(s"$c")
        }
        val content = code.mkString("\n")
        if(content.isEmpty) "" else
          s"// THIS FILE IS GENERATED; APPLINKS: " +
          resStatements.collect{ case s: GeneratedAppLink =>
            s"(${s.pkg} ${s.app} ${s.expr})"
          }.mkString +
          "\n" +
          content
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

  def pathToId(fileName: String): String = {
    val SName = """.+/([-\w]+)\.scala""".r
    val SName(fName) = fileName
    fName.replace('-', '_')
  }


  def assertFinal(cl: ParsedClass): Unit = {
    assert(cl.mods.collect{ case mod"final" => true }.nonEmpty,s"${cl.name} should be final")
  }

  def ignoreTheSamePath(path: Path): Unit = ()

  def pkgNameToId(pkgName: String): String =
    """[\._]+([a-z])""".r.replaceAllIn(pkgName,m=>m.group(1).toUpperCase)
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

case class PublicPathRoot(mainPublicPath: Path, pkgName: String, genPath: Path, publicPath: Path)
class PublicPathsGenerator extends WillGenerator {
  def get(ctx: WillGeneratorContext): List[(Path, Array[Byte])] = {
    val roots: List[PublicPathRoot] = for {
      mainScalaPath <- Util.singleSeq(ctx.srcRoots("main")).toList
      mainPublicPath <- Util.singleSeq(ctx.pubRoots("main")).toList
      path <- ctx.fromFiles.filter(_.getFileName.toString == "ht.scala")
      pkgInfo <- Util.pkgInfo(mainScalaPath,path.getParent)
    } yield {
//      assert()
      val genPath = path.resolveSibling("c4gen.htdocs.scala")
      val publicPath = mainPublicPath.resolve(pkgInfo.pkgPath)
      PublicPathRoot(mainPublicPath,pkgInfo.pkgName,genPath,publicPath)
    }
    val isModRoot = roots.map(_.publicPath).toSet
    val toModRoot = Util.matchThisOrParent(isModRoot)
    val pathByRoot = ctx.fromFiles.groupBy(toModRoot)
    val RefOk = """([\w\-\./]+)""".r
    val ExtRegEx = """.*\.(\w+)""".r
    val fullMimeTypesMap: Map[String, String] = (for {
      path <- ctx.fromFiles.filter(_.getFileName.toString == "MimeTypesMap.scala")
      Parsed.Success(source"..$sourceStatements") = Files.readString(path).parse[Source]
      statement <- sourceStatements
      (ext,tp) <- statement.collect{
        case q"${Lit.String(ext)} -> ${Lit.String(tp)}" => (ext,tp)
      }
    } yield (ext,tp)).groupMapReduce(_._1)(_._2){(a,b)=>
      assert(a==b)
      a
    }
    val imageExtensions: Set[String] = fullMimeTypesMap.collect{
      case (key, value) if value.startsWith("image/") => key
    }.toSet
    Util.toBinFileList(roots.flatMap { (root:PublicPathRoot) =>

      val defs = pathByRoot.getOrElse(Option(root.publicPath),Nil)
        .map{ path =>
          val RefOk(r) = root.publicPath.relativize(path).toString
          val ExtRegEx(ext) = r
          val rel = root.mainPublicPath.relativize(path)
          val ref = s"/mod/main/$rel"

          val publicPath = ext match {
            case "svg" => s"""SVGPublicPath("$ref")"""
            case e if imageExtensions.contains(e) => s"""NonSVGPublicPath("$ref")"""
            case _ => s"""DefaultPublicPath("$ref")"""
          }
          (
            s"    def `/$r` = $publicPath ",
            s"main.${root.pkgName} $ref $rel",
            s"        `/$r` :: "
          )
        }
      val lines = /*if(defs.isEmpty) Nil else*/
        "/** THIS FILE IS GENERATED; CHANGES WILL BE LOST **/" ::
        s"package ${root.pkgName}" ::
        "import ee.cone.c4actor.{DefaultPublicPath, SVGPublicPath, NonSVGPublicPath, PublicPathCollector}" ::
        "abstract class PublicPathCollectorImpl extends PublicPathCollector {" ::
        "    def allPaths = " ::
        defs.map(_._3) :::
        "        Nil" ::
        defs.map(_._1) :::
        "}" :: Nil
      List(root.genPath -> lines, root.mainPublicPath.resolve("c4gen.ht.links") -> defs.map(_._2))
    }.groupMap(_._1)(_._2).transform((k,v)=>v.flatten))
  }
}

class ModRootsGenerator extends WillGenerator {
  def get(ctx: WillGeneratorContext): List[(Path, Array[Byte])] = {
    for {
      mainScalaPath <- Util.singleSeq(ctx.srcRoots("main")).toList
      pp <- ctx.dirToModDir.values
      pkgInfo <- Util.pkgInfo(mainScalaPath,pp.path)
    } {
      assert(!pkgInfo.pkgName.contains("._"),s"unclear name: ${pkgInfo.pkgName}; create dummy *.scala file or remove '_' prefix")
      assert(pp.removed.isEmpty || pp.removed.head.startsWith("_"), s"bad sub: ${pp.removed}")
    }
    Nil
  }
}


/*
git --git-dir=../.git --work-tree=target/c4generator/to  diff
perl run.pl
git --git-dir=../.git --work-tree=target/c4generator/to  diff target/c4generator/to/c4actor-base
*/
