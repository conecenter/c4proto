package ee.cone.c4generator

import scala.meta._

object ValInNonFinalGenerator extends Generator {
  def get(parseContext: ParseContext): List[Generated] =
    parseContext.stats.flatMap{ stat =>
      stat.collect{
        case q"..$mods class $tname[..$tparams] ..$ctorMods (...$paramss) extends $template"
          if mods.collect{ case mod"final" => true }.isEmpty =>
          (tname,template)
        case q"..$mods trait $tname[..$tparams] extends $template" =>
          (tname,template)
      }.flatMap{
        case (tName,template"{ ..$statsA } with ..$inits { $self => ..$statsB }") =>
          if(statsA.nonEmpty) println(s"warn: early initializer in $tName")
          statsB.collect{
            case q"..$mods val ..$patsnel: $tpeopt = $expr"
              if mods.collect{ case mod"lazy" => true }.isEmpty =>
              val valN = patsnel match {
                case Seq(Pat.Var(Term.Name(n))) => n
                case Seq(a: Tree) => throw new Exception(a.structure)
              }
              GeneratedCode(s"\nclass ${tName}_$valN(nonFinal: $tName) extends UnsafeValInNonFinalWarning(nonFinal.$valN)")
          }
        case _ => Nil
      }
    }
}
