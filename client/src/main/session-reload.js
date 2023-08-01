
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
    const clearLocalStorage = (before) => {
        for (const [key, value] of Object.entries(localStorage)) {
            if (key.includes('loadKeyForSession')) {
                const timestamp = +value.match(/\d+/)?.[0];
                if (timestamp < before) localStorage.removeItem(key);
            }
        }
    }
    const checkSessionReload = state => {
        const sessionKey = sessionStorage.getItem("sessionKey")
        if(!sessionKey || state.stopped) return state;
        const loadKeyForSession = "loadKeyForSession-" + sessionKey
        if(!state.loadKey){
            const now = Date.now()
            clearLocalStorage(now - 30 * 24 * 3600000)
            const loadKey = "L" + now + 'R' + random()
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