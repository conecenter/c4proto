
use strict;

sub syf{ for(@_){ print "$_\n"; my $r = scalar `$_`; $? && die $?; return $r } }
my $put_text = sub{
    my($fn,$content)=@_;
    open FF,">:encoding(UTF-8)",$fn and print FF $content and close FF or die "put_text($!)($fn)";
};

my($dir)=@ARGV;
my $content = join " ", sort map{
    my $commit = syf("cd $dir && git --git-dir=$_ rev-parse --short HEAD")=~/(\S+)/ ? $1 : die;
    my $l_dir = m{^\./(|.*/)\.git$} ? $1 : die;
    "$l_dir:$commit";
} syf("cd $dir && find -name .git")=~/(\S+)/g;
&$put_text("$dir/target/c4repo_commits",$content);
