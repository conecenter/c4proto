
import { useState, useEffect, createElement } from "react"

const initViewBox = "0 0 0 0"
const rotateStyle = (rotate) => rotate ? { transform: `rotate(${rotate})` } : {}

const adaptiveTag = "#adaptive"

const $ = createElement

const clear = (url) => url.replace(/#.*$/, "")

function SVGElement({ url, className, style, color, ...props }){
    const [element,setElement] = useState(null)
    const [state, setState] = useState({ viewBox: initViewBox, content: "" })


    useEffect(() => {
        function replaceSvgTag(str) {
            return str.replace(/<\/?svg(.|\n)*?>/g, "")
        }

        function getViewBox(str) {
            const reg = /viewBox=["'](.+?)["']/
            const res = str.match(reg)
            return res ? res[1] : initViewBox
        }

        if (url.startsWith("data:")) {
            const decodedUrl = atob(url.replace(/data:.+?,/, ""))
            const viewBox = getViewBox(decodedUrl)
            setState(was => { return { ...was, viewBox, content: replaceSvgTag(decodedUrl) } })
        } else {
            fetch(url)
                .then(r => r.text())
                .then(r => {
                    const viewBox = getViewBox(r)
                    setState(was => { return { ...was, viewBox, content: replaceSvgTag(r) } })
                })
        }
    }, [url])

    const [adaptiveColor,setAdaptiveColor] = useState("black")
    useEffect(() => {
        if (!element) return
        const win = element.ownerDocument.defaultView
        setAdaptiveColor(win.getComputedStyle(element).color)
    },[element])
    const fill = color || adaptiveColor

    const size = state.viewBox == initViewBox ? { width: "0px", height: "0px" } : {}
    const dangerouslySetInnerHTML = { __html: state.content }
    return $("svg", {
        dangerouslySetInnerHTML,
        viewBox: state.viewBox,
        fill, className, style,
        ref: setElement,
        ...size
    })
}

const ImageElement = ({ src, title, className: argClassName, rotate, color, viewPort, ...props }) => {
    const className = (argClassName || "") + (rotate ? " transitions" : "")
    const style = rotateStyle(rotate)
    if (color) {
        const __src = clear(src)
        const colorOpt = color === "adaptive" ? {} : {color}
        return $(SVGElement, { url: __src, className, style, ...colorOpt, viewPort })
    }
    else {
        return $("img", { src, title, className, style, ...props })
    }
}

export { ImageElement, SVGElement, adaptiveTag }