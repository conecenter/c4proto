
import {manageEventListener, getKey, asObject, asString, assertNever, Login} from "./util"

export type Session = { sessionKey: string, branchKey: string, login: Login, check: ()=>Promise<void>, manageUnload: ()=>void }
export const SessionManager = (win: Window, setSession: (session: Session)=>void) => {
    const getBranch = async (sessionKey: string): Promise<{branchKey?:string,error?:string}> => {
        const rj = await (await win.fetch("/auth/branch",{method: "POST", headers: {"x-r-session":sessionKey}})).json()
        return asObject(rj)
    }    
    const loginInner = async (body: string): Promise<void> => {
        const rj = await (await win.fetch("/auth/check",{ method: "POST", body })).json()
        const sessionKey = asString(getKey(asObject(rj), "sessionKey")) || assertNever("no sessionKey")
        const branchKey = (await getBranch(sessionKey)).branchKey || assertNever("no branchKey")
        createSetSession(sessionKey, branchKey)
    }
    const login = (user: string, pass: string) => loginInner(`${user}\n${pass}`)
    const stateKey = "c4sessionState"
    const createSetSession = (sessionKey: string, branchKey: string) => {
        const check = async (): Promise<void> => { (await getBranch(sessionKey)).error && await loginInner("") }
        const manageUnload = () => manageEventListener(win, "beforeunload", ()=>{
            win.sessionStorage.setItem(stateKey, `${sessionKey}\n${branchKey}`)
        })
        setSession({sessionKey,branchKey,login,check,manageUnload})
    }
    const load = async (): Promise<void> => {
        const state = win.sessionStorage.getItem(stateKey)
        if(!state) return await loginInner("")
        win.sessionStorage.removeItem(stateKey)
        const [sessionKey,branchKey] = state.split("\n")
        sessionKey && branchKey ? createSetSession(sessionKey, branchKey) : assertNever("")
    }
    return {load}
}
