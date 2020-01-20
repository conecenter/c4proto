
export default function SessionReload(localStorage,sessionStorage,location,random){
    const checkActivate = modify => modify("SESSION_RELOAD",state=>{
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
            location.reload()
            return ({...state,stopped:true})
        }
        return state
    })
    return ({checkActivate})
}
//#location.hash