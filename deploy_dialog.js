
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

const subState = (options,value,setValue,key)=>({
    key,
    value: value[key],
    setValue: v => setValue(st=>({...st,[key]:v})),
    gridArea: key,
    options: options[key],
})

const range = sz => [...new Array(sz).keys()]

function App ({projectTags,environments,instance}) {
    const [state,setState] = useState({
        mode: "base",
        count: "1",
        expires: "never",
    })
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
