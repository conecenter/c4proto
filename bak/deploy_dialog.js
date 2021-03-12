
import { h, render } from 'https://cdn.pika.dev/preact'
import { useState, useEffect } from 'https://cdn.pika.dev/preact/hooks'

function Select({options,value,setValue,gridArea}){
    return h('div', {
        style: {
            padding: "1em",
            display: "inline-block",
            gridArea,
            border: "1px solid silver",
            borderRadius: "0.2em",
        }
    },
        h("b",{},gridArea),
        options.map(option=>h("div",{
            key: option,
            onClick: ev=>setValue(option),
            style: {
                margin: "0.2em",
                padding: "0.2em",
                border: `1px solid ${value===option?"green":"white"}`,
                borderRadius: "0.2em",
                cursor: "hand",
            },
        },option))
    )
}

const useLocalStorageObject = (key) => {
    const [ state, setState ] = useState(()=>JSON.parse(localStorage.getItem(key)||"{}"))
    useEffect(()=>{
        localStorage.setItem(key, JSON.stringify(state))
    },[state])
    return [ state, setState ]
}

const subState = (options,value,setValue,key)=>({
    key,
    value: value[key],
    setValue: v => setValue(st=>({...st,[key]:v})),
    gridArea: key,
    options: options[key],
})

const range = sz => [...new Array(sz).keys()]

function App ({projectTags,environments,instance}) {
    const [state,setState] = useLocalStorageObject("deployOptions")
    const {mode,project,expires,environment} = state
    const options = {
        mode: ["base","next"],
        project: projectTags,
        environment: !project ? [] : environments.flatMap(e=>(
            e[1]===project || e[1]==="*" ? [e[0]] : []
        )),
        count: `-${environment}-`.includes(`-${instance}-`) ? ["1","4"] : ["1"],
        expires: ["never","1 hour"],
    }
    const isFilled = Object.entries(options).every(([k,o])=>o.includes(state[k]))

    return h('div', {style: {
        fontFamily: "Arial,sans-serif",
        display: "grid",
        gridTemplateAreas: (
            ' "mode project environment" ' +
            ' "expires project environment" ' +
            ' "count project environment" ' +
            ' "ok project environment" '
        ),
    }},
        h(Select, {
            ...subState(options,state,setState,"mode"),
        }),
        h(Select, {
            ...subState(options,state,setState,"project"),
        }),
        h(Select, {
            ...subState(options,state,setState,"environment"),
        }),
        h(Select, {
            ...subState(options,state,setState,"count"),
        }),
        h(Select, {
            ...subState(options,state,setState,"expires"),
        }),
        h("div",{key:"ok",style:{gridArea:"ok"}},
            isFilled && h("button",{onClick:ev=>{
                const count = parseInt(state.count)
                const environments =
                    (count > 1 ? range(count).map(n=>`${-n}`) : [""]).map(n=>{
                        const instanceN = `${instance}${n}`
                        const environmentN =
                            environment.replace(/\w+/g,m=>m===instance?instanceN:m)
                        return { environment: environmentN, instance: instanceN }
                    })
                const body = JSON.stringify({mode,project,expires,environment,environments})
                fetch("state.json",{method:"PUT",body})
            }},"OK")
        )
    );
}

render(h(App,{...formOptions}), document.body);

/*
my $gitlab_get_form = sub{
    my($instance,$conf_str,$client_code)=@_;
    my $conf_lines = &$decode($conf_str);
    my @proj_tags = sort map{ref($_) && $$_[0] eq "C4TAG" ? $$_[1] : ()} @$conf_lines;
    my @comp_proj = &$map(&$get_deploy_conf(),sub{
        my($comp,$conf)=@_; $$conf{project} ? [$comp=>$$conf{project}] : ()
    });
    my $form_options = &$encode({
        projectTags=>\@proj_tags,
        environments=>\@comp_proj,
        instance=>$instance,
    });
    return qq[<!DOCTYPE html><head><meta charset="UTF-8"></head>].
        qq[<body><script type="module">const formOptions=$form_options\n$client_code</script></body>];
};

        my $index_html = "$deploy_conf_url/index.html";
        if($state_str eq ""){
            my $proto_dir = &$mandatory_of(C4CI_PROTO_DIR=>\%ENV);
            my $instance = &$mandatory_of(C4INSTANCE=>\%ENV);
            my $conf_str = syf("cat $local_dir/c4dep.main.json");
            my $client_code = syf("cat $proto_dir/deploy_dialog.js");
            my $form_content = &$gitlab_get_form($instance,$conf_str,$client_code);
            sy("curl -X PUT $index_html --data-binary \@".&$put_temp("index.html"=>$form_content));
        } else {
            my $job_token = &$mandatory_of(CI_JOB_TOKEN=>\%ENV);
            my $git_project_id = &$mandatory_of(CI_PROJECT_ID=>\%ENV);
            my $server_url = &$mandatory_of(CI_SERVER_URL=>\%ENV);
            my $branch = &$mandatory_of(CI_COMMIT_REF_NAME=>\%ENV);
            my $url = "$server_url/api/v4/projects/$git_project_id/trigger/pipeline";
            my $temp = &$put_temp("trigger_payload"=>$state_str);
            sy("curl","-XPOST",$url,map{('--form',$_)} "token=$job_token","ref=$branch","variables[C4CI_STAGE]=INNER");
            print "\nYou can fill $index_html and retry\n";
        }
        &$put_text($out_path,"");

*/