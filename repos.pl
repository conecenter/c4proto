#!/usr/bin/perl
use strict;

sub syl{ for(@_){ print "$_\n"; my @r = `$_`; $? && die $?; return @r } }

print syl("ssh-add");
my @dirs = map{ /^(.+).git\s*$/ ? "$1" : die } syl("find -iname .git");
for my $dir(@dirs){
    print "####################################### $dir\n";
    my $git = "cd $dir && git ";
    print syl("$git status");
    my @set_msg = map{
        m{^(origin)\s+https://github\.com/(\w+/\w+\.git)\s+\(push\)\s*$} ? do{
            my($nm,$ln) = ($1,$2);
            "COPY/RUN:    ($git remote set-url --push $nm git\@github.com:$ln)\n";
        } : ();
    } syl("$git remote -v");
    if(@set_msg){ print @set_msg } else {print `$git push origin --all --dry-run`}
}

#### CALL: ssh-agent c4proto/repos.pl