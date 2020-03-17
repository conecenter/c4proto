package ee.cone.c4generator

import scala.meta._
import scala.meta.internal.trees.InternalTree

object FieldAccessGenerator extends Generator {
  def get(parseContext: ParseContext): List[Generated] = parseContext.stats.flatMap{
    case Defn.Object(Seq(mod"@fieldAccess"),baseObjectNameNode@Term.Name(baseObjectName),code) =>
      genCode(parseContext, baseObjectNameNode, baseObjectName, code, (objectName, nCode) =>
        GeneratedCode("\n" + Defn.Object(Nil, Term.Name(objectName), nCode.asInstanceOf[Template]).syntax)
      )
    case Defn.Trait(Seq(mod"@fieldAccess"),baseObjectNameNode@Type.Name(baseObjectName),x,y,code) =>
      Utils.parseError(code, parseContext, "not safe to use @fieldAccess on non-object-s")
    case Defn.Class(Seq(mod"@fieldAccess"),baseObjectNameNode@Type.Name(baseObjectName),x,y,code) =>
      Utils.parseError(code, parseContext, "not safe to use @fieldAccess on non-object-s")
    case _ => Nil
  }

  private def genCode(parseContext: ParseContext, baseObjectNameNode: InternalTree, baseObjectName: String, code: Template, genObj: (String, Tree) => GeneratedCode): Seq[Generated] = {
    Util.unBase(baseObjectName, baseObjectNameNode.pos.end) { objectName =>
      //case q"@fieldAccess object $name $code" =>
      //  println(s"=-=$code")
      //Util.comment(code)(cont) +
//      def isSimple(args: Seq[Seq[scala.meta.Term]]) = args match {
//        case List(q"_.$field" :: tail) => true
//        case List(_ :: q"_.$field" :: tail) => true
//        case _ => false
//      }
      def prepend(arg: scala.meta.Term, args: List[List[scala.meta.Term]]) = args match {
        case head :: tail => (arg::head)::tail
      }
      def valToFieldName(nameStr: String) = {
        val fieldShortName = Lit.String(nameStr)
        q"getClass.getName + '.' + $fieldShortName"
      }
      def note(id: Int, hint: String): Unit = {
        //println(s"fieldAccess $id $hint")
      }
      val nCode = code.transform {
        case q"ProdLens.of[$from, $to](...$args)" =>
          note(3,"ProdLens.of[]")
          val List((get@q"_.$fieldT") :: tail) = args
          val nArgs = List(get :: q"value=>model=>model.copy($fieldT=value)" :: tail)
          val Term.Name(fieldStr) = fieldT
          val fieldName = Lit.String(fieldStr)
          genOfSetStrict(from, to, prepend(fieldName,nArgs))
        case q"..$mods val $name: $t[$from, $to] = ProdLens.of(...$args)" =>
          note(2,"ProdLens.of")
          val fieldName = valToFieldName(s"$name")
          val List((get@q"_.$fieldT") :: tail) = args
          val nArgs = List(get :: q"value=>model=>model.copy($fieldT=value)" :: tail)
          q"..$mods val $name: $t[$from, $to] = ${genOfSetStrict(from, to, prepend(fieldName,nArgs))}"
        case q"..$mods val $name: $t[$from, $to] = ProdLens.ofSet(...$args)" =>
          note(1,"ProdLens.ofSet")
          val fieldName = valToFieldName(s"$name")
          q"..$mods val $name: $t[$from, $to] = ${genOfSetStrict(from, to, prepend(fieldName,args))}"

        case q"..$mods def $name(...$dargs): $t[$from, $to] = UnsafeProdLens.ofSet(...$args)" =>
          note(5,"UnsafeProdLens")
          q"..$mods def $name(...$dargs): $t[$from, $to] = ${genOfSetStrict(from, to, args)}"

        case q"..$mods val $name: $t[$from, $to] = ProdGetter.of(...$args)" =>
          note(0,"ProdGetter")
          val fieldName = valToFieldName(s"$name")
          q"..$mods val $name: $t[$from, $to] = ${genOfGetStrict(from, to, prepend(fieldName,args))}"

        case q"..$mods def $name(...$dargs): $t[$from, $to] = UnsafeProdGetter.of(...$args)" =>
          note(4,"UnsafeProdGetter")
          q"..$mods def $name(...$dargs): $t[$from, $to] = ${genOfGetStrict(from, to, args)}"

        case code@q"$_.ofSet(...$args)" =>
          Utils.parseError(code, parseContext,s"@fieldAccess .ofSet($args) should should directly define val")
        case code@q"$_.ofSet[$_, $_](...$args)" =>
          Utils.parseError(code, parseContext,s"@fieldAccess .ofSet($args) should not have type args")
        case q"$_.of[$_, $_](...$args)" =>
          Utils.parseError(code, parseContext,s"@fieldAccess .of($args) may look better w/o type args")
        case code@q"${Term.Name("ProdLens"|"ProdGetter"|"UnsafeProdGetter")}.of(...$args)" =>
          Utils.parseError(code, parseContext,s"@fieldAccess .of($args) should should directly define val")

      }

      List(
        GeneratedImport("import ee.cone.c4di.{TypeKey, CreateTypeKey}"),
        genObj(objectName, nCode)
      )
    }
  }

  private def genOfGetStrict(
    from: Type, to: Type,
    args: List[List[scala.meta.Term]]
  ): Term = {
    val List(field :: get :: tail) = args
    val fromTypeKey = ComponentsGenerator.getTypeKey(from, None).parse[Term].get
    val toTypeKey = ComponentsGenerator.getTypeKey(to, None).parse[Term].get
    val nArgs = List(get :: field ::
      q"classOf[$from]" :: q"classOf[$to]" ::
      q"$fromTypeKey" :: q"$toTypeKey" ::
      tail
    )
    q"ProdGetter.ofStrict[$from, $to](...$nArgs)"
  }

  private def genOfSetStrict(
    from: Type, to: Type,
    args: List[List[scala.meta.Term]]
  ): Term = {
    val fromTypeKey = ComponentsGenerator.getTypeKey(from, None).parse[Term].get
    val toTypeKey = ComponentsGenerator.getTypeKey(to, None).parse[Term].get
    val List(field :: get :: set :: tail) = args
    val nArgs = List(get :: set :: field ::
      q"classOf[$from]" :: q"classOf[$to]" ::
      q"$fromTypeKey" :: q"$toTypeKey" ::
      tail
    )
    q"ProdLens.ofSetStrict[$from, $to](...$nArgs)"
  }
}

