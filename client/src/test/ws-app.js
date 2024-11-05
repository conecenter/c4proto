// @ts-check
import {createElement,useState,useCallback,useContext,createContext,useMemo} from "react"
import {useSession,login} from "../main/session.js"
import {doCreateRoot,IsolatedFrame} from "../main/frames.js"
import {useSyncRoot,useSyncSimple} from "../main/sync.js"

const commentsChangeIdOf = identityAt('commentsChange')
const removeIdOf = identityAt('remove')
const commentsFilterChangeIdOf = identityAt('commentsFilterChange')
const addIdOf = identityAt('add')
function ExampleTodoTask({commentsValue,identity}){
    return createElement("tr", {
        children: [
            createElement("td", { children: [
                createElement(ExampleInput, {value: commentsValue, identity: commentsChangeIdOf(identity)})
            ] }),
            createElement("td", { children: [
                createElement(ExampleButton, {caption: "x", identity: removeChangeIdOf(identity)})
            ] }),
        ]
    })
}
function ExampleTodoTasks({commentsFilterValue,tasks}){
    return createElement("div", {
        children: [
            createElement("table", {
                style: { border: "1px solid silver" },
                children: [
                    createElement("tr", {
                        children: [
                            createElement("td", { children: [
                                "Comments contain ",
                                createElement(ExampleInput, {
                                    value: commentsFilterValue, identity: commentsFilterChangeIdOf(identity)
                                })
                            ] }),
                            createElement("td", { children: [
                                createElement(ExampleButton, {caption: "+", identity: addChangeIdOf(identity)})
                            ] }),
                        ]
                    }),
                    createElement("tr", {
                        children: [
                            createElement("th", { children: ["Comments"] })
                        ]
                    }),
                    ...tasks
                ]
            })
        ]
    })
}


const DIContext = createContext()
const AvailabilityContext = createContext()

function ExampleButton({caption, identity}){
    const {enqueueValue, patches} = useSyncSimple(incomingValue, identity)
    const onClick = useCallback(ev => enqueueValue("1"), [enqueueValue])
    const changing = patches.length > 0
    const backgroundColor = changing ? "yellow" : "white"
    return createElement("input", {type:"button",value:caption,onClick,style:{backgroundColor}})
}

function ExampleInput({value: incomingValue, identity}){
    const {value, enqueueValue, patches} = useSyncSimple(incomingValue, identity)
    const onChange = useCallback(ev => enqueueValue(ev.target.value), [enqueueValue])
    const changing = patches.length > 0
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
