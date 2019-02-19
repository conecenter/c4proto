#!/usr/bin/perl

use strict;

my $http_port = 8067;
my $sse_port = 8068;
my $temp = "target";
my $docker_build = "$temp/docker_build";

################################################################################

sub so{ print join(" ",@_),"\n"; system @_ }
sub sy{ &so and die $? }

sub lazy(&){ my($calc)=@_; my $res; sub{ ($res||=[scalar &$calc()])->[0] } }

my $need_path; $need_path = sub{
    my($path)=@_;
    my $parent = $path=~m{(.*)/[^/]*$} ? $1 : die "path($path)";
    if(!-d $parent){
        &$need_path($parent);
        mkdir $parent or die "mkdir($parent)";
    }
    $path;
};

my $sy_in_dir = sub{ my($d,$c)=@_; &$need_path("$d/any"); sy("cd $d && $c") };

my $pwd = lazy{ my $c = `pwd`; chomp $c; $c };
my $abs_path = sub{ join '/', &$pwd(), @_ };

my $get_generator_path = sub{ &$abs_path("$temp/c4gen") };
my $get_generated_sbt_dir = sub{ &$get_generator_path()."/res" };

my @tasks;

############################### image builds ###################################

my $put_text = sub{
    my($fn,$content)=@_;
    open FF,">:encoding(UTF-8)",$fn and print FF $content and close FF or die "put_text($!)($fn)";
};

my $recycling = sub{
    !-e $_ or rename $_, &$need_path("$temp/recycle/".rand()) or die $! for @_;
};

my $prepare_build = sub{
    my($name,$f) = @_;
    my $ctx_dir = "$docker_build/$name";
    &$need_path("$ctx_dir/any");
    &$put_text("$ctx_dir/.dockerignore",".dockerignore\nDockerfile");
    &$f($ctx_dir);
};


my $gen_docker_conf = sub{
    &$recycling($docker_build);

    &$prepare_build("synced"=>sub{
        my($ctx_dir)=@_;
        sy("cp zoo/* $ctx_dir/");
        my $gen_dir = &$get_generated_sbt_dir();
        sy("cp -r $gen_dir/c4gate-server/target/universal/stage $ctx_dir/app");
        &$put_text("$ctx_dir/gate.sh", join "\n",
            "export C4HTTP_PORT=$http_port",
            "export C4SSE_PORT=$sse_port",
            "exec app/bin/c4gate-server",
        );
    });

    &$prepare_build("haproxy"=>sub{
        my($ctx_dir)=@_;
        &$put_text("$ctx_dir/haproxy.cfg",qq{
            defaults
              timeout connect 5s
              timeout client  900s
              timeout server  900s
            resolvers docker_resolver
              nameserver dns "127.0.0.11:53"
            frontend fe80
              mode http
              bind :80
              acl acl_sse hdr(accept) -i text/event-stream
              use_backend be_sse if acl_sse
              default_backend be_http
            listen listen_443
              mode http
              bind :443 ssl crt /c4deploy/dummy.pem
              server s_http :80
            backend be_http
              mode http
              server se_http gate:$http_port check resolvers docker_resolver resolve-prefer ipv4
            backend be_sse
              mode http
              server se_sse gate:$sse_port check resolvers docker_resolver resolve-prefer ipv4
        });
        &$put_text("$ctx_dir/Dockerfile", join "\n",
            "FROM haproxy:1.7",
            "COPY haproxy.cfg /usr/local/etc/haproxy/haproxy.cfg",
        );
    });
};
###

my $git_need_repo = sub{
    my($dir)=@_;
    my $agit = ['git', "--git-dir=$dir/.git", "--work-tree=$dir"];
    -e &$need_path("$dir/.git") or sy(@$agit, "init");
    $agit;
};
my $git_add_commit = sub{
    my($agit)=@_;
    sy(@$agit, "add", "--all", ":/");
    sy(@$agit, "commit", "-m-");
};
my $git_status = sub{
    my($agit)=@_;
    my $st = join ' ', @$agit, 'status', '--porcelain', ":/";
    @{[`$st`]};
};
my $update_file_tree = sub{
    my($gen_dir,$sbt_dir)=@_;
    my $gen_git = &$git_need_repo($gen_dir);
    my $sbt_git = &$git_need_repo($sbt_dir);
    &$git_add_commit($gen_git) if &$git_status($gen_git,'');
    #run(@$sbt_git, "checkout", ":/src") if git_status($sbt_git,"src"); #checkout failed to delete files
    sy(@$sbt_git, "reset", "--hard");
    sy(@$sbt_git, "pull", $gen_dir, "master:master"); #reset --hard failed to delete files
};



my $run_generator = sub{
    my $generator_path = &$get_generator_path();
    &$recycling($_) for <$generator_path/to/*>; # .git not included
    my $generator_src_dir = &$abs_path("generator");
    my $generator_exec = "$generator_src_dir/target/universal/stage/bin/generator";
    &$sy_in_dir($generator_src_dir,"sbt stage") if !-e $generator_exec;
    print "generation starting\n";
    sy("C4GENERATOR_PATH=$generator_path $generator_exec");
    print "generation finished\n";
};

my $run_generator_outer = sub{
    my $generator_path = &$get_generator_path();
    &$recycling("$generator_path/from");
    my $src_dir = &$abs_path();
    for my $path ((grep{!-d} <$src_dir/project/*>), "$src_dir/build.sbt", (grep{-e} map{"$_/src"} <$src_dir/c4*>)){
        my $rel_path = substr $path, length $src_dir;
        symlink $path,&$need_path("$generator_path/from$rel_path") or die $!;
    }
    &$run_generator();
    &$update_file_tree("$generator_path/to",&$get_generated_sbt_dir());
};

push @tasks, ["### build ###"];
push @tasks, ["build_all", sub{
    &$sy_in_dir(&$abs_path(),"sbt clean");
    &$sy_in_dir(&$abs_path("generator"),"sbt clean");
    &$run_generator_outer();
    &$sy_in_dir(&$get_generated_sbt_dir(),"sbt stage");
    &$gen_docker_conf();
}];
push @tasks, ["build_some_server", sub{
    &$run_generator_outer();
    &$sy_in_dir(&$get_generated_sbt_dir(),"sbt stage");
    &$gen_docker_conf();
}];
push @tasks, ["run_generator", sub{
    &$run_generator_outer();
}];
push @tasks, ["build_conf_only", sub{
    &$gen_docker_conf([]);
}];
push @tasks, ["sbt", sub{
    chdir &$get_generated_sbt_dir() or die $!;
    sy("sbt",@ARGV[1..$#ARGV]);
}];


################################################################################

if($ARGV[0]) {
    my($cmd,@args)=@ARGV;
    $cmd eq $$_[0] and $$_[1]->(@args) for @tasks;
} else {
    print join '', map{"$_\n"} "usage:",
        map{$$_[0]=~/^\./ ? () : $$_[0]=~"^#" ? $$_[0] : "  $0 $$_[0]"} @tasks;
}
