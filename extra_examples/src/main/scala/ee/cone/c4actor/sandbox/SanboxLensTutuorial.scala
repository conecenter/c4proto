package ee.cone.c4actor.sandbox

import ee.cone.c4actor.{Lens, NameMetaAttr, ProdLens}
import ee.cone.c4assemble.Getter

object SanboxLensTutuorial {
  def main(args: Array[String]): Unit = {

    val person = D_Person("Dmitri", Phone(123, (789, 75, 99)))
    val number = person.phone.number._3
    person.copy(
      phone = person.phone.copy(
        number = person.phone.number.copy(
          _3 = 88
        )
      )
    )

    val newPerson = PersonToPhoneLens.set(Phone(1, (1, 2, 3)))(person)

/*
    val prodPhoneLens: ProdLens[D_Person, Phone] =
      ProdLens.ofS et[D_Person, Phone](
        _.phone,
        phone => _.copy(phone = phone),
        "PersonToPhone"
      )
    val prodCodeLens: ProdLens[Phone, Int] =
      ProdLens.of Set[Phone, Int](
        _.code,
        code => _.copy(code = code),
        "PhoneToCode"
      )
    val prodPersonCodeLens: ProdLens[D_Person, Int] =
      prodPhoneLens.to(prodCodeLens)

*/

    println();
  }
}

case class Phone(code: Int, number: (Int, Int, Int))

case class D_Person(name: String, phone: Phone)

trait PhoneGetter extends Getter[D_Person, Phone] {
  def of: D_Person => Phone = _.phone
}

case object CodeGetter extends Getter[Phone, Int] {
  def of: Phone => Int = _.code
}

case class GetterComposer[A, B, C](getterA: Getter[A, B], getterB: Getter[B, C]) extends Getter[A, C] {
  def of: A => C = model => getterB.of(getterA.of(model))
}

case object PersonToPhoneLens extends Lens[D_Person, Phone]
  with PhoneGetter {
  def modify: (Phone => Phone) => D_Person => D_Person =
    f => model => set(f(of(model)))(model)

  def set: Phone => D_Person => D_Person =
    newPhone => _.copy(phone = newPhone)
}



