
import {createRoot,Root} from "react-dom/client"
import {useState,useEffect} from "./react"

const FRAME_SRC = "/blank.html"

export const useIsolatedFrame = (makeChildren: (body: HTMLElement)=>React.ReactNode) => {
    const [frameElement,setFrameElement] = useState<HTMLIFrameElement|null>(null)
    const [root,setRoot] = useState<Root|null>(null)
    const theBody = frameElement?.contentWindow?.document.body || null
    useEffect(() => {
        if(!theBody) return 
        const [root, unmount] = doCreateRoot(theBody)
        setRoot(root)
        return () => { setTimeout(() => unmount()) }  // avoid unmounting during render
    }, [theBody])
    useEffect(() => theBody ? root?.render(makeChildren(theBody)) : undefined, [theBody,root,makeChildren])
    const onLoad = (e: React.SyntheticEvent<HTMLIFrameElement, Event>) => setFrameElement(e.currentTarget)
    return {src: FRAME_SRC, onLoad}
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
