
import {useEffect,useState} from "../main/react"
import {weakCache,manageAnimationFrame,assertNever,Identity,ObjS,UseSync, EnqueuePatch} from "../main/util"

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
    identity?: Identity
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

type CanvasProps = CanvasPart & {
    onChange: (ev: {target:{value:string}})=>void, parentNode: HTMLElement|undefined,
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
const gatherDataFromPathTree = weakCache((prop: CanvasPart): [CanvasCommands,{[K:string]:string|undefined}] => {
    const [takeColors, addColors] = Buffer<[string,Identity|undefined]>()
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
type CanvasBranchContext = { enqueue: EnqueuePatch, isRoot: boolean, win: Window }
type CanvasModArgs = { canvasFactory: CanvasFactory, useSync: UseSync, useBranch: ()=>CanvasBranchContext }
export type UseCanvas = (props: CanvasProps) => ObjS<string>
export const UseCanvas = ({canvasFactory,useBranch}:CanvasModArgs) => (prop:CanvasProps) => {
    const {onChange, value, isGreedy, style: argStyle, options, parentNode} = prop
    const {enqueue,isRoot,win} = useBranch()
    const [canvas, setCanvas] = useState<C4Canvas|undefined>()
    useEffect(()=>{
        const canvas = canvasFactory(options||{})
        setCanvas(canvas)
        return ()=>{ canvas.remove() }
    }, [canvasFactory, options])
    useEffect(()=>{
        if(!parentNode || !canvas) return
        const [commands,colorToContext] = gatherDataFromPathTree(prop)
        const parsed = {...prop,commands,value,onChange}
        const sendToServer = (patch: {headers: ObjS<string>}, color: string) => {
            if(!colorToContext[color]) return
            enqueue(colorToContext[color], {value: "", skipByPath: false, ...patch}) //?move closure
        }
        const state = {parentNode,sizesSyncEnabled:isRoot,canvas,parsed,sendToServer}
        return manageAnimationFrame(win, ()=>canvas.checkActivate(state))
    })
    const style = isGreedy || !value ? argStyle : {...argStyle, height: parseValue(value).pxMapH+"px"}
    return style
}
