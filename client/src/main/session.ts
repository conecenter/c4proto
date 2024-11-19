
import {useState,useMemo,useEffect} from "./hooks"
import {manageEventListener,SetState,getKey,asObject,asString} from "./util"

export const login = (win: Window, user: string, pass: string): Promise<string> => (
    win.fetch("/auth/check",{ method: "POST", body: `${user}\n${pass}` })
    .then(r => r.json()).then(rj => asString(getKey(asObject(rj), "sessionKey")))
)

const stateKey = "c4sessionKey"
const useSessionRestoreOnRefresh = (
    {win, sessionKey, setSessionKey}:
    {win: Window, sessionKey: string|undefined, setSessionKey: (key: string) => void}
) => {
    useEffect(()=>{
        if(!win) return undefined
        const sessionKey = win.sessionStorage.getItem(stateKey)
        win.sessionStorage.removeItem(stateKey)
        sessionKey && setSessionKey(sessionKey)
    }, [win, setSessionKey])
    useEffect(()=>{
        return manageEventListener(win, "beforeunload", ()=>{
            sessionKey && win.sessionStorage.setItem(stateKey, sessionKey)
        })
    }, [win, sessionKey])
}

const SessionBranchManager = (win: Window, setState: SetState<SessionState>) => {
    const setSessionKey = (sessionKey?: string) => setState(was => was.sessionKey === sessionKey ? was : {sessionKey})
    const reloadBranchKeyInner = (sessionKey?: string) =>{
        const fin = (resp: {branchKey?:string, error?:string}) => setState(was => (
            resp?.branchKey && !was.branchKey && was.sessionKey === sessionKey ? 
                {...was,branchKey:resp?.branchKey} : resp?.error || !was.branchKey ? {sessionKey:undefined} : was
        ))
        sessionKey && win.fetch("/auth/branch",{method: "POST", headers: {"x-r-session":sessionKey}}).then(r => r.json())
            .then(rj => fin(asObject(rj)), error => fin({}))
    }
    const reloadBranchKey = () => setState(was => ({...was, reloadBranchCounter: (was.reloadBranchCounter??0)+1}))
    return {setSessionKey,reloadBranchKey,reloadBranchKeyInner}
}
type SessionState = { sessionKey: string|undefined, branchKey?: string, reloadBranchCounter?: number }

export const useSession = (win: Window) => {
    const [{sessionKey,branchKey,reloadBranchCounter}, setState] = useState<SessionState>({sessionKey:undefined})
    const {setSessionKey,reloadBranchKey,reloadBranchKeyInner} = useMemo(()=>SessionBranchManager(win, setState), [win, setState])
    useEffect(() => reloadBranchKeyInner(sessionKey), [reloadBranchKeyInner,sessionKey,reloadBranchCounter])
    useSessionRestoreOnRefresh({win, sessionKey, setSessionKey})
    return {sessionKey, setSessionKey, branchKey, reloadBranchKey}
}
