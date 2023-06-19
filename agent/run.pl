
use strict;

sub sy{ print join(" ",@_),"\n"; system @_ and die $?; }
my $get_text = sub{
    my($path)=@_;
    open FF,"<:encoding(UTF-8)",$path or die "get_text: $path";
    my $res = join"",<FF>;
    close FF or die;
    $res;
};

my $proto_dir = $ENV{C4CI_PROTO_DIR} || die;
sy("perl","$proto_dir/sync_setup.pl");
sy("python3", "-u", "$proto_dir/build_remote.py", "gen_prep", "--context", ($ARGV[0]||die), "--out", "$ENV{HOME}/bin"))
my $dir = "$proto_dir/agent";
sy("cd $dir && sbt c4build");
exec "java","-cp",&$get_text("$dir/target/c4classpath"),"Main",@ARGV;die;
