use strict;
use utf8;
for my $path(`find`){
    chomp($path);
    $path=~/\.scala$/||next;
    my $cont = `cat $path`;
    $cont=~/\b(Assemble|Protocol)\b/||next;
    print "patching: $path\n";

    $cont=~s/\bextends(\s+)(c4proto\.)?Protocol(\s*\{)/$1$3/gs;
    $cont=~s/\bextends(\s+)Assemble(\s*\{)/$1$2/gs;
    $cont=~s/(\bextends\s+)Assemble(\s+)with\b/$1$2/gs;

    open FF,'>',$path and print FF $cont and close FF;

#    $cont=~s/: (Assemble|Protocol)\b//g;
#    $cont=~s/\bList\[Protocol\]//g;
#    $cont=~s/\bList\[Assemble\]//g;
#    $cont=~s/\nimport ee\.cone\.c4[^\n]+//g;
#    my @left = grep{/\b(Assemble|Protocol)\b/ } split "\n", $cont;
#
#    if(@left){
#       print "$_\n" for $path,@left;
#    }
}


#find | grep \\.scala\$ | xargs perl -i -pe 's/\bextends\s+Protocol\s*\{/{/; s/\bAssemble\s+with\b//;  s/\bextends\s+Assemble\s+\{/{/'
#perl -e 'for(map{[$_=>scalar `cat $_`]}map{/(.+\.scala)\s*$/?"$1":()}`find`){$$_[1]=~/\bAssemble\s*\n\s*with\b/s and print "$$_[0]\n"}'