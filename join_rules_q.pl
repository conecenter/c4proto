use strict;

my @rules = grep{@$_}map{
  [map{[/JK\((.+?)\)/g]} /^(.+)==>(.+)$/ ? ($1,$2) : ()];
} `cat /tmp/c4rules.out`;

my ($q_in,$q_out) = @ARGV;

if(!$q_out){
  my %dep = ($q_in=>[]);
  for(@rules){
    my ($ins,$outs)=@$_;
    for my $in(@$ins){
      push @{$dep{$in}||next}, @$outs;
      $dep{$_}||=[] for @$outs;
    }
  }
  print map{"$_\n"} sort keys %dep;
} else {
  my %dep;
  for(@rules){
    my ($ins,$outs)=@$_;
    for my $in(@$ins){
      push @{$dep{$in}||=[]}, @$outs;
    }
  }

  my $f; $f = sub{
    $_[0] eq $q_out and return ($_[0]);
    for(@{$dep{$_[0]}||return}){
      my @r=&$f($_);
      @r and return ($_[0],@r);
    }
  };
  print map{my $n=scalar @{$dep{$_}||[]};"$_ $n\n"} &$f($q_in);
}