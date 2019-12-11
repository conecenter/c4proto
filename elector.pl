
use strict;
use JSON::XS;

sub syf{ for(@_){ print "$_\n"; my $r = scalar `$_`; $? && die $?; return $r } }
my $exec = sub{ print join(" ",@_),"\n"; exec @_; die 'exec failed' };
my $single_or_undef = sub{ @_==1 ? $_[0] : undef };
my $group = sub{ my %r; push @{$r{$$_[0]}||=[]}, $$_[1] for @_; (sub{@{$r{$_[0]}||[]}},[sort keys %r]) };
my $json = sub{ JSON::XS->new->ascii(1)->canonical(1)->pretty(1) };
my $forever = sub{ my($f,@state)=@_; @state = &$f(@state) while 1; };

my @tasks;
my $get_handler = sub{ for my $v(@_){$v eq $$_[0] and return $$_[1] for @tasks} die };

###

push @tasks, [kubectl=>sub{
    &$exec('kubectl', 'proxy', '--port=8080', '--disable-filter=true');
}];

push @tasks, [elector=>sub{
    my $ns = `cat /var/run/secrets/kubernetes.io/serviceaccount/namespace`=~/(\w+)/ ? "$1" : die;
    &$forever(sub{
        my ($was_container_ids_by_app) = @_;
        my $pods_data = syf("kubectl -n $ns get pods -o json");
        #
        my ($pods_by_app,$apps) = &$group(map{[$$_{app},$_]} map{ my $pod = $_;
            my $container_status = &$single_or_undef(@{$$pod{status}{containerStatuses}||die});
            my $container = &$single_or_undef(@{$$pod{spec}{containers}||die});
            my $rolling = $container && &$single_or_undef(map{$$_{name} eq "C4ROLLING"?$$_{value}:()} @{$$container{env}||[]});
            my $container_name = $container && $$container{name};
            my $pod_name = $$pod{metadata}{name} || die;
            my $exec = $container_name && "kubectl -n $ns exec $pod_name -c $container_name -- ";
            my $exec_get = $exec && $rolling && "$exec ls $rolling | grep c4is-master";
            my $exec_set = $exec && $rolling && "$exec sh -c '>$rolling/c4is-master'";
            $rolling  ? {
                app => ($$pod{metadata}{labels}{app} || die),
                container_id => ($container_status && $$container_status{containerID} || die),
                exec_get => ($exec_get || die), exec_set => ($exec_set || die),
            } : ()
        } @{$json->decode($pods_data)->{items}||die});
        my $container_ids_by_app = {map{
            ($_ => join " ", sort map{$$_{container_id}} &$pods_by_app($_))
        }@$apps};
        my @invalidate_apps = grep{ $$container_ids_by_app{$_} ne $$was_container_ids_by_app{$_} } @$apps;
        #
        for my $app(@invalidate_apps){
            my @pods = &$pods_by_app($app);
            grep{syf($$_{exec_get})=~/\S/} @pods or syf($pods[0]{exec_set}||die);
        }
        sleep 1;
        ($container_ids_by_app);
    },+{});

}];

###

my($cmd,@args)=@ARGV;
&$get_handler($cmd,'def')->(@args);