
import React from "react"
import {createRoot} from "react-dom/client"

const exchHello = ev => fetch("/kop",{method:"POST",body:{op:"get_state"}}).then(resp=>alert(resp))

const App = () => <h1 onclick={exchHello}>Hello, world!</h1>

(() => {
    const rootNativeElement = document.createElement("span")
    document.body.appendChild(rootNativeElement)
    createRoot(rootNativeElement).render(<App/>)
})()