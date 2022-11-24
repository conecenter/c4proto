
function h(tagName,attr,...content){
    const el = document.createElement(tagName)
    Object.entries(attr).forEach(([k,v])=>el.setAttribute(k,v))
    content.forEach(c=>el.appendChild(c))
    return el
}
const text = v => document.createTextNode(v)

///

const render = state => h("div",{ style: "font-family: monospace" },
    ...state.auth.map(st=>h("div",{},text("username: "+st.devName))),
    h("div",{},
        text("login again to context:"),
        ...contexts.map(c=>h("span",{},h("a",{href:c.authenticator,style:"margin-left: 3pt"},text(c.context))))
    ),
    text("pods:"),
    ...state.pods.map(pod=>h("div",{
        "data-click-form": new URLSearchParams({ "op": "forward_to_pod", "pod": pod }),
        style: "margin-left: 3pt; cursor: pointer;"+(state.forwardToPods.includes(pod)?"color:green":"")
    },text(pod)))
)

///

const getState = async () => {
    const response = await fetch(`/state.json?${Date.now()}`)
    if(response.status != 200) throw new Error(`${response.url} | ${response.status}`)
    return await response.text()
}

const findParent = (el, f) => f(el) || el && findParent(el.parentElement, cond)

const handleClick = ev => {
    const body = findParent(ev.target, el=>el.getAttribute("data-click-form"))
    if(!body) return
    const resp = fetch("/form", {
        method: "POST",
        headers: {
            'Content-Type': 'application/x-www-form-urlencoded'
        },
        redirect: 'manual',
        body
    })
    //todo: wake
}

const trackState = async () => {
    let was = {}
    while(true){
        const stateText = await getState()
        if(was.stateText === stateText) {
            await sleep(1000)
        } else {
            const el = render(JSON.parse(stateText))
            if(was.el) document.body.removeChild(was.el)
            if(el) document.body.appendChild(el)
            el.onclick = handleClick
            was = {el,stateText}
        }
    }
}

const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));

trackState()
