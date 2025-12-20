
use strict;
use JSON::XS;

my $encode = sub{ JSON::XS->new->canonical(1)->encode(@_) };

my $main = sub{ # the last multi container kc
    my($ns)=@_;
    $ns || die "need ns";
    my $kubectl = "kubectl -n $ns";
    my $run_comp = "deployer";
    my $add_yml = join "\n", map{&$encode($_)} ({
        apiVersion => "rbac.authorization.k8s.io/v1",
        kind => "Role",
        metadata => { name => $run_comp },
        rules => [
            {
                apiGroups => ["","apps","extensions","metrics.k8s.io","networking.k8s.io","kafka.strimzi.io"],
                resources => [
                    "statefulsets","secrets","services","deployments","ingresses","pods","replicasets","kafkatopics",
                    "deployments/scale", "pods/resize",
                ],
                verbs => ["get","create","patch","delete","update","list","watch"],
            },
            {
                apiGroups => [""],
                resources => ["pods/exec","pods/portforward"],
                verbs => ["create"],
            },
            {
                apiGroups => [""],
                resources => ["pods/log"],
                verbs => ["get"],
            },
#            {
#                apiGroups => [""],
#                resources => ["nodes"],
#                verbs => ["list","watch"],
#            }
        ],
    }, {
        apiVersion => "v1",
        kind => "ServiceAccount",
        metadata => { name => $run_comp },
    }, {
        apiVersion => "rbac.authorization.k8s.io/v1",
        kind => "RoleBinding",
        metadata => { name => $run_comp },
        subjects => [{ kind => "ServiceAccount", name => $run_comp }],
        roleRef => { kind => "Role", name => $run_comp, apiGroup => "rbac.authorization.k8s.io" },
    });
    my $get_secret = qq[$kubectl get secret -o jsonpath='{.data.token}' \$($kubectl get serviceaccount $run_comp -o jsonpath='{.secrets[].name}') | base64 -d];
    print "######## COPY:\ncat <<EOF | $kubectl apply -f- \n$add_yml\nEOF\necho SECRET: && $get_secret && echo\n######## END_COPY\n";
};

&$main(@ARGV);
