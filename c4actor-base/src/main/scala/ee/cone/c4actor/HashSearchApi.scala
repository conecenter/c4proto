package ee.cone.c4actor

import ee.cone.c4actor.Types.SrcId
import ee.cone.c4assemble.Assembled

trait HashSearchFactory {
  def index[Model<:Product](model: Class[Model]): HashSearch.IndexBuilder[Model]
  def request[Model<:Product](condition: Condition[Model]): HashSearch.Request[Model]
}

object HashSearch {
  case class Request[Model<:Product](requestId: SrcId, condition: Condition[Model])
  case class Response[Model<:Product](request: Request[Model], lines: List[Model])
  trait IndexBuilder[Model<:Product] {
    def add[By<:Product,Field](lens: ProdLens[Model,Field], by: By)(
      implicit ranger: Ranger[By,Field]
    ): IndexBuilder[Model]
    def assemble: Assembled
  }
}
