
import {useState,useEffect,createRoot} from "./hooks"
import {manageAnimationFrame} from "./util"

export const useIsolatedFrame = (children: React.ReactNode) => {
    const [frameElement,ref] = useState<HTMLIFrameElement>()
    const [theBody,setBody] = useState<HTMLElement>()
    useEffect(() => frameElement && !theBody ? manageAnimationFrame(frameElement, ()=>{
        const body = frameElement?.contentWindow?.document.body
        if(body?.id) setBody(body)
    }) : undefined, [theBody, setBody, frameElement])
    useEffect(() => theBody && doCreateRoot(theBody, children), [theBody,children])
    const srcdoc = '<!DOCTYPE html><meta charset="UTF-8"><body id="blank"></body>'
    return {srcdoc,ref}
}
//type Root = { render(children: React.ReactNode): void, unmount(): void }
//type CreateRoot = (container: ReactDOM.Container) => Root
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
