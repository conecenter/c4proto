package ee.cone.c4actor

import ee.cone.c4actor.HashSearch.Request
import ee.cone.c4assemble.Assemble

object StaticHashSearchApi {

  trait StaticFactory {
    def index[Model <: Product](model: Class[Model]): StaticIndexBuilder[Model]

    def request[Model <: Product](condition: Condition[Model]): Request[Model]
  }

  trait StaticIndexBuilder[Model <: Product] {
    def add[By <: Product, Field](lens: ProdLens[Model, Field], by: By)(
      implicit ranger: Ranger[By, Field]
    ): StaticIndexBuilder[Model]

    def assemble: List[Assemble]
  }

}
