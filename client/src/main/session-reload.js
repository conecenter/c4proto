
export default function SessionReload(localStorage,sessionStorage,location,random){
    const reload = location => state => {
        location.reload()
        return ({...state,stopped:true})
    }
    const checkErrorReload = state => (
        !state.skipErrorReloadUntil ? { ...state, skipErrorReloadUntil: Date.now()+5000 } :
        !state.wasModificationError ? state :
        Date.now() < state.skipErrorReloadUntil ? state :
        reload(location)(state)
    )
    const checkSessionReload = state => {
        const sessionKey = sessionStorage.getItem("sessionKey")
        if(!sessionKey || state.stopped) return state;
        const loadKeyForSession = "loadKeyForSession-" + sessionKey
        if(!state.loadKey){
            const loadKey = "L"+random()
            localStorage.setItem(loadKeyForSession, loadKey)
            return ({...state,loadKey})
        }
        const was = localStorage.getItem(loadKeyForSession)
        if(was !== state.loadKey){ //duplicated or session-set
            if(was) sessionStorage.clear() //duplicated
            return reload(location)(state)
        }
        return state
    }
    const checkActivate = modify => modify("CHECK_RELOAD", state => (
        state.stopped ? state : checkSessionReload(checkErrorReload(state))
    ))
    return ({checkActivate})
}
//#location.hash