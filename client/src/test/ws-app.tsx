
import React from "react"
import {StrictMode} from "react"
import {useState,useCallback,useMemo,useEffect,isValidElement,createElement} from "../main/react"
import {assertNever, CreateNode, Identity, identityAt, ObjS, SetState} from "../main/util"
import {useSession,login} from "../main/session"
import {doCreateRoot,useIsolatedFrame} from "../main/frames"
import {initSyncRootState, SyncRootState} from "../main/sync-root"
import {CanvasAppContext, CanvasFactory, useCanvas} from "../main/canvas-manager"
import { AckContext, ABranchContext, LocationElement, useSync, patchFromValue, useSender, mergeSimple } from "../main/sync-hooks"

const activateIdOf = identityAt("activate")
const sizesChangeIdOf = identityAt('sizesChange')
type ExampleFigure = {offset: number, identity: Identity, isActive: boolean}
function ExampleCanvas({appContext,sizesValue,identity,figures}:{appContext: AppContext,sizesValue: string, identity: Identity, figures: ExampleFigure[]}){ 
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
    const options = useMemo(()=>({noOverlay:false}),[])
    const cProps = {
        appContext, parentNode,
        value: sizesValue, identity: sizesChangeIdOf(identity), style: {height:"100vh"},
        width: 100, height: 100, options, zoomSteps: 4096, minCmdUnitsPerEMZoom: 0, initialFit: "xy", isGreedy: true,
        commands: [], children: figures.map(figure), commandsFinally: [],
    }
    const style = useCanvas(cProps)
    return createElement("div",{ style, ref },[])
}


function TestSessionList({appContext,sessions}:{appContext:AppContext,sessions:{key:string,branchKey:string,userName:string,isOnline:boolean}[]}){
    return <div>{sessions.map(({key,branchKey,userName,isOnline})=>(
        <div key={key}>
            User: {userName} {isOnline ? "online" : "offline"}<br/>
            <ExampleFrame key="frame" {...{appContext,branchKey,style:{width:"30%",height:"400px"}}} />
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
function ExampleFrame({appContext,branchKey,style}:{appContext:AppContext,branchKey:string,style:{[K:string]:string}}){
    const {sessionKey,setSessionKey} = useSender()
    const {ref,...props} = useIsolatedFrame(body => {
        const win = body.ownerDocument.defaultView ?? assertNever("no window")
        return <SyncRoot {...{appContext,sessionKey,setSessionKey,branchKey,reloadBranchKey:noReloadBranchKey,isRoot:false,win}}/>
    })
    return <iframe {...props} {...{style}} ref={ref} />
}

function Login({win,setSessionKey} : {win: Window, setSessionKey: SetState<string|undefined>}){
    const [user, setUser] = useState("")
    const [pass, setPass] = useState("")
    const [error, setError] = useState(false)
    const onClick = useCallback(() => {
        setError(false)
        login(win, user, pass).then(sessionKey => setSessionKey(was=>sessionKey), err=>setError(true))
    }, [win,login,user,pass])
    return <div>
        Username <input type="text" value={user} onChange={ev=>setUser(ev.target.value)}/>,
        password <input type="password" value={pass} onChange={ev=>setPass(ev.target.value)}/>&nbsp;
        <input type="button" value="sign in" onClick={onClick}/>
        {error ? " FAILED" : ""}
    </div>
}

function Menu({availability,setSessionKey}: {availability: boolean, setSessionKey: SetState<string|undefined>}){
    return <div style={{padding:"2pt"}}>
        availability {availability?"yes":"no"} | 
        <a href="#todo">todo-list</a> | 
        <a href="#leader">coworking</a> | 
        <a href="#rectangle">canvas</a> | 
        <input type="button" value="logout" onClick={ev=>setSessionKey(was=>"")}/>
    </div>
}

type PreLoginBranchContext = { appContext: AppContext, win: Window }
type PreSyncBranchContext = PreLoginBranchContext & { 
    sessionKey: string, setSessionKey: SetState<string|undefined>, 
    isRoot: boolean, branchKey: string, reloadBranchKey: ()=>void 
}

function SyncRoot(prop: PreSyncBranchContext){
    const { appContext, isRoot, sessionKey, setSessionKey, branchKey, win, reloadBranchKey } = prop
    const {createNode} = appContext
    const [{manager, children, availability, ack, failure}, setState] = useState<SyncRootState>(initSyncRootState)
    const {start, enqueue, stop} = manager
    useEffect(()=>{
        start({createNode, setState, isRoot, sessionKey, branchKey, win, reloadBranchKey})
        return () => stop()
    }, [start, createNode, setState, isRoot, sessionKey, branchKey, win, reloadBranchKey, stop])
    const branchContextValue = useMemo(()=>({
        branchKey, sessionKey, setSessionKey, enqueue, isRoot, win
    }),[
        branchKey, sessionKey, setSessionKey, enqueue, isRoot, win
    ])
    //console.log("ve",isValidElement(children),children)
    return <StrictMode>
        <ABranchContext.Provider value={branchContextValue}>
            <AckContext.Provider value={ack}>
                {isRoot ? <Menu key="menu" availability={availability} setSessionKey={setSessionKey}/> : ""}
                {failure ? <div>VIEW FAILED: {failure}</div> : ""}
                {children}
            </AckContext.Provider>
        </ABranchContext.Provider>
    </StrictMode>
}

function App({appContext,win}:PreLoginBranchContext){
    const {sessionKey, setSessionKey, branchKey, reloadBranchKey} = useSession(win)
    return [
        sessionKey && branchKey ? <SyncRoot {...{
            appContext, sessionKey, setSessionKey, branchKey, reloadBranchKey, isRoot: true, win
        }} key={branchKey}/> : 
        sessionKey === "" ? <Login {...{win,setSessionKey}} key="login"/> : ""
    ]
}

type SyncAppContext = { createNode: CreateNode }
type AppContext = CanvasAppContext & SyncAppContext

export const main = ({win, canvasFactory}: {win: Window, canvasFactory: CanvasFactory }) => {
    const typeTransforms: ObjS<React.FC<any>|string> = {
        span: "span", LocationElement, 
        ExampleTodoTaskList, TestSessionList, ExampleCanvas
    }
    const createNode: CreateNode = at => {
        //console.log("tp",at.tp)
        const constr = typeTransforms[at.tp]
        if(!constr) return at
        if(typeof constr !== "string") return createElement(constr, {appContext,...at})
        const {tp,...leftAt} = at
        return createElement(constr, leftAt)
    }
    const appContext = {createNode, canvasFactory}
    doCreateRoot(win.document.body, <App appContext={appContext} win={win}/>)
}
