use strict;

my $m = 9;

my $get_method = sub{
    my($n,$t)=@_;
    my $st = $t=~/(\w+)$/ ? $1 : die;
qq^
  public interface Handler$n {
    public void handle${st}s(^.join(", ",map{"$t a$_"}0..$_-1).q^);
  }
  public static void foreach(^.join("",map{"scala.collection.Iterable<$t> a$_,"}0..$n-1).qq^Handler$n handler){
    ^.join("",map{"for (scala.collection.Iterable<$t> i$_ = a$_; i$_.nonEmpty(); i$_ = i$_.tail()){  "}0..$n-1).qq^
      handler.handle${st}s(^.join(", ",map{"i$_.head()"}0..$n-1).q^);
    ^.join("",map{"}"}0..$n-1).qq^
  }
^
};

my $put = sub{
    my($path,$content)=@_;
    open FF, ">",$path and print FF $content and close FF or die $!;
};

my $put_for_class = sub{
    my($cl,$t)=@_;
    my $content = qq^package ee.cone.c4assemble;\npublic class $cl {^.
        join("",map{ &$get_method($_,$t) }1..9).
    q^}^;
    &$put("$cl.java",$content);
};

&$put_for_class(ProdMultiFor=>"scala.Product");
&$put_for_class(PartMultiFor=>"MultiForPart");
&$put("MultiForPart.java",qq^package ee.cone.c4assemble;
public interface MultiForPart {
  boolean isChanged();
  scala.collection.immutable.List<scala.Product> items();
}
^);