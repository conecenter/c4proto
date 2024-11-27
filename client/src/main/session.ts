
import {useState,useEffect} from "./react"
import {manageEventListener,SetState,getKey,asObject,asString, assertNever, weakCache} from "./util"

export const login = (win: Window, user: string, pass: string): Promise<string> => (
    win.fetch("/auth/check",{ method: "POST", body: `${user}\n${pass}` })
    .then(r => r.json()).then(rj => asString(getKey(asObject(rj), "sessionKey")) || assertNever("no sessionKey"))
)

const stateKey = "c4sessionKey"
const useSessionRestoreOnRefresh = (
    {win, sessionKey, setSessionKey}:
    {win: Window, sessionKey: string|undefined, setSessionKey: SetState<string|undefined>}
) => {
    useEffect(()=>{
        if(!win) return undefined
        const sessionKey = win.sessionStorage.getItem(stateKey)
        win.sessionStorage.removeItem(stateKey)
        setSessionKey(was => was ?? (sessionKey||""))
    }, [win, setSessionKey])
    useEffect(()=>{
        return manageEventListener(win, "beforeunload", ()=>{
            sessionKey && win.sessionStorage.setItem(stateKey, sessionKey)
        })
    }, [win, sessionKey])
}

const sessionBranchManagers = weakCache((setState: SetState<SessionState>) => {
    const setSessionKey: SetState<string|undefined> = f => setState(was => {
        const sessionKey = f(was.sessionKey) ?? assertNever("no sessionKey")
        return was.sessionKey === sessionKey ? was : {sessionKey}
    })
    const reloadBranchKeyInner = (sessionKey: string|undefined, win: Window) =>{
        const fin = (resp: {branchKey?:string, error?:string}) => setState(was => (
            resp?.branchKey && !was.branchKey && was.sessionKey === sessionKey ? 
                {...was,branchKey:resp?.branchKey} : resp?.error || !was.branchKey ? {sessionKey:""} : was
        ))
        sessionKey && win.fetch("/auth/branch",{method: "POST", headers: {"x-r-session":sessionKey}}).then(r => r.json())
            .then(rj => fin(asObject(rj)), error => fin({}))
    }
    const reloadBranchKey = () => setState(was => ({...was, reloadBranchCounter: (was.reloadBranchCounter??0)+1}))
    return {setSessionKey,reloadBranchKey,reloadBranchKeyInner}
})
type SessionState = { 
    sessionKey?: string, // undefined is after refresh, and "" means "need to login"
    branchKey?: string, reloadBranchCounter?: number 
}

export const useSession = (win: Window) => {
    const [{sessionKey,branchKey,reloadBranchCounter}, setState] = useState<SessionState>({})
    const {setSessionKey,reloadBranchKey,reloadBranchKeyInner} = sessionBranchManagers(setState)
    useEffect(() => reloadBranchKeyInner(sessionKey,win), [reloadBranchKeyInner,sessionKey,win,reloadBranchCounter])
    useSessionRestoreOnRefresh({win, sessionKey, setSessionKey})
    return {sessionKey, setSessionKey, branchKey, reloadBranchKey}
}
