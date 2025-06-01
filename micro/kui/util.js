
import {useState,useEffect,createElement} from "react"
import {createRoot} from "react-dom/client"

const getHashParams = () => Object.fromEntries(new URLSearchParams(location.hash.substring(1)))
const setHashParams = o => { location.hash = "#" + new URLSearchParams({...getHashParams(),...o}).toString() }

const manageEventListener = (element, evName, listener) => {
    if(element && listener){
        element.addEventListener(evName, listener)
        return () => element.removeEventListener(evName, listener)
    }
}

const now = () => Date.now()

const manageExchange = (url, setState) => {
    let wasAt = 0
    let ws = undefined
    const close = () => {
        try { ws && ws.readyState <= ws.OPEN && ws.close() } catch(e){ console.trace(e) }
    }
    const activate = () => {
        if(document.hidden) close()
        else if(ws && ws.readyState <= ws.OPEN && now() - wasAt < 5000)
            ws.readyState === ws.OPEN && ws.send(JSON.stringify({...getHashParams(), op: "load" }))
        else {
            close()
            ws = new WebSocket(url)
            ws.addEventListener("message", ev => {
                setState(was => ({...was, ...JSON.parse(ev.data), willNavigate, willSend}))
                wasAt = now()
            })
            ws.addEventListener("open", ev => activate())
            wasAt = now()
        }
    }
    const willNavigate = o => () => { // cat become memo/cache later
        setHashParams(o)
        activate()
    }
    const willSend = msg => async () => {
        //setState(was => ({...was, loading: true}))
        ws && ws.readyState === ws.OPEN && ws.send(JSON.stringify(msg))
        activate()
    }
    const interval = setInterval(() => activate(), 1000)
    const remove = manageEventListener(document, 'visibilitychange', () => activate())
    activate()
    return () => {
        clearInterval(interval)
        close()
        remove()
    }
}

const App = ({url, getContent}) => {
    const [state, setState] = useState(() => ({loading: 0}))
    useEffect(() => manageExchange(url, setState), [setState])
    return state.willSend && getContent({...state, ...getHashParams()})
}

export const start = (url, getContent) => {
    const rootNativeElement = document.createElement("span")
    document.body.appendChild(rootNativeElement)
    createRoot(rootNativeElement).render(createElement(App, {url, getContent}))
}

// this control works well only being the only source of changes
export const useSimpleInput = ({ value, onChange, dirtyClassName, className, ...props }) => {
  const [element, setElement] = useState()
  const [_, setDummyCounter] = useState(0)
  const rerender = () => setDummyCounter(was=>was+1)
  useEffect(() => manageEventListener(element, "change", ev => onChange(ev.target.value)), [element])
  useEffect(() => {
    if (element && element.value === value){
        element.closest("form").reset()
        rerender()
    }
  }, [value, element])
  const mergedClassName = element && element.value !== value ? `${className} ${dirtyClassName}` : className
  return { ...props, ref: setElement, defaultValue: value, onChange: rerender, className: mergedClassName }
}

export const useTabs = ({viewProps, tabs}) => {
    const {tab, willNavigate} = viewProps
    useEffect(()=>{ tab || willNavigate({ tab: tabs[0].key })() }, [tab])
    return tabs.find(({key}) => tab === key)?.view({...viewProps,...viewProps[tab]})
}
