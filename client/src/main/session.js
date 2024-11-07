// @ts-check

import {useState,useCallback,useEffect} from "react"
import {manageEventListener} from "../main/util.js"

const never = cause => { throw new Error(cause) }
export const login = (user, pass) => fetch("/auth/check",{ method: "POST", body: `${user}\n${pass}` })
                                        .then(resp => resp.ok && resp.json().sessionKey || never("failed"))

const stateKey = "c4sessionKey"
const useSessionRestoreOnRefresh = ({win, sessionKey, setSessionKey}) => {
    useEffect(()=>{
        if(!win) return undefined
        const sessionKey = win.sessionStorage.getItem(stateKey)
        win.sessionStorage.removeItem(stateKey)
        sessionKey && setSessionKey(sessionKey)
    }, [win, setSessionKey])
    useEffect(()=>{
        return manageEventListener(win, "beforeunload", ()=>win.sessionStorage.setItem(stateKey, sessionKey))
    }, [win, sessionKey])
}

const useLoadBranchKey = (sessionKey,setSessionKey) => {
    const [branchBySession, setBranchBySession] = useState({})
    const branchKey = branchBySession[sessionKey]
    const reloadBranchKey = useCallback(()=>{
        const fin = resp => {
            resp?.branchKey ? setBranchBySession(was=>({...was, [sessionKey]: resp.branchKey})) :
            resp?.error || !branchKey ? setSessionKey(null) : null
        }
        sessionKey && fetch("/auth/branch",{method: "POST", headers: {"x-r-session":sessionKey}})
            .then(resp => fin(resp.json()), error => fin(null))
    },[sessionKey,setBranchBySession])
    useEffect(reloadBranchKey, [reloadBranchKey])
    return [branchKey, reloadBranchKey]
}

export const useSession = win => {
    const [sessionKey, setSessionKey] = useState()
    useSessionRestoreOnRefresh({win, sessionKey, setSessionKey})
    const [branchKey, reloadBranchKey] = useLoadBranchKey(sessionKey, setSessionKey)
    return {sessionKey, setSessionKey, branchKey, reloadBranchKey}
}
