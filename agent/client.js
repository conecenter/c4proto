
function h(tagName,attr,...content){
    const el = document.createElement(tagName)
    Object.entries(attr).forEach(([k,v])=>el.setAttribute(k,v))
    content.forEach(c=>el.appendChild(c))
    return el
}
const text = v => document.createTextNode(v)

///

const selectedStyle = cond => "cursor:pointer;"+(cond?"color:green;":"")

const render = (state,localSessionState) => h("div",{ style: "font-family: monospace" },
    ...state.auth.flatMap(st=>[
        h("div",{},text("auth user: "+st.devName)),
        h("div",{},text("auth date: "+(new Date(st.authTime)).toISOString().split("T")[0])),
    ]),
    h("div",{},
        text("login again to context:"),
        ...contexts.map(c=>h("span",{},h("a",{href:c.authenticator,style:"margin-left: 3pt"},text(c.context))))
    ),
    h("div",{},
        text("pods: "),
        h("span",{
            ...prepareClick({op:"show_pods",value:"my"}),
            style: selectedStyle(localSessionState.showPods!=="all")
        },text("my")),
        text(" "),
        h("span",{
            ...prepareClick({op:"show_pods",value:"all"}),
            style: selectedStyle(localSessionState.showPods==="all")
        },text("all")),
    ),
    ...state.auth.flatMap(auth=>[
        ...state.pods.filter(
            pod => localSessionState.showPods==="all" || pod.includes("-"+auth.devName+"-")
        ).map(pod=>h("div",{
            ...prepareClick({ op: "forward_to_pod", pod: pod }),
            style: "margin-left: 3pt;"+selectedStyle(state.forwardToPods.includes(pod))
        },text(pod)))
    ]),
)

const getMainPrefix = pod => singleOrNull(pod.split("-main-").slice(0,-1))

const runEffects = state => {/*
    const {pods, forwardToPods} = state
    if(pods.some(pod=>forwardToPods.includes(pod))) return
    const forwardPrefixes = state.forwardToPods.map(getMainPrefix).filter(Boolean)
    const cond = forwardToPods.length===0 ?
        (pod=>pod.includes("-main-")) : (pod=>forwardPrefixes.includes(getMainPrefix(pod)))
    const pod = singleOrNull(pods.filter(cond))
    if(!pod) return
    postForm({ "op": "forward_to_pod", "pod": pod })
*/}

const forward_to_pod = task => postForm(task)

const show_pods = task => updateLocalSessionState(was=>({...was,showPods:task.value}))

const handlers = {forward_to_pod,show_pods}

///

const prepareClick = task => ({ "data-click": JSON.stringify(task) })

const getLocalSessionStateText = () => sessionStorage.getItem("state")||'{}'
const updateLocalSessionState =
    f => sessionStorage.setItem("state",JSON.stringify(f(JSON.parse(getLocalSessionStateText()))))

const postForm = body => fetch("/form", {
    method: "POST", redirect: 'manual',
    body: new URLSearchParams(body), headers: { 'Content-Type': 'application/x-www-form-urlencoded' }
})

const singleOrNull = l => l.length === 1 ? l[0] : null

const getState = async () => {
    const response = await fetch(`/state.json?${Date.now()}`)
    if(response.status != 200) throw new Error(`${response.url} | ${response.status}`)
    return await response.text()
}

const findParent = (el, f) => f(el) || el && findParent(el.parentElement, cond)

const handleClick = ev => {
    const taskStr = findParent(ev.target, el=>el.getAttribute("data-click"))
    if(taskStr){
        const task = JSON.parse(taskStr)
        handlers[task.op](task)
    }
    //todo: wake
}

const trackState = async () => {
    let was = {}
    while(true){
        const stateText = await getState()
        const localSessionStateText = getLocalSessionStateText()
        if(was.stateText === stateText && was.localSessionStateText === localSessionStateText) {
            await sleep(1000)
        } else {
            const state = JSON.parse(stateText)
            const localSessionState = JSON.parse(localSessionStateText)
            const el = render(state,localSessionState)
            if(was.el) document.body.removeChild(was.el)
            if(el) document.body.appendChild(el)
            el.onclick = handleClick
            runEffects(state)
            was = {el,stateText,localSessionStateText}
        }
    }
}

const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));

trackState()
