
import {useEffect,useState} from "../main/react"
import {weakCache,manageAnimationFrame,assertNever,Identity,ObjS,mergeSimple,patchFromValue,SyncAppContext,BranchContext} from "../main/util"

const Buffer = <T>(): [()=>T[], (...items: T[])=>void] => {
    let finished = 0
    const res = [] as unknown as T[] & { push(...items: T[]): void }
    const finallyTakeAll = () => (finished++) > 0 ? assertNever("finished") : res
    const pushMany = (...items: T[]) => finished > 0 ? assertNever("finished") : res.push(...items)
    return [finallyTakeAll, pushMany]
}

////

type CanvasCommands = unknown[]
type CanvasPart = {
    commands: CanvasCommands
    commandsFinally: CanvasCommands
    identity: Identity
    children: CanvasPart[]
}
type CanvasState = {
    parentNode: HTMLElement|undefined, sizesSyncEnabled: boolean, canvas: C4Canvas, 
    parsed: CanvasProps & { commands: CanvasCommands }, 
    sendToServer: (target: {headers: ObjS<string>}, color: string) => void
}
type C4Canvas = {
    checkActivate(state: CanvasState): void
    remove(): void
}
type CanvasOptions = {[K:string]:unknown}
export type CanvasFactory = (opt: CanvasOptions)=>C4Canvas
export type CanvasAppContext = { canvasFactory: CanvasFactory } & SyncAppContext

type CanvasProps = CanvasPart & {
    identity: Identity, branchContext: CanvasAppContext & BranchContext, parentNode: HTMLElement|undefined,
    isGreedy: boolean, value: string, style: {[K:string]:string}, options: CanvasOptions
}

const replaceArrayTree = (root: unknown[], replace: (item: unknown)=>unknown) => {
    const traverse = (arr: unknown[]): unknown[] => {
        const nArr = arr.map((el:unknown) => Array.isArray(el) ? traverse(el) : replace(el))
        return arr.some((el,j)=>el!==nArr[j]) ? nArr : arr
    }
    return traverse(root)
}
const color = (fromN: number, pos: number) => {
    const size = 5
    const mask = (1<<size) - 1
    return ((fromN>>(size*pos)) & mask) << 3
}
const colorKeyGen = (fromN: number) => `rgb(${color(fromN,2)},${color(fromN,1)},${color(fromN,0)})`
const colorKeyMarker = "[colorPH]"
const makeColor = (index: number) => ({index,value:colorKeyGen(index)})
const gatherDataFromPathTree = weakCache((prop: CanvasPart): [CanvasCommands,{[K:string]:string}] => {
    const [takeColors, addColors] = Buffer<[string,Identity]>()
    const [takeCommands, addCommands] = Buffer<unknown>()
    let color = makeColor(0)
    const traverse = (node: CanvasPart) => {
        if(node.commands){
            const nCommands = replaceArrayTree(node.commands, el => el===colorKeyMarker ? color.value : el)
            if(nCommands !== node.commands){
                addColors([color.value, node.identity])
                color = makeColor(color.index+1)
            }
            addCommands(...nCommands)
        }
        node.children && node.children.forEach(traverse)
        node.commandsFinally && addCommands(...node.commandsFinally)
    }
    traverse(prop)
    return [takeCommands(),Object.fromEntries(takeColors())]
})

const parseValue = (value: string) => {
    const [cmdUnitsPerEMZoom,aspectRatioX,aspectRatioY,pxMapH] = value.split(",") // eslint-disable-line no-unused-vars
    return {cmdUnitsPerEMZoom,aspectRatioX,aspectRatioY,pxMapH}
}

export const useCanvas = (prop:CanvasProps) => {
    const {identity, value: incomingValue, branchContext, isGreedy, style: argStyle, options, parentNode} = prop
    const {canvasFactory,enqueue,isRoot,useSync} = branchContext
    const [sizePatches, enqueueSizePatch] = useSync(identity)
    const value = mergeSimple(incomingValue, sizePatches)
    const [canvas, setCanvas] = useState<C4Canvas|undefined>()
    useEffect(()=>{
        const canvas = canvasFactory(options||{})
        setCanvas(canvas)
        return ()=>{ canvas.remove() }
    }, [canvasFactory, options])
    useEffect(()=>{
        if(!parentNode || !canvas) return
        const [commands,colorToContext] = gatherDataFromPathTree(prop)
        const onChange = ({target:{value}}:{target:{value:string}}) => {
            enqueueSizePatch(patchFromValue(value))
        }
        const parsed = {...prop,commands,value,onChange}
        const sendToServer = (patch: {headers: ObjS<string>}, color: string) => {
            if(!colorToContext[color]) return
            enqueue({value: "", skipByPath: false, ...patch, identity: colorToContext[color]}) //?move closure
        }
        const state = {parentNode,sizesSyncEnabled:isRoot,canvas,parsed,sendToServer}
        return manageAnimationFrame(parentNode, ()=>canvas.checkActivate(state))
    })
    const style = isGreedy || !value ? argStyle : {...argStyle, height: parseValue(value).pxMapH+"px"}
    return style
}
