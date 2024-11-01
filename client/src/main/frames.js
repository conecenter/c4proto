// @ts-check
import {useState,useCallback,useEffect,createElement} from "react"
import {createRoot} from "react-dom"

const useAnimationFrame = (element,callback) => {
    useEffect(()=>{
        if(!callback || !element) return
        const {requestAnimationFrame,cancelAnimationFrame} = element.ownerDocument.defaultView
        const animate = () => {
            callback()
            req = requestAnimationFrame(animate,element)
        }
        let req = requestAnimationFrame(animate,element)
        return () => cancelAnimationFrame(req)
    }, [element,callback])    
}

////

export function IsolatedFrame({children,...props}){
    const [frameElement,ref] = useState()
    const [theBody,setBody] = useState()
    const frame = useCallback(()=>{
        const body = frameElement?.contentWindow?.document.body
        if(body.id) setBody(body)
    }, [frameElement,setBody])
    useAnimationFrame(frameElement, !theBody && frame)
    useEffect(() => theBody && doCreateRoot(theBody,children), [theBody,children])
    const srcdoc = '<!DOCTYPE html><meta charset="UTF-8"><body id="blank"></body>'
    return createElement("iframe",{...props,srcdoc,ref})
}

export const doCreateRoot = (parentNativeElement,children) => {
    const rootNativeElement = parentNativeElement.ownerDocument.createElement("span")
    parentNativeElement.appendChild(rootNativeElement)
    const root = createRoot(rootNativeElement)
    root.render(children)
    return () => {
        root.unmount()
        rootNativeElement.parentElement.removeChild(rootNativeElement)
    }
}
