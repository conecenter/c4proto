
use strict;

sub sy{ print join(" ",@_),"\n"; system @_ and die $?; }

my($app) = @ARGV;
sleep 3600 while !$app;
-e $app or sy("mkdir -p $app");
chdir $app or die;
if(!-e ".git"){
    sy("git init");
    sy("git config receive.denyCurrentBranch ignore");
    sy("git config user.email c3deploy@cone.ee");
    sy("git config user.name c3deploy");
    sy("touch .dummy");
    sy("git add .dummy");
    sy("git commit -am-");
}
while(sleep 1){
    my $script = "start.run";
    -e $script or sy("git reset --hard");
    -e $script and exec "sh $script" ;
}