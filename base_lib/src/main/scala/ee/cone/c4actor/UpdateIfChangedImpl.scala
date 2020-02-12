package ee.cone.c4actor

import ee.cone.c4assemble.ToPrimaryKey
import ee.cone.c4di.c4

@c4("RichDataCompApp") class UpdateIfChangedImpl extends UpdateIfChanged {
    def updateSimple[T<:Product](getByPK: GetByPK[T]): Context=>Seq[T]=>Seq[LEvent[Product]] = local => {
        val was = getByPK.ofA(local)
        origSeq => LEvent.update(origSeq.filterNot(o=>was.get(ToPrimaryKey(o)).contains(o)))
    }
}
