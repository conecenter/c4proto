
function h(tagName,attr,...content){
    const el = document.createElement(tagName)
    Object.entries(attr).forEach(([k,v])=>el.setAttribute(k,v))
    content.forEach(c=>el.appendChild(c))
    return el
}
const text = v => document.createTextNode(v)

///

const render = state => h("div",{ style: "font-family: monospace" },
    ...state.auth.flatMap(st=>[
        h("div",{},text("auth user: "+st.devName)),
        h("div",{},text("auth date: "+(new Date(st.authTime)).toISOString().split("T")[0])),
    ]),
    h("div",{},
        text("login again to context:"),
        ...contexts.map(c=>h("span",{},h("a",{href:c.authenticator,style:"margin-left: 3pt"},text(c.context))))
    ),
    text("pods:"),
    ...state.pods.map(pod=>h("div",{
        "data-click-form": prepareAction({ "op": "forward_to_pod", "pod": pod }),
        style: "margin-left: 3pt; cursor: pointer;"+(state.forwardToPods.includes(pod)?"color:green":"")
    },text(pod)))
)

const getMainPrefix = pod => singleOrNull(pod.split("-main-").slice(0,-1))

const runEffects = state => {
    const {pods, forwardToPods} = state
    if(pods.some(pod=>forwardToPods.includes(pod))) return
    const forwardPrefixes = state.forwardToPods.map(getMainPrefix).filter(Boolean)
    const cond = forwardToPods.length===0 ?
        (pod=>pod.includes("-main-")) : (pod=>forwardPrefixes.includes(getMainPrefix(pod)))
    const pod = singleOrNull(pods.filter(cond))
    if(!pod) return
    postAction(prepareAction({ "op": "forward_to_pod", "pod": pod }))
}

///

const prepareAction = opt => new URLSearchParams(opt)

const postAction = body => fetch("/form", {
    method: "POST", redirect: 'manual', body, headers: { 'Content-Type': 'application/x-www-form-urlencoded' }
})

const singleOrNull = l => l.length === 1 ? l[0] : null

const getState = async () => {
    const response = await fetch(`/state.json?${Date.now()}`)
    if(response.status != 200) throw new Error(`${response.url} | ${response.status}`)
    return await response.text()
}

const findParent = (el, f) => f(el) || el && findParent(el.parentElement, cond)

const handleClick = ev => {
    const body = findParent(ev.target, el=>el.getAttribute("data-click-form"))
    if(!body) return
    const resp = postAction(body)
    //todo: wake
}

const trackState = async () => {
    let was = {}
    while(true){
        const stateText = await getState()
        if(was.stateText === stateText) {
            await sleep(1000)
        } else {
            const state = JSON.parse(stateText)
            const el = render(state)
            if(was.el) document.body.removeChild(was.el)
            if(el) document.body.appendChild(el)
            el.onclick = handleClick
            runEffects(state)
            was = {el,stateText}
        }
    }
}

const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));

trackState()
