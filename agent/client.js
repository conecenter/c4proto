
function h(tagName,attr,...content){
    const el = document.createElement(tagName)
    Object.entries(attr).forEach(([k,v])=>el.setAttribute(k,v))
    content.forEach(c=>el.appendChild(c))
    return el
}
const text = v => document.createTextNode(v)

///

document.body.appendChild(h("div",{},
    ...publicState.map(st=>h("div",{},text(st.devName))),
    ...contexts.map(c=>h("div",{},h("a",{href:c.authenticator},text(c.context))))
))
