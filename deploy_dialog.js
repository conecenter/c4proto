
import { h, render } from 'https://cdn.pika.dev/preact'
import { useState, useEffect } from 'https://cdn.pika.dev/preact/hooks'

function Select({options,value,setValue}){
    return h('div', {
        style: {
            padding: "1em",
        }
    }, options.map(option=>h("span",{
        key: option,
        onClick: ev=>setValue(option),
        style: {
            margin: "0.2em",
            padding: "0.2em",
            display: "inline-block",
            border: `1px solid ${value===option?"green":"white"}`,
            borderRadius: "0.2em",
            cursor: "hand",
        },
    },option)))
}

const subState = (value,setValue,key)=>({
    key,
    value: value[key],
    setValue: v => setValue(st=>({...st,[key]:v,last:key})),
})

function App (props) {
    const [state,setState] = useState({})
    const {project,last} = state
    useEffect(()=>{
        if(last==="environment")
            fetch("state.json",{method:"PUT",body:JSON.stringify(state)})
    },[state])
    return h('div', {style: {fontFamily: "Arial,sans-serif", textAlign:"center"}},
        h(Select, {
            ...subState(state,setState,"mode"),
            options:["base","next"],
        }),
        h(Select, {
            ...subState(state,setState,"project"),
            options: formOptions.projectTags
        }),
        project && h(Select, {
            ...subState(state,setState,"environment"),
            options: formOptions.environments.flatMap(e=>(
                e[1]===project || e[1]==="*" ? [e[0]] : []
            )),
        }),
    );
}

render(h(App), document.body);