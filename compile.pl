
use strict;
sub sy{ print join(" ",@_),"\n"; system @_ and die $?; }
#open FF,'|-','sbt',"$mod/compile"; close FF or die; ### perl during 'system' will not fail on ^C; so we use 'open'

my ($mod) = @ARGV;
my $src_dir = $ENV{PWD} || die;
my $proto_dir = $ENV{C4CI_PROTO_DIR} || die;
sy(qq^cd $src_dir/target/c4/mod.$mod.d && sbt -Dsbt.color=true -J-Xmx16G c4build^); # C4BUILD_JAVA_TOOL_OPTIONS was here
sy("perl", "$proto_dir/build_env.pl", $src_dir, $mod);
