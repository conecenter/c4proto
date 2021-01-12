#!/usr/bin/perl
use strict;

sub syl{ for(@_){ print "$_\n"; my @r = `$_`; $? && die $?; return @r } }

print syl("ssh-add");
my @dirs = map{ /^(.+).git\s*$/ ? "$1" : die } syl("find -iname .git");
for my $dir(@dirs){
    print "####################################### $dir\n";
    my $git = "cd $dir && git ";
    for(syl("$git remote -v")){
        my($nm,$ln) = m{^(origin)\s+https://github\.com/(\w+/\w+\.git)\s+\(push\)\s*$} ? ($1,$2) : next;
        print "COPY/RUN:    ($git remote set-url --push $nm git\@github.com:$ln)\n";
    }
    print syl("$git status");
    print `$git push origin --all --dry-run`;
}

#### CALL: ssh-agent c4proto/repos.pl