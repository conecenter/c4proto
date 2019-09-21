package ee.cone.c4generator


import scala.collection.immutable.Seq
import scala.meta._

/*
val (tp,meta) = tpe.get match {
  case t"$tp @meta(..$ann)" ⇒ (tp,ann)
  case a ⇒ (a,Nil)
}
println(meta,meta.map(_.getClass))*/

object LensesGenerator extends Generator {
  def get: Get = { case (code@q"@protocol(...$exprss) object ${objectNameNode@Term.Name(objectName)} extends ..$ext { ..$stats }", fileName) ⇒ Util.unBase(objectName,objectNameNode.pos.end){ objectName ⇒
    stats.collect{ case q"..$mods case class ${Type.Name(messageName)} ( ..$params ) extends ..$ext" if mods.collectFirst{ case mod"@GenLens" ⇒ true }.nonEmpty =>
      val resultType = messageName // simplification
      val lensesLines = params.map {
        case param"..$mods ${Term.Name(propName)}: $tpeopt = $v" ⇒
          val Seq(id) = mods.collect{ case mod"@Id(${Lit(id:Int)})" ⇒ id }
          val tp = tpeopt.asInstanceOf[Option[Type]].get
          val meta = mods.collect{ case mod"@Meta(...$exprss)" ⇒ parseArgsWithApply(exprss) }.flatten.toList
          getLens(objectName, resultType, id, propName, s"$tp", meta)
        case t: Tree ⇒
          Utils.parseError(t, "lenses", fileName)
      }
      GeneratedCode("\n" +
        s"""object ${resultType}Lenses {
           |  ${lensesLines.mkString("\n")}
           |}
         """.stripMargin
      )
    }
  }}

  def parseArgsWithApply: Seq[Seq[Term]] ⇒ List[String] =
    _.flatMap(_.map(_.toString())).toList

  def getLens(protocolName: String, origType: String, fieldId: Long, fieldName: String, fieldType: String, meta: List[String]): String =
    s"""  val $fieldName: ee.cone.c4actor.ProdLens[$protocolName.$origType, $fieldType] =
       |    ee.cone.c4actor.ProdLens.ofSet(
       |      _.$fieldName,
       |      v ⇒ _.copy($fieldName = v),
       |      "$protocolName.$origType.$fieldName",
       |      ee.cone.c4actor.IdMetaAttr($fieldId),
       |      ee.cone.c4actor.ClassesAttr(
       |        classOf[$protocolName.$origType].getName,
       |        classOf[$fieldType].getName
       |      )${if (meta.isEmpty) "" else meta.mkString(",\n      ", ",\n      ", "")}
       |    )""".stripMargin
}
