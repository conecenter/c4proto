package ee.cone.c4generator

import scala.collection.immutable.Seq
import scala.annotation.{StaticAnnotation, compileTimeOnly}
import scala.meta._

sealed trait JRule extends Product
case class JStat(content: String) extends JRule
case class JoinDef(defName: String, params: Seq[JConnDef], inKeyType: KeyNSType, outKeyName: Option[String]) extends JRule
sealed trait JConnDef {
  def name: String
  def indexKeyName: String
  def inValOuterType: String
}
case class InJConnDef(name: String, indexKeyName: String, inValOuterType: String, many: Boolean, distinct: Boolean, keyEq: Option[String]) extends JConnDef
case class OutJConnDef(name: String, indexKeyName: String, inValOuterType: String) extends JConnDef
case class KeyValType(name: String, of: List[KeyValType])
case class KeyNSType(key: KeyValType, str: String, ns: String)
case class SubAssembleName(name: String) extends JRule

object ExtractKeyValType {
  def unapply(t: Any): Option[KeyValType] = t match {
    case Some(e) => unapply(e)
    case Type.Name(n) => Option(KeyValType(n,Nil))
    case Type.Apply(Type.Name(n), types:Seq[_]) => Option(KeyValType(n, types.map(unapply(_).get).toList))
    case s: Tree => throw new Exception(s"${s.structure}")
  }
}
object ExtractKeyNSType {
  def unapply(t: Any): Option[KeyNSType] = t match {
    case Some(e) => unapply(e)
    case t: Tree => t match {
      case t"$tp @ns($nsExpr)" =>
        ExtractKeyValType.unapply(tp).map(kvt=>KeyNSType(kvt, s"$tp", s"$nsExpr"))
      case tp =>
        ExtractKeyValType.unapply(tp).map(kvt=>KeyNSType(kvt, s"$tp", ""))
    }
  }
}
/*
object UnBaseGenerator extends Generator {
  val UnBase = """(\w+)Base""".r
  def get(parseContext: ParseContext): List[Generated] = for {
    cl <- Util.matchClass(parseContext.stats) if cl.mods.collect{
      case mod"@assemble" => true
      case mod"@c4assemble" => true
    }.isEmpty
    no <- cl.name match {
      case UnBase(n) =>
        println(s"class ${cl.name}")
        Nil
      case _ => Nil
    }
  } yield no
}*/

trait JoinParamTransformer {
  def transform(parseContext: ParseContext): Term.Param => Option[Term.Param]
}

class AssembleGenerator(joinParamTransforms: List[JoinParamTransformer]) extends Generator {

