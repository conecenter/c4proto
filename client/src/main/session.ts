// @ts-check

import {useState,useMemo,useEffect} from "./hooks"
import {manageEventListener} from "./util"

const never = cause => { throw new Error(cause) }
export const login = (user, pass) => fetch("/auth/check",{ method: "POST", body: `${user}\n${pass}` })
    .then(r => r.json()).then(rj => rj.sessionKey || never("failed"))

const stateKey = "c4sessionKey"
const useSessionRestoreOnRefresh = ({win, sessionKey, setSessionKey}) => {
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

const SessionBranchManager = setState => {
    const setSessionKey = sessionKey => setState(was => was.sessionKey === sessionKey ? was : {sessionKey})
    const reloadBranchKeyInner = sessionKey =>{
        const fin = resp => setState(was => (
            resp?.branchKey && !was.branchKey && was.sessionKey === sessionKey ? 
                {...was,branchKey:resp?.branchKey} : resp?.error || !was.branchKey ? {} : was
        ))
        sessionKey && fetch("/auth/branch",{method: "POST", headers: {"x-r-session":sessionKey}}).then(r => r.json())
            .then(rj => fin(rj), error => fin(null))
    }
    const reloadBranchKey = () => setState(was => ({...was, reloadBranchCounter: was.reloadBranchCounter+1}))
    return {setSessionKey,reloadBranchKey,reloadBranchKeyInner}
}

export const useSession = win => {
    const [{sessionKey,branchKey,reloadBranchCounter}, setState] = useState({sessionKey:null,branchKey:null,reloadBranchCounter:0})
    const {setSessionKey,reloadBranchKey,reloadBranchKeyInner} = useMemo(()=>SessionBranchManager(setState), [setState])
    useEffect(()=>reloadBranchKeyInner(sessionKey), [reloadBranchKeyInner,sessionKey,reloadBranchCounter])
    useSessionRestoreOnRefresh({win, sessionKey, setSessionKey})
    return {sessionKey, setSessionKey, branchKey, reloadBranchKey}
}
