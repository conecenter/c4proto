use strict;
use List::Util qw[min max];

my @res;
for(<STDIN>){
  m{\bms\s+\[(\d+)\d{6}/(\d+)\d{6}/(\d+)\d{6}/(\d+)\d{6}\]} or next;
  push @res, { tm=>[$1,$2,$3,$4], hint=>$_ };
}
my $from = min(map{@{$$_{tm}}} @res);
my $to = max(map{@{$$_{tm}}} @res);
my $ppu = 2000/($to-$from);
my $draw = sub{
  my($n,$l_from,$l_to,$color,$hint)=@_;
  my $left = int(($l_from-$from)*$ppu);
  my $w = int(($l_to-$l_from)*$ppu);
  my $top = $n * 3;
  qq[<div class="stat" style="width:${w}px;left:${left}px;top:${top}px;background-color:$color"><div>$hint</div></div>]
};

my @pres = map{
  my $e = $res[$_];
  (
  &$draw($_,@{$res[$_]{tm}}[0,1],"red",$res[$_]{hint}),
  &$draw($_,@{$res[$_]{tm}}[1,2],"green",$res[$_]{hint}),
  &$draw($_,@{$res[$_]{tm}}[2,3],"blue",$res[$_]{hint}),
  )
} 0..$#res;
print "<!DOCTYPE html><style>
div.stat {
  position:absolute;
  height:2px;
}
div.stat div {
  position: fixed;
  display: none;
  top: 0px;
  left: 0px;
}

div.stat:hover div {
  display: block;
}
</style>".join("\n",@pres);
