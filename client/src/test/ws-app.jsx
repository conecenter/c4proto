// @ts-check
import React from "react"
import {useState,useCallback,useContext,createContext,useMemo} from "../main/hooks.js"
import {createRoot} from "react-dom/client"
import {useSession,login} from "../main/session.js"
import {doCreateRoot,useIsolatedFrame} from "../main/frames.js"
import {useSyncRoot,useSyncSimple,useLocation} from "../main/sync.js"
import {identityAt} from "../main/util.js"

const commentsChangeIdOf = identityAt('commentsChange')
const removeIdOf = identityAt('remove')
const commentsFilterChangeIdOf = identityAt('commentsFilterChange')
const addIdOf = identityAt('add')
function ExampleTodoTask({commentsValue,identity}){
    return <tr>
        <td key="comment"><ExampleInput value={commentsValue} identity={commentsChangeIdOf(identity)}/></td>
        <td key="remove"><ExampleButton caption="x" identity={removeIdOf(identity)}/></td>
    </tr>
}
function ExampleTodoTaskList({commentsFilterValue,tasks,identity}){
    return <table style={{border: "1px solid silver"}}><tbody>
        <tr>
            <td key="comment">Comments contain <ExampleInput value={commentsFilterValue} identity={commentsFilterChangeIdOf(identity)}/></td>
            <td key="add"><ExampleButton caption="+" identity={addIdOf(identity)}/></td>
        </tr>
        <tr><th>Comments</th></tr>
        {tasks}
    </tbody></table>
}

const DIContext = createContext()
const AvailabilityContext = createContext()

function ExampleButton({caption, identity}){
    const {setValue, patches} = useSyncSimple("", identity)
    const onClick = useCallback(ev => setValue("1"), [setValue])
    const changing = patches.length > 0
    const backgroundColor = changing ? "yellow" : "white"
    return <input type="button" value={caption} onClick={onClick} style={{backgroundColor}} />
}

function ExampleInput({value: incomingValue, identity}){
    const {value, setValue, patches} = useSyncSimple(incomingValue, identity)
    const onChange = useCallback(ev => setValue(ev.target.value), [setValue])
    const changing = patches.length > 0
    const backgroundColor = changing ? "yellow" : "white"
    return <input type="text" value={value} onChange={onChange} style={{backgroundColor}} />
}

function ExampleFrame({branchKey}){
    const {transforms, sessionKey} = useContext(DIContext)
    const child = <SyncRoot {...{sessionKey,branchKey,reloadBranchKey:null,isRoot:false,transforms,children:[]}}/>
    const [props, ref] = useIsolatedFrame(createRoot, [child])
    return <iframe {...props} ref={ref} />
}

function Login({setSessionKey}){
    const [user, setUser] = useState("")
    const [pass, setPass] = useState("")
    const [error, setError] = useState(false)
    const onClick = useCallback(ev => {
        setSessionKey(null)
        setError(false)
        login(user, pass).then(setSessionKey, err=>setError(true))
    }, [login,user,pass])
    return <div>
        Username <input type="text" value={user} onChange={ev=>setUser(ev.target.value)}/>,
        password <input type="password" value={pass} onChange={ev=>setPass(ev.target.value)}/>&nbsp;
        <input type="button" value="sign in" onClick={onClick}/>
        {error ? " FAILED" : ""}
    </div>
}

function Availability(){
    const {availability} = useContext(AvailabilityContext)
    console.log("2)"+availability)
    return <div>availability {availability}</div>
}

function RootElement({location, identity, failure, children}){
    const {availability} = useContext(AvailabilityContext)
    console.log("3)"+availability)
    useLocation({location, identity})
    return [...(children??[]), failure ? <div>VIEW FAILED: {failure}</div> : ""]
}

function SyncRoot({sessionKey, branchKey, reloadBranchKey, isRoot, transforms, children: addChildren}){
    const {children, availability} = useSyncRoot({sessionKey, branchKey, reloadBranchKey, isRoot, transforms})
    const provided = useMemo(()=>({transforms,sessionKey}),[transforms,sessionKey])
    console.log("1)"+availability)
    console.log("ch "+children.length)
    return <DIContext.Provider value={provided}>
        <AvailabilityContext.Provider value={availability}>
            {...addChildren}{...children}
        </AvailabilityContext.Provider>
    </DIContext.Provider>
}

function App({transforms,win}){
    const {sessionKey, setSessionKey, branchKey, reloadBranchKey} = useSession(win)
    const children = [<Availability/>, branchKey ? "" : <Login {...{setSessionKey}}/>]
    return <SyncRoot {...{sessionKey, branchKey, reloadBranchKey, isRoot: true, transforms, children}}/>
}

(()=>{
    const transforms = {tp:{RootElement,ExampleTodoTaskList,ExampleTodoTask,ExampleInput,ExampleFrame}}
    doCreateRoot(createRoot, document.body, <App transforms={transforms} win={window}/>)
})()
