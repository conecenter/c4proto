package ee.cone.c4generator

import scala.meta._
import scala.meta.internal.trees.InternalTree

object FieldAccessGenerator extends Generator {
  def get(parseContext: ParseContext): List[Generated] = parseContext.stats.flatMap{
    case Defn.Object(Seq(mod"@fieldAccess"),baseObjectNameNode@Term.Name(baseObjectName),code) =>
      genCode(baseObjectNameNode, baseObjectName, code, (objectName, nCode) =>
        GeneratedCode("\n" + Defn.Object(Nil, Term.Name(objectName), nCode.asInstanceOf[Template]).syntax)
      )
    case Defn.Trait(Seq(mod"@fieldAccess"),baseObjectNameNode@Type.Name(baseObjectName),x,y,code) =>
      genCode(baseObjectNameNode, baseObjectName, code, (objectName, nCode) =>
        GeneratedCode("\n" + Defn.Trait(Nil, Type.Name(objectName), x, y, nCode.asInstanceOf[Template]).syntax)
      )
    case Defn.Class(Seq(mod"@fieldAccess"),baseObjectNameNode@Type.Name(baseObjectName),x,y,code) =>
      genCode(baseObjectNameNode, baseObjectName, code, (objectName, nCode) =>
        GeneratedCode("\n" + Defn.Class(Nil, Type.Name(objectName), x, y, nCode.asInstanceOf[Template]).syntax)
      )
    case _ => Nil
  }

  private def genCode(baseObjectNameNode: InternalTree, baseObjectName: String, code: Template, genObj: (String, Tree) => GeneratedCode): Seq[Generated] = {
    Util.unBase(baseObjectName, baseObjectNameNode.pos.end) { objectName =>
      //case q"@fieldAccess object $name $code" =>
      //  println(s"=-=$code")
      //Util.comment(code)(cont) +
      val nCode = code.transform {
        case q"ProdLens.of[$from, $to](...$args)" =>
          val fromTypeKey = ComponentsGenerator.getTypeKey(from, None).parse[Term].get
          val toTypeKey = ComponentsGenerator.getTypeKey(to, None).parse[Term].get
          val List(head :: tail) = args
          val q"_.$field" = head
          val nArgs = List(head :: q"value=>model=>model.copy($field=value)" ::
            Lit.String(s"$field") ::
            q"classOf[$from]" :: q"classOf[$to]" ::
            q"$fromTypeKey" :: q"$toTypeKey" ::
            tail)
          q"ProdLens.ofSetStrict[$from, $to](...$nArgs)"
        case q"ProdLens.ofFunc[$from, $to](...$args)" =>
          val List(field :: head :: tail) = args
          val fromTypeKey = ComponentsGenerator.getTypeKey(from, None).parse[Term].get
          val toTypeKey = ComponentsGenerator.getTypeKey(to, None).parse[Term].get
          val nArgs = List(head ::
            q"$field" ::
            q"classOf[$from]" :: q"classOf[$to]" ::
            q"$fromTypeKey" :: q"$toTypeKey" ::
            tail)
          q"ProdLens.ofFuncStrict[$from, $to](...$nArgs)"
        case q"ProdLens.ofFunc(...$args)" =>
          throw new Exception(s"ProdLens.ofFunc($args) should have implicit types like ProdLens.ofFunc[FROM, TO](...)")
        case q"ProdLens.of(...$args)" =>
          val List(head :: tail) = args
          val q"_.$field" = head
          val nArgs = List(head :: q"value=>model=>model.copy($field=value)" :: Lit.String(s"$field") :: tail)
          q"ProdLens.ofSet(...$nArgs)"
      }

      List(
        GeneratedImport("import ee.cone.c4di.{TypeKey, CreateTypeKey}"),
        GeneratedImport("import ee.cone.base.util.Never"),
        genObj(objectName, nCode)
      )
    }
  }
}

