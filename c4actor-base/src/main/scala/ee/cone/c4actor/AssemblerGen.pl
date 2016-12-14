
use strict;
use warnings FATAL => 'all';

open FF, '>', "AssemblerGen.scala" or die;
print FF "
package ee.cone.c4actor
import Types._
";
for my $n(1..3) {
    my $jm = sub { my ($m,$c) = @_; my $r = join ', ', map{&$m()} 1..$n; $r ? \"$r$c" : \$r };
    print FF qq^
abstract class Join$n\[${&$jm(sub{"T$_"},', ')}R,TK,RK](
  ${&$jm(sub{"t$_: WorldKey[Index[TK,T$_]]"},', ')}
  val outputWorldKey: WorldKey[Index[RK,R]]
) extends Join[R,TK,RK] {
  def join(${&$jm(sub{"a$_: Values[T$_]"},'')}): Values[(RK,R)]
  //
  def joins(in: Seq[Values[Object]]): Iterable[(RK,R)] = in match {
    case Seq(${&$jm(sub{"a$_"},'')}) ⇒
      join(${&$jm(sub{"a$_.asInstanceOf[Values[T$_]]"},'')})
  }
  def inputWorldKeys: Seq[WorldKey[Index[TK,Object]]] =
    Seq(${&$jm(sub{"t$_"},'')}).asInstanceOf[Seq[WorldKey[Index[TK,Object]]]]
}
^
}
close FF or die;
1;