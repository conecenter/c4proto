
use strict;

sub sy{ print join(" ",@_),"\n"; system @_ and die $?; }
my $put_text = sub{
    my($fn,$content)=@_;
    open FF,">:encoding(UTF-8)",$fn and print FF $content and close FF or die "put_text($!)($fn)";
};
my $get_text = sub{
    my($path)=@_;
    open FF,"<:encoding(UTF-8)",$path or die "get_text: $path";
    my $res = join"",<FF>;
    close FF or die;
    $res;
};
my $put_bin = sub{
    my($nm,$content)=@_;
    my $bin_path = "$ENV{HOME}/bin";
    my $path = "$bin_path/$nm";
    return if -e $path and &$get_text($path) eq $content;
    sy("mkdir -p $bin_path");
    &$put_text($path,$content);
    sy("chmod +x $path");
};
my $perl_exec = sub{ join"\n",'#!/usr/bin/perl','use strict;',@_,'die;' };

do{ # agent and de
    &$put_bin("kc",&$perl_exec('exec "kubectl", "--context", @ARGV;')); # manual only
    my $kcd = 'exec "kubectl", "--context", ($ENV{C4DEPLOY_CONTEXT}||die), ';
    &$put_bin("kcd",&$perl_exec($kcd.'@ARGV;')); # manual and code
    &$put_bin("c4rsh_raw",&$perl_exec('my ($pod,@args) = @ARGV; '.$kcd.'"exec", "-i", $pod, "--", @args;')); # code
    &$put_bin("c4dsync",&$perl_exec('exec "rsync","--blocking-io","-e","c4rsh_raw",@ARGV;')); # code
    &$put_bin("c4rsh",&$perl_exec($kcd.'"exec", "-it", (scalar(`cat /tmp/c4pod`)||die "no pod"), "--", "bash";')); # manual only
    &$put_bin("c4p",&$perl_exec('my ($op,@args) = @ARGV; exec "python3","-u","$ENV{C4CI_PROTO_DIR}/$op.py",@args;'));
};