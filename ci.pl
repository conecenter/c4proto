#!/usr/bin/perl
use strict;
use Digest::MD5 qw(md5_hex);

sub sy{ print join(" ",@_),"\n"; system @_ and die $?; }
my $exec = sub{ print join(" ",@_),"\n"; exec @_; die 'exec failed' };
my $env = sub{ $ENV{$_[0]} || die "no $_[0]" };
my $put_text = sub{
    my($fn,$content)=@_;
    open FF,">:encoding(UTF-8)",$fn and print FF $content and close FF or die "put_text($!)($fn)";
};
my @tasks;

###

my $handle_build = sub{
    my ($arg) = @_;
    my($full_img,$img,$tag,$base,$mode,$checkout) =
        $arg=~/^build\s+(([\w\-\.\:\/]+)\:(([\w\-\.]+)\.(\w+)\.([\w\-]+)))\s*$/ ?
        ($1,$2,$3,$4,$5,$6) : die "can not [$arg]";
    #we can implement fork after checkout later and unshare ctx_dir
    my $builder = md5_hex($full_img)."-".time;
    my $host = &$env("C4CI_HOST");
    my $ctx_dir = &$env("C4CI_CTX_DIR");
    my %repo_dirs = &$env("C4CI_REPO_DIRS")=~/(\S+)/g;
    my $repo_dir = $repo_dirs{$img} || die "no repo for $img";
    my $args = " --build-arg C4CI_BASE_TAG=$base";
    my @commands = (
        "set -x",
        "(test -e $ctx_dir && rm -r $ctx_dir; true)",
        "mkdir $ctx_dir",
        "cd $repo_dir && git fetch && git fetch --tags",
        "git --git-dir=$repo_dir/.git --work-tree=$ctx_dir checkout $checkout -- .",
        "docker build -t builder:$tag -f $ctx_dir/build.$mode.dockerfile $args $ctx_dir",
        "rm -r $ctx_dir",
        "docker create --name $builder builder:$tag",
        "docker cp $builder:/c4/res $ctx_dir",
        "docker rm -f $builder",
        "docker build -t $full_img $ctx_dir",
        $img=~m{/} ? "docker push $full_img" : (),
    );
    &$put_text("/tmp/build.sh", join " && ",@commands);
    sy("ssh c4\@$host sh < /tmp/build.sh");
};

push @tasks, [ci=>sub{
    my $tgz = &$env("C4CI_KEY_TGZ");
    my $dir = "/c4/.ssh";
    sy("mkdir -p $dir && cd $dir && chmod 0700 . && tar -xzf $tgz");
    my $port = &$env("C4CI_PORT");
    &$exec('socat', "tcp-l:$port,reuseaddr,fork", "exec:perl /ci.pl ci_handle");
}];
push @tasks, [ci_handle=>sub{
    my $arg = <STDIN>;
    &$handle_build($arg);
}];
push @tasks, [frpc=>sub{
    &$exec("/tools/frp/frpc", "-c", &$env("C4FRPC_INI"));
}];
push @tasks, [ci_arg=>sub{
    my($arg)=@_;
    &$handle_build($arg);
}];

###

my($cmd,@args)=@ARGV;
($cmd||'def') eq $$_[0] and $$_[1]->(@args) for @tasks;