  def get(parseContext: ParseContext): List[Generated] = for {
    cl <- Util.matchClass(parseContext.stats)
    c4ann <- Util.singleSeq(cl.mods.collect{
      case mod"@assemble" => None
      case mod"@c4assemble(...$e)" => Option(mod"@c4(...$e)")
      case mod"@c4multiAssemble(...$e)" => Option(mod"@c4multi(...$e)")
    })
    res <- Util.unBase(cl.name,cl.nameNode.pos.end) { className =>
      (if(c4ann.isEmpty) Nil else List(GeneratedImport("\nimport ee.cone.c4di._"))) :::
      getAssemble(parseContext, cl, className, c4ann)
    }
  } yield res
  def getAssemble(parseContext: ParseContext, cl: ParsedClass, className: String, c4ann: Option[Mod.Annot]): List[Generated] = {
    val transformers = joinParamTransforms.map(_.transform(parseContext))
    val classArgs = cl.params.toList.flatten.collect{
      case param"..$mods ${Term.Name(argName)}: Class[${Type.Name(typeName)}]" =>
        typeName -> argName
    }.toMap
    def mkLazyVal(name: String, body: String): JStat =
      JStat(s"private lazy val $name = {$body}")
    def classOfExpr(className: String) =
      classArgs.getOrElse(className,s"classOf[$className]") + ".getName"
    def classOfT(kvType: KeyValType): String =
      if(kvType.of.isEmpty) classOfExpr(kvType.name)
      else s"classOf[${kvType.name}[${kvType.of.map(_ => "_").mkString(", ")}]].getName+'['+${kvType.of.map(classOfT).mkString("+','+")}+']'"
    def joinKey(name: String, was: Boolean, key: KeyNSType, value: KeyValType): JStat = {
      val addNS = if(key.ns.isEmpty) "" else s""" + "#" + (${key.ns}) """
      JStat(s"""private def $name: MakeJoinKey = _.util.joinKey($was, "${key.str}"$addNS, ${classOfT(key.key)}, ${classOfT(value)})""")
    }
    def joinKeyB(name: String, body: String): JStat = {
      JStat(s"""private def $name: MakeJoinKey = $body """)
    }
    val rules: List[JRule] = cl.stats.toList.flatMap {
      case q"type $tname = $tpe" => Nil
      case q"type $tname[..$params] = $tpe" => Nil
      case q"import ..$i" => Nil
      case e@q"..$mods val ..$vname: $tpe = $expr" => throw new Exception(s"Don't use val in Assemble: ${e.toString}")
      case q"@ignore def $ename[..$tparams](...$paramss): $tpeopt = $expr" => Nil
      case q"def result: Result = tupled(${Term.Name(joinerName)} _)" =>
        JStat(s"override def resultKey = ${joinerName}_outKey") :: Nil
      case q"def result: Result = $temp" => Nil
      case q"override def subAssembles: $tpeopt = $expr" =>
        expr.collect{
          case q"super.subAssembles" => List("ok")
          case _ => Nil
        }.flatten.headOption.map(_ => Nil).getOrElse(throw new Exception(s"\'override def subAssembles\' doesnt have \'super.subAssembles\'"))
      case q"def ${Term.Name(defName)}(...${Seq(params)}): ${Some(outTypeE)} = $expr" =>
        val param"$keyName: ${ExtractKeyNSType(inKeyType)}" = params.head
        val paramInfo: List[(JConnDef,List[JRule])] = params.tail.toList.map{
          param => transformers.foldLeft(param)((current, transform) => transform(current).getOrElse(current))
        }.map{
          case param"..$mods ${Term.Name(paramName)}: ${Some(inValOuterType)} = $defVal" =>
            val fullNamePrefix = s"${defName}_$paramName"
            val fullName = s"${fullNamePrefix}_inKey"
            (mods,defVal.asInstanceOf[Option[Term]],paramName,fullNamePrefix,fullName,inValOuterType)
        }.map{
          case (mods,defVal,paramName,fullNamePrefix,fullName,inValOuterType@t"OutFactory[${ExtractKeyNSType(outKeyType)},${ExtractKeyValType(outValType)}]") =>
            assert(mods.isEmpty)
            assert(defVal.isEmpty)
            val statements = joinKey(fullName, was=false, outKeyType , outValType) :: Nil
            (OutJConnDef(paramName, fullName, s"$inValOuterType"),statements)
          case (mods,defVal,paramName,fullNamePrefix,fullName,inValOuterType@t"$manyT[${ExtractKeyValType(inValType)}]") =>
            val many = manyT match { case t"Values" => true case t"Each" => false }
            //
            object DistinctAnn
            object WasAnn
            class ByAnn(val keyType: KeyNSType, val keyEq: Option[String])
            val ann = mods.map{
              case mod"@distinct" => DistinctAnn
              case mod"@was" => WasAnn
              case mod"@by[${ExtractKeyNSType(tp)}]" => new ByAnn(tp,None)
              case mod"@byEq[${ExtractKeyNSType(tp)}]($v)" => new ByAnn(tp,Option(s"$v"))
              case s => Utils.parseError(s, parseContext)
            }
            val distinct = ann.contains(DistinctAnn)
            val was = ann.contains(WasAnn)
            val byOpt = ann.collect{ case b: ByAnn => b } match {
              case Seq(tp) => Option(tp)
              case Seq() => None
            }
            val keyEq: Option[String] = for {
              by <- byOpt
              keyEq <- by.keyEq
            } yield keyEq
            //
            val statements = defVal match {
              case None =>
                val by = byOpt.getOrElse(new ByAnn(inKeyType,None))
                joinKey(fullName, was, by.keyType , inValType) :: Nil
              case Some(q"$expr.call") =>
                assert(!was)
                assert(byOpt.isEmpty)
                val subAssembleName = s"${fullNamePrefix}_subAssemble"
                SubAssembleName(subAssembleName) ::
                mkLazyVal(subAssembleName,s"$expr") ::
                joinKeyB(fullName, s"$subAssembleName.resultKey") :: Nil
            }
            (InJConnDef(paramName, fullName, s"$inValOuterType", many, distinct, keyEq),statements)
        }
        val joinDefParams = paramInfo.map(_._1)
        val (outKeyName,outLines): (Option[String],List[JRule]) = outTypeE match {
          case t"Values[(${ExtractKeyNSType(outKeyType)},${ExtractKeyValType(outValType)})]" =>
            val fullName = s"${defName}_outKey"
            val statements = joinKey(fullName,was=false,outKeyType,outValType) :: Nil
            (Option(fullName),statements)
          case t"Outs" =>
            (None,Nil)
        }
        outLines ::: JoinDef(defName,joinDefParams,inKeyType,outKeyName) :: paramInfo.flatMap(_._2)
      case s: Tree => Utils.parseError(s, parseContext)
    }
    val toString =
      s"""getClass.getPackage.getName + ".$className" ${if(cl.typeParams.isEmpty)"" else {
        s""" + '['+ ${cl.typeParams.map(i =>
          classArgs.get(s"${i.name}").fold(s""" "${i.name}" """)(_+".getSimpleName")
        ).mkString("+','+")} +']'"""
      }}"""
    val joinImpl = rules.collect{
      case JoinDef(defName,ioParams,inKeyType,outKeyName) =>
        val params = ioParams.collect{ case p: InJConnDef => p }
        val outParams = ioParams.collect{ case p: OutJConnDef => p }
        val outKeyNames: Seq[String] = outKeyName.fold(outParams.map(_.indexKeyName)){k=>assert(outParams.isEmpty);Seq(k)}
        val (seqParams,eachParams) = params.partition(_.many)
        val (keyEqParams,keyIdParams) = params.partition(_.keyEq.nonEmpty)
        def litOrId(p: InJConnDef): String = p.keyEq.getOrElse("id")
        def parts(map: Seq[InJConnDef]=>Seq[(String,String)]): String =
          map(seqParams).map{ case(nm,id) => s"val ${nm}_arg = iUtil.getValues(${nm}_index,$id,${nm}_warn); "}.mkString +
          map(seqParams).map{ case(nm,id) => s"val ${nm}_isChanged = iUtil.nonEmpty(${nm}_diffIndex,$id); "}.mkString +
          map(eachParams).map{ case(nm,id) => s"val ${nm}_parts = iUtil.partition(${nm}_index,${nm}_diffIndex,$id,${nm}_warn); "}.mkString
        val keyIdParts = parts(pp => for(p <- pp if p.keyEq.isEmpty) yield (p.name,"id"))
        val keyEqParts = parts(pp => for(p <- pp; id <- p.keyEq) yield (p.name,id))
        val doJoin = s"${defName}(id.asInstanceOf[${inKeyType.str}],${ioParams.map(p=>s"${p.name}_arg.asInstanceOf[${p.inValOuterType}]").mkString(",")}).foreach(add)"
        val isChangedCond = params.map(p=>s"${p.name}_isChanged").mkString("("," || ",")")
        val nonEmptyCond = seqParams.filter(_.keyEq.isEmpty).map(p=>s"${p.name}_arg.nonEmpty").mkString("("," || ",")")
        val add = outKeyName.fold("def add(value: DOut): Unit = { val _ = buffer.addOne(value) }")(_=>s"def add(pair:(Any,Product)): Unit = { val _ = buffer.addOne(outFactory.result(pair)) }")
        val body = if(eachParams.isEmpty)
          s"""{
          |    import transJoin._
          |    import dirJoin._
          |    $keyIdParts
          |    $add
          |    def execute() = if(
          |      $nonEmptyCond &&
          |      $isChangedCond
          |    ) $doJoin
          |  }
          |""" else
          s""" extends PartMultiFor.Handler${eachParams.size} with ProdMultiFor.Handler${eachParams.size} {
          |    import transJoin._
          |    import dirJoin._
          |    $keyIdParts
          |    $add
          |    def handleProducts(${eachParams.map(p=>s"${p.name}_arg: Product").mkString(", ")}): Unit = $doJoin
          |    def handleMultiForParts(${eachParams.map(p=>s"${p.name}_part: MultiForPart").mkString(", ")}): Unit = {
          |      ${eachParams.map(p=>s"val ${p.name}_isChanged = ${p.name}_part.isChanged; ").mkString}
          |      if(
          |        ${if(eachParams.exists(_.keyEq.isEmpty))"" else s"$nonEmptyCond && "}
          |        $isChangedCond
          |      ) ProdMultiFor.foreach(${eachParams.map(p=>s"${p.name}_part.items(), ").mkString}this)
          |    }
          |    def execute(): Unit =
          |      PartMultiFor.foreach(${eachParams.map(p=>s"${p.name}_parts, ").mkString}this)
          |  }
          |"""
        s"""  import ee.cone.c4assemble.Types.{DiffIndexRawSeq,Index}
           |  import scala.concurrent.Future
           |  private final class ${defName}_Join(val indexFactory: IndexFactory) extends Join(
           |    $toString,
           |    "${defName}",
           |    Seq(${params.map(_.indexKeyName).mkString(",")}).map(_(indexFactory)),
           |    Seq(${outKeyNames.mkString(",")}).map(_(indexFactory)),
           |  ) {
           |    def joins(diffIndexRawSeq: DiffIndexRawSeq, executionContext: OuterExecutionContext): TransJoin =
           |      new ${defName}_TransJoin(this,diffIndexRawSeq,executionContext)
           |  }
           |  private final class ${defName}_TransJoin(join: ${defName}_Join, diffIndexRawSeq: DiffIndexRawSeq, val executionContext: OuterExecutionContext) extends TransJoin {
           |    import join._
           |    implicit val ec = executionContext.value
           |    val iUtil = indexFactory.util
           |    val Seq(${params.map(p=>s"${p.name}_diffIndex").mkString(",")}) = diffIndexRawSeq
           |    ${keyEqParams.map(p=>s"val ${p.name}_isAllChanged = iUtil.nonEmpty(${p.name}_diffIndex,${litOrId(p)}); ").mkString}
           |    val invalidateKeySetOpt =
           |      ${if(keyEqParams.isEmpty)"" else s"""if(${keyEqParams.map(p=>s"${p.name}_isAllChanged").mkString(" || ")}) None else """}
           |      Option(iUtil.keyIteration(Seq(${keyIdParams.map(p=>s"${p.name}_diffIndex").mkString(",")}),executionContext))
           |    ${params.map(p => if(p.distinct) s"""val ${p.name}_warn = "";""" else s"""val ${p.name}_warn = "${defName} ${p.name} "+${p.indexKeyName}(indexFactory).valueClassName;""").mkString}
           |    def dirJoin(dir: Int, indexRawSeq: Seq[Index]): Future[DOut] =
           |      new ${defName}_DirJoin(this,dir,indexRawSeq).execute()
           |  }
           |  private final class ${defName}_DirJoin(transJoin: ${defName}_TransJoin, dir: Int, indexRawSeq: Seq[Index]) extends KeyIterationHandler {
           |    import transJoin._
           |    ${outKeyName.fold("")(_=>s"val outFactory = iUtil.createOutFactory(0,dir);")}
           |    ${outParams.zipWithIndex.map{ case (p,i) => s"val ${p.name}_arg = iUtil.createOutFactory($i,dir); "}.mkString}
           |    val Seq(${params.map(p=>s"${p.name}_index").mkString(",")}) = indexRawSeq
           |    $keyEqParts
           |    val invalidateKeySet = invalidateKeySetOpt.getOrElse(iUtil.keyIteration(Seq(${keyIdParams.map(p=>s"${p.name}_index").mkString(",")}),executionContext))
           |    def execute(): Future[DOut] = invalidateKeySet.execute(this)
           |    def execute(id: Any, buffer: collection.mutable.Buffer[DOut]) = new ${defName}_KeyJoin(transJoin,this,id,buffer).execute()
           |  }
           |  private final class ${defName}_KeyJoin(transJoin: ${defName}_TransJoin, dirJoin: ${defName}_DirJoin, id: Any, buffer: collection.mutable.Buffer[DOut]) $body
           |
           |""".stripMargin
    }.mkString
    val dataDependencies = rules.collect {
      case d: JoinDef => s"new ${d.defName}_Join(indexFactory)"
    }.mkString(s"  def dataDependencies = indexFactory => List(",",",").map(indexFactory.createJoinMapIndex)\n")
    val statRules = rules.collect{ case JStat(c) => s"  $c\n" }.mkString



    val (subAssembleImp,subAssembleWith,subAssembleDef) = (rules.collect{ case SubAssembleName(n) => n }.distinct) match {
      case Seq() => (Nil,"","")
      case s => (GeneratedCode("import ee.cone.c4assemble.CallerAssemble")::Nil, " with CallerAssemble", s.mkString(s"override def subAssembles = List(",",",") ::: super.subAssembles\n"))
    }

    val paramNames = cl.params.map(params=>params.map{
      case param"..$mods $name: $tpeopt = $expropt" => if(expropt.isEmpty) Option(name) else None
    }.flatten.mkString("(",",",")")).mkString
    val paramNamesWithTypes = cl.params.map(params=>params.map{
      case param"..$mods $name: $tpeopt = $expropt" => if(expropt.isEmpty) Option(param"..${mods.collect{ case mod"valparam" => None case o => Option(o) }.flatten} $name: $tpeopt") else None
    }.flatten)

    val res = c4ann.fold(
      q"""class ${Type.Name(className)} [..${cl.typeParams}] (...$paramNamesWithTypes)"""
    )(ann=>
      q"""$ann final class ${Type.Name(className)} [..${cl.typeParams}] (...$paramNamesWithTypes)"""
    )

    val factoryStats = MultiGenerator.getForStats(List(res))
    //cont.substring(0,className.pos.end) + "_Base" + cont.substring(className.pos.end) +
    factoryStats ::: subAssembleImp ::: GeneratedCode(
      s"${res.syntax} extends ${cl.name}$paramNames with Assemble$subAssembleWith " +
      s"{\n$statRules$joinImpl$dataDependencies$subAssembleDef}"
    ) :: Nil // :: components
  }
}
