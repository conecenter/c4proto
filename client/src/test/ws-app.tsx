
import React from "react"
import {StrictMode} from "react"
import {useState,useCallback,useMemo,useEffect,isValidElement,createElement} from "../main/react"
import {assertNever, CreateNode, Identity, identityAt, ObjS} from "../main/util"
import {useSession,login} from "../main/session"
import {doCreateRoot,useIsolatedFrame} from "../main/frames"
import {initSyncRootState, SyncRootState} from "../main/sync-root"
import {Canvas,CanvasAppContext,CanvasFactory} from "../main/canvas-manager"
import { AckContext, ABranchContext, LocationElement, useSync, patchFromValue, useSender, mergeSimple } from "../main/sync-hooks"

const sizesChangeIdOf = identityAt('sizesChange')
type ExampleFigure = {offset: number, identity: Identity}
function ExampleCanvas({appContext,sizesValue,identity,figures}:{appContext: AppContext,sizesValue: string, identity: Identity, figures: ExampleFigure[]}){
    
    const cmd = (...args: unknown[]) => [args.slice(0,-1),args.at(-1)]
    const rect = (x: number, y: number, w: number, h: number) => [
        ...cmd(x,y,"moveTo"), ...cmd(x+w,y,"lineTo"), ...cmd(x+w,y+h,"lineTo"), ...cmd(x,y+h,"lineTo"), ...cmd(x,y,"lineTo"),
    ]


    const figure = ({offset, identity}:ExampleFigure) => ({
        identity,
        commands: [
            ...cmd("setMainContext"),
            ...cmd("save"),
            //...cmd(0,50,"translate"),
            ...cmd(0.1,"rotate"),
            ...cmd("applyPath",[
                ...rect(10+offset,20,30,40),
                ...cmd("closePath"),
            ],"definePath"),
            ...cmd("preparingCtx",[
                ...cmd("beginPath"),
                ...cmd("applyPath"),
                ...cmd("fillStyle", "rgb(255,0,0)", "set"),
                ...cmd("strokeStyle", "#c9c4c3", "set"),
                ...cmd("fill"),
                ...cmd("stroke"),
            ],"inContext"),
            ...cmd("reactiveCtx",[
                ...cmd("beginPath"),
                ...cmd("applyPath"),
                ...cmd("fillStyle", "[colorPH]", "set"),
                ...cmd("strokeStyle", "[colorPH]", "set"),
                ...cmd("fill"),
                ...cmd("stroke"),                
            ],"inContext"),
        ],
        children: [],
        commandsFinally: [
            ...cmd("setMainContext"),
            ...cmd("restore"),
        ],
    })
    const cProps = {
        appContext,
        value: sizesValue, identity: sizesChangeIdOf(identity), style: {height:"100vh"},
        width: 100, height: 100, options: {noOverlay:false}, zoomSteps: 4096, minCmdUnitsPerEMZoom: 0, initialFit: "xy", isGreedy: true,
        commands: [], children: figures.map(figure), commandsFinally: [],
    }
    //commands, commandsFinally, children
    return <Canvas {...cProps}/>
}
/*



    path(key,
      Rect(10+offset,20,30,40),
      GotoClick(key),
      FillStyle("rgb(255,0,0)"), StrokeStyle("#000000"),
      path("3",
        Translate(0,50), Rotate(0.1),
        path("3",Rect(0,0,20,20),FillStyle("rgb(0,0,0)"))
      ),
      path("4")
    )
  CanvasToJson.appendCanvasJson, PathFactory

}*/


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
    const {sessionKey} = useSender()
    const {ref,...props} = useIsolatedFrame(body => {
        const win = body.ownerDocument.defaultView ?? assertNever("no window")
        return <SyncRoot {...{appContext,sessionKey,branchKey,reloadBranchKey:noReloadBranchKey,isRoot:false,win}}/>
    })
    return <iframe {...props} {...{style}} ref={ref} />
}

function Login({win,setSessionKey} : {win: Window, setSessionKey: (sessionKey?: string)=>void}){
    const [user, setUser] = useState("")
    const [pass, setPass] = useState("")
    const [error, setError] = useState(false)
    const onClick = useCallback(() => {
        setSessionKey(undefined)
        setError(false)
        login(win, user, pass).then(setSessionKey, err=>setError(true))
    }, [win,login,user,pass])
    return <div>
        Username <input type="text" value={user} onChange={ev=>setUser(ev.target.value)}/>,
        password <input type="password" value={pass} onChange={ev=>setPass(ev.target.value)}/>&nbsp;
        <input type="button" value="sign in" onClick={onClick}/>
        {error ? " FAILED" : ""}
    </div>
}

function Menu({availability}: {availability: boolean}){
    return <div>
        availability {availability?"yes":"no"} | 
        <a href="#todo">todo-list</a> | 
        <a href="#leader">coworking</a> | 
        <a href="#rectangle">canvas</a> | 
    </div>
}

type PreLoginBranchContext = { appContext: AppContext, win: Window }
type PreSyncBranchContext = 
     PreLoginBranchContext & { sessionKey: string, isRoot: boolean, branchKey: string, reloadBranchKey: ()=>void }

function SyncRoot(prop: PreSyncBranchContext){
    const { appContext, isRoot, sessionKey, branchKey, win, reloadBranchKey } = prop
    const {createNode} = appContext
    const [{manager, children, availability, ack, failure}, setState] = useState<SyncRootState>(initSyncRootState)
    const {start, enqueue, stop} = manager
    useEffect(()=>{
        start({createNode, setState, isRoot, sessionKey, branchKey, win, reloadBranchKey})
        return () => stop()
    }, [start, createNode, setState, isRoot, sessionKey, branchKey, win, reloadBranchKey, stop])
    const branchContextValue = useMemo(()=>({
        branchKey, sessionKey, enqueue, isRoot, win
    }),[
        branchKey, sessionKey, enqueue, isRoot, win
    ])
    return <StrictMode>
        <ABranchContext.Provider value={branchContextValue}>
            <AckContext.Provider value={ack}>
                <Menu key="menu" availability={availability}/>, 
                {failure ? <div>VIEW FAILED: {failure}</div> : ""}
                {isValidElement(children) ? children : []}
            </AckContext.Provider>
        </ABranchContext.Provider>
    </StrictMode>
}

function App({appContext,win}:PreLoginBranchContext){
    const {sessionKey, setSessionKey, branchKey, reloadBranchKey} = useSession(win)
    return [
        sessionKey && branchKey ? <SyncRoot {...{
            appContext, sessionKey, branchKey, reloadBranchKey, isRoot: true, win
        }} key={branchKey}/> : 
        !sessionKey ? <Login {...{win,setSessionKey}} key="login"/> : ""
    ]
}

type SyncAppContext = { createNode: CreateNode }
type AppContext = CanvasAppContext & SyncAppContext

export const main = ({win, canvasFactory}: {win: Window, canvasFactory: CanvasFactory }) => {
    const typeTransforms: ObjS<React.FC<any>|string> = {
        span: "span", LocationElement, 
        ExampleTodoTaskList, TestSessionList, ExampleCanvas
    }
    const createNode: CreateNode = ({tp,...at}, childAt) => {
        return typeTransforms[tp] ? 
            createElement(typeTransforms[tp], {appContext, ...at, ...childAt}) : {tp, ...at, ...childAt}
    }
    const appContext = {createNode, canvasFactory}
    doCreateRoot(win.document.body, <App appContext={appContext} win={win}/>)
}
