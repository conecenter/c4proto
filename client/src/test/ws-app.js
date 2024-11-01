// @ts-check
import {createElement,useState,useCallback,useContext,createContext,useMemo} from "react"
import {useSession,login} from "../main/session.js"
import {doCreateRoot,IsolatedFrame} from "../main/frames.js"
import {useSyncRoot,useSyncSimple} from "../main/sync.js"

const DIContext = createContext()
const AvailabilityContext = createContext()

function ExampleInput({value: incomingValue, identity}){
    const {value, enqueueValue, patches} = useSyncSimple(incomingValue, identity)
    const onChange = useCallback(ev => enqueueValue(ev.target.value), [enqueueValue])
    const changing = patches.length > 0 ? "1" : undefined
    const backgroundColor = changing ? "yellow" : "white"
    return createElement("input", {value,onChange,style:{backgroundColor}})
}

function ExampleFrame({branchKey}){
    const {transforms, sessionKey} = useContext(DIContext)
    const children = [createElement(SyncRoot, {sessionKey, branchKey, reloadBranchKey: null, isRoot: false, transforms, children: []})]
    return createElement(IsolatedFrame, {children})
}

function Login({setSessionKey}){
    const [user, setUser] = useState()
    const [pass, setPass] = useState()
    const [error, setError] = useState(false)
    const onClick = useCallback(ev => {
        setSessionKey(null)
        setError(false)
        login(user, pass).then(setSessionKey, err=>setError(true))
    }, [login,user,pass])
    return createElement("div", {
        children: [
            "Username ",
            createElement("input", {value: user, onChange: setUser, type:"text"}),
            ", password ",
            createElement("input", {value: pass, onChange: setPass, type:"password"}),
            " ",
            createElement("input", {type:"button", onClick, value: "sign in"}),
            error ? " FAILED" : ""
        ]
    })
}

function Availability(){
    const {availability} = useContext(AvailabilityContext)
    return createElement("div", {children: [`availability ${availability}`]})
}

function FailureElement({value}){
    return createElement("div", {children: [`VIEW FAILED: ${value}`]})
}

function SyncRoot({sessionKey, branchKey, reloadBranchKey, isRoot, transforms, children: addChildren}){
    const {children, availability} = useSyncRoot({sessionKey, branchKey, reloadBranchKey, isRoot, transforms})
    const provided = useMemo(()=>({transforms,sessionKey}),[transforms,sessionKey])
    return createElement(DIContext.Provider, {value: provided, children: [
        createElement(AvailabilityContext.Provider, {value: availability, children: [
            ...addChildren,...children
        ]})
    ]})
}

function App({transforms,win}){
    const {sessionKey, setSessionKey, branchKey, reloadBranchKey} = useSession(win)
    const children = [createElement(Availability, {}), branchKey ? "" : createElement(Login,{setSessionKey})]
    return createElement(SyncRoot, {sessionKey, branchKey, reloadBranchKey, isRoot: true, transforms, children})
}

(()=>{
    const transforms = {FailureElement,ExampleInput,ExampleFrame}
    doCreateRoot(document.body, [createElement(App,{transforms, win: window})])
})()
