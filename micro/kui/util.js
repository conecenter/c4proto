import {useState,useEffect,useCallback,useMemo,createElement} from "react"
import {createRoot} from "react-dom/client"
import {
    useQuery, useMutation, useQueryClient, QueryClient, QueryClientProvider, useMutationState, useIsFetching
} from '@tanstack/react-query'

const toNavObj = str => Object.fromEntries(new URLSearchParams(str.substring(1)))
export const withHashParams = o => `${new URLSearchParams({...toNavObj(location.hash),...o})}`

const manageEventListener = (element, evName, listener) => {
    if(element && listener){
        element.addEventListener(evName, listener)
        return () => element.removeEventListener(evName, listener)
    }
}

export const toPath = ({op,...args}) => `/${op}?${new URLSearchParams(Object.entries(args).sort(([a],[b])=>a<b?-1:a>b?1:0).map(([k,v])=>[k,v??'']))}`

const die = e => { throw e }

const processResp = resp => resp.ok ? resp : die(new Error('fetch_error'))
const mutationFn = async u => { processResp(await fetch(u, {method: "POST"})) }
const queryFn = async ({queryKey: [_pre, u]}) => ({
    ...(await processResp(await fetch(u, {cache: "no-cache"})).json()), ldPath: u
})

export const useNavigation = defTab => {
    const [state, setState] = useState(location.hash)
    useEffect(()=>manageEventListener(document.defaultView, "hashchange", ()=>setState(location.hash)), [setState])
    const willNavigate = useCallback(diff => () => setState(location.hash = "#" + withHashParams(diff)), [setState])
    const nav = toNavObj(state)
    const {tab} = nav
    useEffect(()=> { tab || willNavigate({ tab: defTab })() }, [tab, willNavigate])
    return [nav, willNavigate]
}

export const useErrorList = () => {
    const [errors, setErrors] = useState([])
    const addErrorText = useCallback(text => setErrors(s => [...s.filter(e => e.text !== text), {text}]), [setErrors])
    const delError = useCallback(m => setErrors(s => s.filter(e => e !== m)), [setErrors])
    return {errors, addErrorText, delError}
}

export const queryOpt = ({invalidationGroup: ig, interval}) => msg => ({
    queryFn, queryKey: [ig ?? 'dynamic', toPath(msg)], refetchInterval: interval*1000, retry: interval > 8
})
export const useRQuery = useQuery

export const usePending = () => [
    useIsFetching(), useMutationState({ filters: { status: 'pending' }, select: m => m.state.variables.at(-1) }),
]

export const useAppMutation = onError => {
    const queryClient = useQueryClient()
    const onSuccess = () => { queryClient.invalidateQueries({ queryKey: ['dynamic'] }) }
    const {mutate} = useMutation({mutationFn, onSuccess, onError})
    return useCallback(msg => () => mutate(toPath(msg)), [mutate])
}

export const start = appElement => {
    const rootNativeElement = document.createElement("span")
    document.body.appendChild(rootNativeElement)
    const queryClient = new QueryClient()
    const wrapElement = createElement(QueryClientProvider, { client: queryClient, children: appElement })
    createRoot(rootNativeElement).render(wrapElement)
}

// this control works well only being the only source of changes
export const useSimpleInput = ({ value, onChange, dirtyClassName, className, ...props }) => {
  const [element, setElement] = useState()
  const [_, setDummyCounter] = useState(0)
  const rerender = () => setDummyCounter(was=>was+1)
  useEffect(() => manageEventListener(element, "change", ev => onChange(ev.target.value)), [element, onChange])
  useEffect(() => {
    if (element && element.value === value){
        element.closest("form").reset()
        rerender()
    }
  }, [value, element])
  const mergedClassName = element && element.value !== value ? `${className} ${dirtyClassName}` : className
  return { ...props, ref: setElement, defaultValue: value, onChange: rerender, className: mergedClassName }
}