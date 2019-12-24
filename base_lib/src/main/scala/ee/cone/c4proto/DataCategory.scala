package ee.cone.c4proto

import scala.annotation.StaticAnnotation

trait DataCategory extends Product

case class Cat(category: DataCategory*) extends StaticAnnotation

sealed trait DataTypeCategory extends DataCategory

case object B_Cat extends DataTypeCategory
case object C_Cat extends DataTypeCategory
case object D_Cat extends DataTypeCategory
case object E_Cat extends DataTypeCategory
case object N_Cat extends DataTypeCategory
case object S_Cat extends DataTypeCategory
case object U_Cat extends DataTypeCategory
case object V_Cat extends DataTypeCategory
case object T_Cat extends DataTypeCategory