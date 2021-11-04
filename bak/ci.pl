#!/usr/bin/perl
use strict;
use Digest::MD5 qw(md5_hex);

sub sy{ print join(" ",@_),"\n"; system @_ and die $?; }
sub syl{ for(@_){ print "$_\n"; my @r = `$_`; $? && die $?; return @r } }
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
    my $allow = &$env("C4CI_ALLOW");
    my $ctx_dirs = &$env("C4CI_CTX_DIR");
    my $host = &$env("C4CI_HOST");
    my @ssh = ("ssh","c4\@$host");
    if($arg=~/^allowed\s*$/){
        print "allowed: $allow\n";
        return;
    }
    if($arg=~/^cleanup\s*$/){
        my @to_kill = map{/^c4\s+(\d+).+\bdocker\s+build\b/?"$1":()} syl(join" ",@ssh,"ps","-ef");
        @to_kill and sy(@ssh,"kill",@to_kill);
        sy(@ssh,"test -e $ctx_dirs && rm -r $ctx_dirs; true");
        return;
    }
    my($full_img,$reg,$shrep,$tag,$base,$proj,$mode,$checkout) =
        $arg=~/^build\s+(([\w\-\.\:\/]*?)(\w+)\:((([\w\-]+)[\w\.]*)\.(\w+)\.([\w\-]+)))\s*$/ ?
        ($1,$2,$3,$4,$5,$6,$7,$8) : die "can not [$arg]";
    index(" $allow "," $reg$shrep:$proj ") < 0 and die "prefix not allowed";
    my $builder_reg = index(" $allow "," ${reg}builder:$proj ") < 0 ? "" : $reg;
    #implement checkout lock?
    my $builder = md5_hex($full_img)."-".time;
    my $ctx_dir = (map{/(\S+)/ ? "$ctx_dirs/$1" : ()} syl("uuidgen"))[0] || die;
    my %repo_dirs = &$env("C4CI_SHORT_REPO_DIRS")=~/(\S+)/g;
    my $repo_dir = $repo_dirs{$shrep} || die "no repo for $arg";
    my @args = ("--build-arg","C4CI_BASE_TAG=$base");
    my @commands = (
        ["mkdir","-p",$ctx_dir],
        ["cd $repo_dir && git fetch && git fetch --tags && ".
            "git --git-dir=$repo_dir/.git --work-tree=$ctx_dir checkout $checkout -- ."],
        ["-t","docker","build","-t","builder:$tag","-f","$ctx_dir/build.$mode.dockerfile",@args,$ctx_dir],
        $builder_reg ? (
            ["docker","tag","builder:$tag","${builder_reg}builder:$tag"],
            ["docker","push","${builder_reg}builder:$tag"],
        ) : (),
        ["rm","-r",$ctx_dir],
        ["docker","create","--name",$builder,"builder:$tag"],
        ["docker","cp","$builder:/c4/res",$ctx_dir],
        ["docker","rm","-f",$builder],
        ["-t","docker","build","-t",$full_img,$ctx_dir],
        $reg ? ["docker","push",$full_img] : (),
    );
    sy(@ssh,@$_) for @commands;
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

############### from prod.pl:

#push @tasks, ["up-ci","",sub{
#    my ($comp,$args) = @_;
#    my $gen_dir = &$get_proto_dir();
#    my $img = do{
#        my $from_path = &$get_tmp_dir();
#        my $put = &$rel_put_text($from_path);
#        sy("cp $gen_dir/install.pl $gen_dir/ci.pl $from_path/");
#        &$put("Dockerfile", join "\n",
#            &$base_image_steps(),
#            "RUN perl install.pl apt curl openssh-client socat libdigest-perl-md5-perl uuid-runtime",
#            "RUN perl install.pl curl $dl_frp_url",
#            "COPY ci.pl /",
#            "USER c4",
#            'ENTRYPOINT ["perl","/ci.pl"]',
#        );
#        &$remote_build(''=>$comp,$from_path);
#    };
#    my $conf = &$get_compose($comp);
#    my ($host,$port,$user) = &$get_host_port($comp);
#    #userns_mode => "host" with docker.sock -- bad uid-s
#    my @containers = (
#        {
#            image => $img,
#            name => "ci",
#            C4CI_KEY_TGZ => "/c4conf/ssh.tar.gz",
#            C4CI_PORT => $cicd_port,
#            C4CI_HOST => $host,
#            tty => "true",
#            (map{($_=>$$conf{$_}||die "no $_")} qw[C4CI_SHORT_REPO_DIRS C4CI_ALLOW C4CI_CTX_DIR]),
#        },
#        {
#            image => $img,
#            name => "frpc",
#            C4FRPC_INI => "/c4conf/frpc.ini",
#        },
#    );
#    my $from_path = &$get_tmp_dir();
#    &$put_frpc_conf($from_path,&$get_frpc_conf($comp));
#    &$put_reg_ssh_client_conf($from_path,$host,$port);
#    #sy(&$remote($comp,"mkdir -p $repo_dir"));
#    #sy(&$remote($comp,"test -e $repo_dir/.git || git clone $repo_url $repo_dir"));
#
#    my($yml_str,$up) = &$make_dc_yml($comp,$from_path,\@containers);
#    my $up_content = &$pl_head().&$pl_embed(main=>$yml_str).qq[pp(main=>$up);];
#    &$sync_up($comp,$from_path,$up_content,"");
#}];