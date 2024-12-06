
import React from "react"
import {StrictMode} from "react"
import {useState,useCallback,useMemo,createElement,createContext,useContext} from "../main/react"
import {assertNever, CreateNode, Identity, identityAt, ObjS, patchFromValue, mergeSimple, Login, asString, EnqueuePatch} from "../main/util"
import {useSessionManager} from "../main/session"
import {doCreateRoot,useIsolatedFrame} from "../main/frames"
import {useSyncRoot, UseSyncRootArgs} from "../main/sync-root"
import { UseCanvas, CanvasFactory } from "../extra/canvas-manager"
import {AckContext, UseSyncMod} from "../main/sync-hooks"
import {LocationComponents} from "../main/location"
import {ToAlienMessageComponents} from "../main/receiver"

const completeIdOf = identityAt("complete")
const forceRemoveIdOf = identityAt("forceRemove")
function ExampleReplicaList({replicas}:{replicas?:{
    key: string, identity: Identity, 
    role: string, startedAt: string, hostname: string, version: string, completion: string,
}[]}){
    return <table style={{border: "1px solid silver"}}><tbody>
        <tr>
            {[
                ["role","Role",7],["startedAt","Started At",15],["hostname","Hostname",15],
                ["version","Version",15],["completion","Completion",15],
                ["remove","",7]
            ].map(([key,caption,width]) => <th style={{width: `${width}%`}} key={key}>{caption}</th>)}
        </tr>
        {(replicas??[]).map(({key,identity,role,startedAt,hostname,version,completion})=>(
        <tr key={key}>
            <td key="role">{role}</td><td key="startedAt">{startedAt}</td><td key="hostname">{hostname}</td>
            <td key="version">{version}</td><td key="completion">{completion}</td>
            <td key="remove">
                <ExampleButton key="complete" caption="x..." identity={completeIdOf(identity)}/>
                <ExampleButton key="force-remove" caption="x(" identity={forceRemoveIdOf(identity)}/>
            </td>
        </tr>
        ))}
    </tbody></table>
}

const makeSavepointIdOf = identityAt("makeSavepoint")
const revertToSavepointIdOf = identityAt("revertToSavepoint")
function ExampleReverting({offset,identity}:{offset: string, identity: Identity}){
    return <div>
        <ExampleButton caption="make savepoint" identity={makeSavepointIdOf(identity)}/>
        {offset && <ExampleButton caption={`revert to ${offset}`} identity={revertToSavepointIdOf(identity)}/>}
    </div>
}

const activateIdOf = identityAt("activate")
const sizesChangeIdOf = identityAt('sizesChange')
type ExampleFigure = {offset: number, identity: Identity, isActive: boolean}
const exampleCanvasOptions = {noOverlay:false}
type ExampleCanvasProps = { sizesValue: string, identity: Identity, figures: ExampleFigure[] }
const ExampleCanvasMod = (useCanvas: UseCanvas) => ({sizesValue,identity,figures}:ExampleCanvasProps) => { 
    const cmd = (...args: unknown[]) => [args.slice(0,-1),args.at(-1)]
    const rect = (x: number, y: number, w: number, h: number) => [
        ...cmd(x,y,"moveTo"), ...cmd(x+w,y,"lineTo"), ...cmd(x+w,y+h,"lineTo"), ...cmd(x,y+h,"lineTo"), ...cmd(x,y,"lineTo"),
    ]
    const layer = (name: string, fill: string, stroke: string) => cmd(name,[
        ...cmd("beginPath"), ...cmd("applyPath"), 
        ...cmd("fillStyle", fill, "set"), ...cmd("strokeStyle", stroke, "set"), ...cmd("fill"), ...cmd("stroke"),
    ],"inContext")
    const figure = ({offset, identity, isActive}: ExampleFigure) => ({
        identity: activateIdOf(identity),
        commands: [
            ...cmd("setMainContext"), ...cmd("save"),
            ...cmd(offset,0,"translate"), ...cmd(0.1,"rotate"),   
            ...cmd("applyPath",[...rect(10,20,30,40), ...cmd("closePath")], "definePath"),
            ...layer("preparingCtx", isActive ? "rgb(0,255,0)":"rgb(255,0,0)", "#c9c4c3"),
            ...layer("reactiveCtx", "[colorPH]", "[colorPH]"),
        ],
        children: [],
        commandsFinally: [...cmd("setMainContext"), ...cmd("restore")],
    })
    const [parentNode, ref] = useState<HTMLElement|undefined>()
    const cProps = {
        parentNode,
        value: sizesValue, identity: sizesChangeIdOf(identity), style: {height:"100vh"},
        width: 100, height: 100, options: exampleCanvasOptions, 
        zoomSteps: 4096, minCmdUnitsPerEMZoom: 0, initialFit: "xy", isGreedy: true,
        commands: [], children: figures.map(figure), commandsFinally: [],
    }
    const style = useCanvas(cProps)
    return createElement("div",{ style, ref },[])
}

