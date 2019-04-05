#!/usr/bin/perl
use strict;
use Digest::MD5 qw(md5_hex);

sub sy{ print join(" ",@_),"\n"; system @_ and die $?; }
sub syl{ print "$_\n" and return `$_` for @_ }
my $exec = sub{ print join(" ",@_),"\n"; exec @_; die 'exec failed' };
my $env = sub{ $ENV{$_[0]} || die "no $_[0]" };
my $put_text = sub{
    my($fn,$content)=@_;
    open FF,">:encoding(UTF-8)",$fn and print FF $content and close FF or die "put_text($!)($fn)";
};
my $serve = sub{
    my($port,$handle)=@_;
    use strict;
    use IO::Socket::INET;
    my $socket = new IO::Socket::INET(
        LocalHost => '0.0.0.0',
        LocalPort => $port,
        Proto => 'tcp',
        Listen => 5,
        Reuse => 1
    ) or die $!;
    while(1){
      my $client_socket = $socket->accept();
      my $fileno = $client_socket->fileno;
      open STDIN, "<&$fileno" or die;
      open STDOUT, ">&$fileno" or die;
      open STDERR, ">&$fileno" or die;
      &$handle();
      shutdown($client_socket, 1);
    }
    $socket->close();
};

my $handle = sub{
    my $full_img = <STDIN> || die;
    chomp $full_img;
    my($tag,$base,$mode,$repo_name,$checkout) =
        $full_img=~/^[\w\-\.\:\/]+\:(([\w\-\.]+)\.(\w+)\.([\w\-]+)\.([\w\-]+))$/ ?
        ($1,$2,$3,$4,$5) : die "bad tag: $full_img";
    #we can implement fork after checkout later and unshare ctx_dir
    my $builder = md5_hex($full_img)."-".time;
    my $host = &$env("C4CI_HOST");
    my $ctx_dir = &$env("C4CI_CTX_DIR");
    my $repo_dir = &$env("C4CI_REPO_DIR");
    my $clear_ctx = sub{ ("(rm -r $ctx_dir;true)","mkdir $ctx_dir") };
    my $args = " --build-arg C4CI_FULL_IMG=$full_img --build-arg C4CI_BASE_TAG=$base";
    my @commands = (
        "set -x",
        "(test -e $ctx_dir && rm -r $ctx_dir; true)",
        "mkdir $ctx_dir",
        "cd $repo_dir/$repo_name && git fetch && git fetch --tags",
        "git --git-dir=$repo_dir/$repo_name/.git --work-tree=$ctx_dir checkout $checkout -- .",
        "docker build -t builder:$tag -f $ctx_dir/build.$mode.dockerfile $args $ctx_dir",
        "rm -r $ctx_dir",
        "docker create --name $builder builder:$tag",
        "docker cp $builder:/c4/res $ctx_dir",
        "docker rm -f $builder",
        "docker build -t $full_img $ctx_dir",
        "docker push $full_img",
    );
    &$put_text("/tmp/build.sh", join " && ",@commands);
    sy("ssh -v $host sh < /tmp/build.sh");
};

my @tasks;
push @tasks, [serve=>sub{
    my $tgz = &$env("C4CI_KEY_TGZ");
    my $dir = "/c4/.ssh";
    sy("mkdir -p $dir && cd $dir && chmod 0700 . && tar -xzf $tgz");
    &$serve(&$env("C4CI_PORT"),$handle);
}];
push @tasks, [frpc=>sub{
    &$exec("/tools/frp/frpc", "-c", &$env("C4FRPC_INI"));
}];

my($cmd,@args)=@ARGV;
($cmd||'def') eq $$_[0] and $$_[1]->(@args) for @tasks;
