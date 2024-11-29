
import {useState,useEffect} from "./react"
import {manageEventListener, getKey, asObject, asString, assertNever, weakCache, SetState} from "./util"

type SessionState = { sessionKey: string, branchKey: string, login: Login }
export type Login = (user: string, pass: string) => Promise<void>

const getBranch = async (win: Window, sessionKey: string): Promise<{branchKey?:string,error?:string}> => {
    const rj = await (await win.fetch("/auth/branch",{method: "POST", headers: {"x-r-session":sessionKey}})).json()
    return asObject(rj)
}
const loginInner = async (win: Window, body: string): Promise<SessionState> => {
    const rj = await (await win.fetch("/auth/check",{ method: "POST", body })).json()
    const sessionKey = asString(getKey(asObject(rj), "sessionKey")) || assertNever("no sessionKey")
    const branchKey = (await getBranch(win, sessionKey)).branchKey || assertNever("no branchKey")
    return createSession({sessionKey,branchKey})
}
const stateKey = "c4sessionState"
const manageUnload = (win: Window, {sessionKey,branchKey}: SessionState) => manageEventListener(win, "beforeunload", ()=>{
    sessionKey && branchKey && win.sessionStorage.setItem(stateKey, `${sessionKey}\n${branchKey}`)
})
const load = async (win: Window): Promise<SessionState> => {
    const state = win.sessionStorage.getItem(stateKey)
    if(state){
        win.sessionStorage.removeItem(stateKey)
        const [sessionKey,branchKey] = state.split("\n")

        return sessionKey && branchKey ? createSession({sessionKey,branchKey}) : assertNever("")
    } else {
        return await loginInner(win, "")
    }
}

const createSetSession = ({win,setSession,sessionKey,branchKey}) => {
    const check = async (): Promise<SessionState|undefined> => {
        return (await getBranch(win, sessionKey)).error ? await loginInner(win, "") : undefined
    }
    setSession(was => ({sessionKey,branchKey,login,check}))
}



const checks = weakCache((win: Window) => weakCache((session: SessionState) => weakCache((setSession: SetState<SessionState|undefined>) => 
    () => { check(win, session).then(s => s && setSession(was=>s)) }
)))
const logins = weakCache((win: Window) => weakCache((setSession: SetState<SessionState|undefined>) => 
    (user: string, pass: string) => loginInner(win, `${user}\n${pass}`).then(s=>setSession(was=>s))
))

export const useSession = (win: Window) => {
    const [session, setSession] = useState<SessionState|undefined>()
    const [failure, setFailure] = useState<unknown>()
    useEffect(() => { win && load(win).then(setSession, setFailure) }, [win, setSession, setFailure])
    useEffect(() => win && session && manageUnload(win, session), [win, session])
    const reloadBranchKey: (()=>void)|undefined = session && checks(win)(session)(setSession)
    const login: Login = logins(win)(setSession)
    return {session, failure, reloadBranchKey, login}
}
