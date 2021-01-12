#!/usr/bin/perl
use strict;

sub syl{ for(@_){ print "$_\n"; my @r = `$_`; $? && die $?; return @r } }

for(syl("find -iname .git")){
    my $dir = /^(.+).git\s*$/ ? $1 : die;
    for(syl("cd $dir && git remote -v")){
        my($nm,$ln) = m{^(origin)\s+https://github\.com/(\w+/\w+\.git)\s+\(push\)\s*$} ? ($1,$2) : next;
        print "COPY/RUN:    (cd $dir && git remote set-url --push $nm git\@github.com:$ln)\n";
    }
}