type TestSessionListProps = { sessions?: {key:string,branchKey:string,userName:string,isOnline:boolean}[] }
function TestSessionList({sessions}:TestSessionListProps){
    return <div>{(sessions||[]).map(({key,branchKey,userName,isOnline})=>(
        <div key={key}>
            User: {userName} {isOnline ? "online" : "offline"}<br/>
            <ExampleFrame key="frame" {...{branchKey,style:{width:"30%",height:"400px"}}} />
        </div>
    ))}</div>
}

const commentsChangeIdOf = identityAt('commentsChange')
const removeIdOf = identityAt('remove')
const commentsFilterChangeIdOf = identityAt('commentsFilterChange')
const addIdOf = identityAt('add')
function ExampleTodoTaskList(
    {commentsFilterValue,tasks,identity}:
    {commentsFilterValue:string,tasks?:{key:string,commentsValue:string,identity:Identity}[],identity:Identity}
){
    return <table style={{border: "1px solid silver"}}><tbody>
        <tr>
            <td key="comment">Comments contain <ExampleInput value={commentsFilterValue} identity={commentsFilterChangeIdOf(identity)}/></td>
            <td key="add"><ExampleButton caption="+" identity={addIdOf(identity)}/></td>
        </tr>
        <tr><th>Comments</th></tr>
        {(tasks??[]).map(({key,commentsValue,identity})=>(
        <tr key={key}>
            <td key="comment"><ExampleInput value={commentsValue} identity={commentsChangeIdOf(identity)}/></td>
            <td key="remove"><ExampleButton caption="x" identity={removeIdOf(identity)}/></td>
        </tr>
        ))}
    </tbody></table>
}

function ExampleButton({caption, identity}:{caption:string,identity:Identity}){
    const [patches, enqueuePatch] = useSync(identity)
    const onClick = useCallback(() => enqueuePatch(patchFromValue("1")), [enqueuePatch])
    const changing = patches.length > 0
    const backgroundColor = changing ? "yellow" : "white"
    return <input type="button" value={caption} onClick={onClick} style={{backgroundColor}} />
}

function ExampleInput({value: incomingValue, identity}:{value: string, identity: Identity}){
    const [patches, enqueuePatch] = useSync(identity)
    const value = mergeSimple(incomingValue, patches)
    const onChange = useCallback((ev: React.ChangeEvent<HTMLInputElement>) => { 
        enqueuePatch(patchFromValue(ev?.target.value)) 
    }, [enqueuePatch])
    const changing = patches.length > 0
    const backgroundColor = changing ? "yellow" : "white"
    return <input type="text" value={value} onChange={onChange} style={{backgroundColor}} />
}

const noReloadBranchKey = ()=>{}
const noLogin: Login = (u,p) => assertNever("no login in non-root")
type ExampleFrameProps = { branchKey: string, style: ObjS<string> }
function ExampleFrame({branchKey,style}:ExampleFrameProps){
    const {createNode,sessionKey} = useSender()
    const makeChildren = useCallback((body:HTMLElement) => {
        const win = body.ownerDocument.defaultView ?? assertNever("no window")
        const syncProps: SyncRootArgs = {
            createNode, login: noLogin, reloadBranchKey: noReloadBranchKey, isRoot: false, win, sessionKey, branchKey
        }
        return <SyncRoot {...syncProps}/>        
    }, [createNode,sessionKey,branchKey])
    const {ref,...props} = useIsolatedFrame(makeChildren)
    return <iframe {...props} {...{style}} ref={ref} />
}

