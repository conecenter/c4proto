
use strict;

chdir "app" or die;
if(!-e ".git"){
    sy("git init");
    sy("git config receive.denyCurrentBranch ignore");
}
while(sleep 1){
    my $script = "start.run";
    -e $script or sy("git reset --hard");
    -e $script and exec "sh $script" ;
}