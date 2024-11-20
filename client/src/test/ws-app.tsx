
import React from "react"
import {StrictMode} from "react"
import {useState,useCallback,useContext,createContext,useMemo} from "../main/hooks"
import {useSession,login} from "../main/session"
import {doCreateRoot,useIsolatedFrame} from "../main/frames"
import {useSyncRoot,useSyncSimple,useLocation,identityAt,Transforms,Identity} from "../main/sync"


function TestSession({branchKey,userName,isOnline}:{branchKey:string,userName:string,isOnline:boolean}){
    return <div>
        User: {userName} {isOnline ? "online" : "offline"}<br/>
        <ExampleFrame {...{branchKey,style:{width:"30%",height:"400px"}}} />
    </div>
}
function TestSessionList({sessions}:{sessions:React.ReactElement[]}){
    return <div>{sessions}</div>
}


const commentsChangeIdOf = identityAt('commentsChange')
const removeIdOf = identityAt('remove')
const commentsFilterChangeIdOf = identityAt('commentsFilterChange')
const addIdOf = identityAt('add')
function ExampleTodoTask({commentsValue,identity}:{commentsValue:string,identity:Identity}){
    return <tr>
        <td key="comment"><ExampleInput value={commentsValue} identity={commentsChangeIdOf(identity)}/></td>
        <td key="remove"><ExampleButton caption="x" identity={removeIdOf(identity)}/></td>
    </tr>
}
function ExampleTodoTaskList(
    {commentsFilterValue,tasks,identity}:
    {commentsFilterValue:string,tasks:React.ReactElement[],identity:Identity}
){
    return <table style={{border: "1px solid silver"}}><tbody>
        <tr>
            <td key="comment">Comments contain <ExampleInput value={commentsFilterValue} identity={commentsFilterChangeIdOf(identity)}/></td>
            <td key="add"><ExampleButton caption="+" identity={addIdOf(identity)}/></td>
        </tr>
        <tr><th>Comments</th></tr>
        {tasks}
    </tbody></table>
}

const DIContext = createContext<{transforms:Transforms,sessionKey?:string}>({transforms:{tp:{}}})
const AvailabilityContext = createContext(false)

function ExampleButton({caption, identity}:{caption:string,identity:Identity}){
    const {setValue, patches} = useSyncSimple("", identity)
    const onClick = useCallback(() => setValue("1"), [setValue])
    const changing = patches.length > 0
    const backgroundColor = changing ? "yellow" : "white"
    return <input type="button" value={caption} onClick={onClick} style={{backgroundColor}} />
}

function ExampleInput({value: incomingValue, identity}:{value: string, identity: Identity}){
    const {value, setValue, patches} = useSyncSimple(incomingValue, identity)
    const onChange = useCallback((ev: React.ChangeEvent<HTMLInputElement>) => setValue(ev?.target.value), [setValue])
    const changing = patches.length > 0
    const backgroundColor = changing ? "yellow" : "white"
    return <input type="text" value={value} onChange={onChange} style={{backgroundColor}} />
}

const noReloadBranchKey = ()=>{}
function ExampleFrame({branchKey,style}:{branchKey:string,style:{[K:string]:string}}){
    const {transforms, sessionKey} = useContext(DIContext)
    const child = sessionKey && <SyncRoot {...{sessionKey,branchKey,reloadBranchKey:noReloadBranchKey,isRoot:false,transforms,children:[]}}/>
    const {ref,...props} = useIsolatedFrame([child])
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

function Availability(){
    const availability = useContext(AvailabilityContext)
    return <div>availability {availability?"yes":"no"}</div>
}

function RootElement(
    {location, identity, failure, children}:
    {location: string, identity: Identity, failure: string, children: React.ReactElement[]}
){
    useLocation({location, identity})
    return [...(children??[]), failure ? <div>VIEW FAILED: {failure}</div> : ""]
}

function SyncRoot(
    {sessionKey, branchKey, reloadBranchKey, isRoot, transforms, children: addChildren}:
    {sessionKey: string, branchKey: string, reloadBranchKey: ()=>void, isRoot: boolean, transforms: Transforms, children: React.ReactElement[]}
){
    const {children, availability} = useSyncRoot({sessionKey, branchKey, reloadBranchKey, isRoot, transforms})
    const provided = useMemo(()=>({transforms,sessionKey}),[transforms,sessionKey])
    return <DIContext.Provider value={provided}>
        <AvailabilityContext.Provider value={availability}>
            {...addChildren}{...children}
        </AvailabilityContext.Provider>
    </DIContext.Provider>
}

function App({transforms,win}:{transforms:Transforms,win:Window}){
    const {sessionKey, setSessionKey, branchKey, reloadBranchKey} = useSession(win)
    return [
        sessionKey && branchKey ? <SyncRoot {...{
            sessionKey, branchKey, reloadBranchKey, isRoot: true, transforms, children: [<Availability/>] 
        }} key={branchKey}/> : 
        !sessionKey ? <Login {...{win,setSessionKey}} key="login"/> : ""
    ]
}


declare var window: Window
(()=>{
    const transforms = {tp:{RootElement,ExampleTodoTaskList,ExampleTodoTask,ExampleInput,ExampleFrame,Availability,TestSessionList,TestSession}}
    doCreateRoot(window.document.body, <StrictMode><App transforms={transforms} win={window}/></StrictMode>)
})()
