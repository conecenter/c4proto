
import {useState,useEffect,createRoot} from "./hooks"
import {manageAnimationFrame} from "./util"

export const useIsolatedFrame = (makeChildren: (body: HTMLElement)=>React.ReactNode) => {
    const [frameElement,ref] = useState<HTMLIFrameElement|null>(null)
    const [theBody,setBody] = useState<HTMLElement|null>(null)
    useEffect(() => frameElement && !theBody ? manageAnimationFrame(frameElement, ()=>{
        const body = frameElement?.contentWindow?.document.body
        if(body?.id) setBody(body)
    }) : undefined, [theBody, setBody, frameElement])
    useEffect(() => theBody ? doCreateRoot(theBody, makeChildren(theBody)) : undefined, [theBody,makeChildren])
    const srcDoc = '<!DOCTYPE html><meta charset="UTF-8"><body id="blank"></body>'
    return {srcDoc,ref}
}
export const doCreateRoot = (parentNativeElement: HTMLElement, children: React.ReactNode) => {
    const rootNativeElement = parentNativeElement.ownerDocument.createElement("span")
    parentNativeElement.appendChild(rootNativeElement)
    const root = createRoot(rootNativeElement)
    root.render(children)
    return () => {
        root.unmount()
        rootNativeElement.parentElement?.removeChild(rootNativeElement)
    }
}
