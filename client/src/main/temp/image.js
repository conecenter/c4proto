"use strict";
import { useState, useEffect, createElement, useRef } from '../../main/react-prod.js'
import { globalRegistry, checkActivateCalls } from './utils.js'


const initViewBox = "0 0 0 0"
const rotateStyle = (rotate) => rotate ? { transform: `rotate(${rotate})` } : {}

const adaptiveTag = "#adaptive"

const $ = createElement

const clear = (url) => url.replace(/#.*$/, "")

const SVGElement = ({ url, ...props }) => {
    const ref = useRef(null)
    const savedCallback = useRef()
    const [state, setState] = useState({ viewBox: initViewBox, color: "black", content: "" })


    useEffect(() => {
        function replaceSvgTag(str) {
            return str.replace(/<\/?svg(.|\n)*?>/g, "")
        }

        function getViewBox(str) {
            const reg = /viewBox=["'](.+?)["']/
            return str.match(reg)[1]
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

    useEffect(() => {
        /*ref.current.onload = () => {
            const {x,y, width, height} = ref.current.getBBox()
            const viewBox = `${x} ${y} ${width} ${height}`           
            window.console.log("loaded2")
            setState({...state,viewBox})
        }*/
        savedCallback.current = () => {
            if (!ref.current) return
            const win = ref.current.ownerDocument.defaultView
            const color = !props.color || props.color == "adaptive" ? win.getComputedStyle(ref.current).color : props.color
            const { x, y, width, height } = ref.current.getBBox()
            const defViewBox = `${x} ${y} ${width} ${height}`
            const viewBox = props.viewPort ? props.viewPort : state.viewBox !== initViewBox ? state.viewBox : defViewBox
            if (state.color != color || state.viewBox != viewBox)
                setState(was => { return { ...was, viewBox, color } })

        }
    })

    useEffect(() => {
        const color = () => savedCallback.current()
        checkActivateCalls.add(color)
        return () => {
            checkActivateCalls.remove(color)
        }
    }, [])

    const size = state.viewBox == initViewBox ? { width: "0px", height: "0px" } : {}
    const htmlObject = { __html: state.content }
    return $("svg", {
        dangerouslySetInnerHTML: htmlObject,
        viewBox: state.viewBox,
        fill: state.color,
        ref: ref,
        className: props.className,
        style: props.style,
        ...size
    })
}

const ImageElement = ({ src, forceSrcWithoutPrefix, title, className, rotate, color, viewPort }) => {
    const _className = (className || "") + (rotate ? " transitions" : "")
    const rotateStyles = rotateStyle(rotate)
    const style = { ...rotateStyles }
    const urlPrefix = globalRegistry.get().feedbackUrl
    const _src = !forceSrcWithoutPrefix ? (urlPrefix || "") + src : src
    if (color) {
        const __src = clear(_src)
        return $(SVGElement, { url: __src, className: _className, style, color, viewPort })
    }
    else {
        return $("img", { src: _src, title, className: _className, style: style })
    }
}




export { ImageElement, SVGElement, adaptiveTag }