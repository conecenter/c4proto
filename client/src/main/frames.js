// @ts-check
import {useState,useCallback,useEffect} from "./hooks.js"

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

export function useIsolatedFrame(createRoot,children){
    const [frameElement,ref] = useState()
    const [theBody,setBody] = useState()
    const frame = useCallback(()=>{
        const body = frameElement?.contentWindow?.document.body
        if(body.id) setBody(body)
    }, [frameElement,setBody])
    useAnimationFrame(frameElement, !theBody && frame)
    useEffect(() => theBody && doCreateRoot(createRoot, theBody, children), [theBody,children])
    const srcdoc = '<!DOCTYPE html><meta charset="UTF-8"><body id="blank"></body>'
    return [{srcdoc},ref]
}

export const doCreateRoot = (createRoot, parentNativeElement, children) => {
    const rootNativeElement = parentNativeElement.ownerDocument.createElement("span")
    parentNativeElement.appendChild(rootNativeElement)
    const root = createRoot(rootNativeElement)
    root.render(children)
    return () => {
        root.unmount()
        rootNativeElement.parentElement.removeChild(rootNativeElement)
    }
}
