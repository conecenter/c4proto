use strict;

sub sy{ print join(" ",@_),"\n"; system @_ and die $?; }
my $put_text = sub{
    my($fn,$content)=@_;
    open FF,">:encoding(UTF-8)",$fn and print FF $content and close FF or die "put_text($!)($fn)";
};
my $dir = "/c4/wrap";
my $path = "$dir/bloop";
mkdir $dir;
&$put_text($path,q%#!/usr/bin/perl
for(<.bloop/*>){
    my ($pre,$nm) = m{^(.+)/([^/]+)\.json$} ? ($1,$2) : next;
    my $a_dir = "$pre/c4/mod.$nm.classes";
    my $b_dir = "mod.$nm.out/bloop-bsp-clients-classes/mod.$nm.classes-bloop-cli";
    next if $b_dir eq readlink $a_dir;
    unlink $a_dir;
    symlink $b_dir, $a_dir or die $!, $a_dir, "cleanup may help";
}
exec "/c4/.local/share/coursier/bin/bloop",@ARGV; die;
%);
sy("chmod +x $path");