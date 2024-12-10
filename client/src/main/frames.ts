
import {useState,useEffect,createRoot,Root} from "./react"
import {manageAnimationFrame} from "./util"

export const useIsolatedFrame = (makeChildren: (body: HTMLElement)=>React.ReactNode) => {
    const [frameElement,ref] = useState<HTMLIFrameElement|null>(null)
    const [theBody,setBody] = useState<HTMLElement|null>(null)
    const [root,setRoot] = useState<Root|null>(null)
    useEffect(() => frameElement && !theBody ? manageAnimationFrame(frameElement.ownerDocument.defaultView, ()=>{
        const body = frameElement?.contentWindow?.document.body
        if(body?.id) setBody(body)
    }) : undefined, [theBody, setBody, frameElement])
    useEffect(() => {
        if(!theBody) return 
        const [root, unmount] = doCreateRoot(theBody)
        setRoot(root)
        return unmount
    }, [theBody])
    useEffect(() => theBody ? root?.render(makeChildren(theBody)) : undefined, [theBody,root,makeChildren])
    const srcDoc = '<!DOCTYPE html><meta charset="UTF-8"><body id="blank"></body>'
    return {srcDoc,ref}
}
export const doCreateRoot = (parentNativeElement: HTMLElement): [Root,()=>void] => { //, children: React.ReactNode; root.render(children)
    const rootNativeElement = parentNativeElement.ownerDocument.createElement("span")
    parentNativeElement.appendChild(rootNativeElement)
    const root = createRoot(rootNativeElement)
    return [root, () => {
        root.unmount()
        rootNativeElement.parentElement?.removeChild(rootNativeElement)
    }]
}
