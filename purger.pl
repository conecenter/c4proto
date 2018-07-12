
use strict;

use List::Util qw(min max);

sub zipWithIndex{
  my $i = 0;
  map{[$_,$i++]} @_;
}

my $fresh_days = 4;
my $fresh_count = 20;

my $rm = sub{
  for(@_){
    print "removing $_\n";
    unlink $_ or die $!;
  }
};

print "looking for snapshots\n";
my @snapshots = map{
  my($sn,$i)=@$_;
  $i < $fresh_count || $$sn{age} < $fresh_days ? $sn : {%$sn,rm=>1}
} zipWithIndex(map{
  m(/([0-9a-f]{16})-\w{8}-\w{4}-\w{4}-\w{4}-\w{12}$) ?
    { path=>$_, offset=>hex($1), age=>(-M) } : ()
} reverse sort </c4/db4/snapshots/*>);
&$rm(map{$$_{path}} grep{$$_{rm}} @snapshots);
print "snapshots purged\n";

my $min_snapshot_offset = min(map{$$_{offset}} grep{!$$_{rm}} @snapshots);
if(defined $min_snapshot_offset){
  print "min_snapshot_offset $min_snapshot_offset\n";
  my @logs = map{
    m(/(\d{20})\.(index|log|timeindex)$) ? { path=>$_, offset=>$1-0 } : ()
  } reverse sort </c4/db4/kafka-logs/.inbox-0/*>;
  my $min_log_offset = max(grep{$_<=$min_snapshot_offset} map{$$_{offset}} @logs);
  print "min_log_offset $min_log_offset\n";
  &$rm(map{$$_{path}} grep{$$_{offset}<$min_log_offset} @logs);
}

print "...\n";

sleep 3600;