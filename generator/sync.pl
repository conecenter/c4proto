
use strict;
use Time::HiRes;

sub sy{ print join(" ",@_),"\n"; system @_ and die $?; }

my $put_text = sub{
    my($fn,$content)=@_;
    open FF,">:encoding(UTF-8)",$fn and print FF $content and close FF or die "put_text($!)($fn)";
};

my $to_rel = sub{
    my($pre,$fn)=@_;
    $pre eq substr $fn, 0, length $pre or die;
    substr $fn, length $pre;
};

my $find = sub{
    my($pre,$from,$prune)=@_;
    my $cmd = "${pre}find $from";
    my $prune_str = join'', map{" -o -name '$_' -prune"} @$prune;
    my %prune_h = map{($_=>1)} @$prune;
    map{ &$to_rel($from,$_)=~m{(.+/|)([^/]+)\n} && !$prune_h{$2} ? "$1$2" : () }
        sort `$cmd -type f$prune_str`;
};

my $sync = sub{
    my ($list_fn,$ssh,$from,$from_fns,$to,$to_fns) = @_;
    my %keep = map{($_=>1)} @$from_fns;
    my @to_rm = grep{!$keep{$_}} @$to_fns;
    &$put_text($list_fn, join "", map{"$_\n"} @$from_fns);
    &$put_text("$list_fn.rm",join " ", "true", map{$_%10 ? $to_rm[$_] : ("\\\n && rm",$to_rm[$_])} 0..(@to_rm-1));
    my $tm = Time::HiRes::time();
    my $ssh_opt = $ssh ? "-e '$ssh'" : "";

    sy("cat $list_fn");
    sy("ls -la $from");
    sy("ls -la $to");
    system "rsync $ssh_opt -av --files-from=$list_fn $from/ $to" if @$from_fns;
    print "AAA\n";

    sy("rsync $ssh_opt -av --files-from=$list_fn $from/ $to") if @$from_fns;
    print Time::HiRes::time()-$tm," for rsync\n";
};

my $filter = sub{grep{m{\bc4gen\b}}@_};
my $prune = [qw(target .git .idea .bloop)];

my $port = $ENV{C4BUILD_PORT}-0;
my $clean = $ENV{C4BUILD_CLEAN}-0;
my $cmd = $ENV{C4BUILD_CMD};
my $compile = $ENV{C4BUILD_COMPILE_CMD};
my $dir = $ARGV[0] || die;
my $remote_dir = $ARGV[1] || die;
$port || $dir ne $remote_dir || die "from and to are the same $dir";

my $ssh = $port ? "ssh -p$port" : "";
my $remote = $port ? "c4\@127.0.0.1:$remote_dir" : $remote_dir;
my $remote_pre = $ssh ? "$ssh c4\@127.0.0.1 " : ""; # $remote_pre '. /c4p_alias.sh && '
my $list_fn = "$dir/target/c4sync";
my $remote_pre_q = $remote_pre ? $remote_pre : "sh -c";
my $init_env = $remote_pre ? ". /c4p_alias.sh &&" : "";

if($clean){
    print "= clean =\n";
    for(grep{m"\bc4gen\b|/target/|/tmp/|/node_modules/|/.bloop/"} map{"/$_"} &$find("","$dir/",[])){
        my $path = "$dir$_";
        print "deleting $path\n";
        unlink $path or die;
    }
    sy("$remote_pre_q 'mkdir -p $remote_dir && rm -r $remote_dir && mkdir $remote_dir'");
} else {
    sy("$remote_pre_q 'mkdir -p $remote_dir'");
}

mkdir "$dir/target" or die "$! -- $dir/target";
my @local_fns = &$find("","$dir/",$prune);
&$sync($list_fn,$ssh,
    $dir,[@local_fns],
    $remote,[&$find($remote_pre,"$remote_dir/",$prune)]
);
sy("$remote_pre_q 'cd $remote_dir && sh' < $list_fn.rm");
if($cmd){
    sy("$remote_pre_q '$init_env cd $remote_dir && $cmd'");
    &$sync($list_fn,$ssh,
        $remote,[&$filter(&$find($remote_pre,"$remote_dir/",$prune))],
        $dir,[&$filter(@local_fns)]
    );
    sy("cd $dir && sh < $list_fn.rm");
}
if($compile){
    sy("$remote_pre_q '$init_env cd $remote_dir && $compile'");
}