function ExampleLogin(){
    const {login} = useSender()
    const [user, setUser] = useState("")
    const [pass, setPass] = useState("")
    const [error, setError] = useState(false)
    const onClick = useCallback(() => {
        login(user, pass).then(()=>{}, err=>setError(true))
    }, [login,user,pass])
    return <div>
        Username <input type="text" value={user} onChange={ev=>setUser(ev.target.value)}/>,
        password <input type="password" value={pass} onChange={ev=>setPass(ev.target.value)}/>&nbsp;
        <input type="button" value="sign in" onClick={onClick}/>
        {error ? " FAILED" : ""}
    </div>
}

function ExampleMenu(
    { items, children }:
    { items?: { key: string, caption: string, identity: Identity }[], children?: React.ReactElement[] }
){
    return <div>
        <div style={{padding:"2pt"}} key="menu">
        {(items||[]).flatMap(({key,caption,identity},i)=>[
            i>0?"|":"", <ExampleButton {...{caption,identity:activateIdOf(identity)}} key={key}/>
        ])}
        </div>
        {children}
    </div>
}

function Availability({availability}: {availability: boolean}){
    return <div style={{padding:"2pt"}}>
        availability {availability?"yes":"no"}
    </div>
}

type SyncRootArgs = UseSyncRootArgs & {login: Login}
function SyncRoot(prop: SyncRootArgs){
    const { enqueue, children, availability, ack, failure } = useSyncRoot(prop)
    const { 
        createNode, sessionKey, branchKey, isRoot, win, login 
    } = prop
    const branchContextValue = useMemo(()=>({
        createNode, sessionKey, branchKey, isRoot, win, login, enqueue
    }),[createNode, sessionKey, branchKey, isRoot, win, login, enqueue])
    //console.log("ve",isValidElement(children),children)
    return <StrictMode>
        <BranchContext.Provider value={branchContextValue}>
            <AckContext.Provider value={ack}>
                {isRoot ? <Availability key="availability" availability={availability}/> : ""}
                {failure ? <div>VIEW FAILED: {failure}</div> : ""}
                {children}
            </AckContext.Provider>
        </BranchContext.Provider>
    </StrictMode>
}

function App({createNode,win}:{ createNode: CreateNode, win: Window }){
    const {result,failure} = useSessionManager(win)
    return result ? <SyncRoot {...{createNode,...result}}/> : failure ? `SESSION INIT FAILED: ${failure}` : ""
}


/*
const splitFirst = (value: string): [string,string] = {}
// todo provide receivers
const [k,v] = splitFirst(value)
            (receivers[k]||[]).forEach(r => r(v)) 
*/
type BranchContext = {
    createNode: CreateNode, sessionKey: string, 
    branchKey: string, enqueue: EnqueuePatch, isRoot: boolean, win: Window, login: Login 
}
const BranchContext = createContext<BranchContext|undefined>(undefined)
BranchContext.displayName = "BranchContext"
const useSender = () => useContext(BranchContext) ?? assertNever("no BranchContext")

const useSync = UseSyncMod(useSender)
const messageReceiver = (value: string) => console.trace(value)
const toAlienMessageComponents = ToAlienMessageComponents({messageReceiver,useSender})
const locationComponents = LocationComponents({useSender,useSync})

type NativeElementProps = ObjS<unknown> & {tp:string}
const NativeElement = ({tp,identity,branchContext,...at}:NativeElementProps) => createElement(tp, at)
export const main = ({win, canvasFactory}: {win: Window, canvasFactory: CanvasFactory }) => {
    const useCanvas: UseCanvas = UseCanvas({canvasFactory,useSender,useSync})
    const ExampleCanvas = ExampleCanvasMod(useCanvas)
    const typeTransforms: ObjS<React.FC<any>|string> = {
        span: NativeElement, ...locationComponents, ...toAlienMessageComponents,
        ExampleLogin, ExampleMenu, ExampleTodoTaskList, TestSessionList, ExampleCanvas, ExampleReverting, ExampleReplicaList
    }
    const createNode: CreateNode = at => {
        //console.log("tp",at.tp)
        const constr = typeTransforms[asString(at["tp"])]
        return constr ? createElement(constr, at) : at
    }
    const [root, unmount] = doCreateRoot(win.document.body)
    root.render(<App createNode={createNode} win={win}/>)
}
