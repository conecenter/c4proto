
import {createContext,createElement,useState,useContext,useCallback,useEffect,memo} from "../main/react-prod.js"
import {splitFirst,spreadAll,oValues}    from "../main/util.js"
import {ifInputsChanged,dictKeys,branchByKey,rootCtx,ctxToPath,chain,someKeys,identityOf,valueAt} from "../main/vdom-util.js"



//todo branch LIFE

const getLastKey = ctx => ctx && (ctx.key || getLastKey(ctx.parent))
function setupIncomingDiff(by,content,parent) {
    const {isInSet} = parent
    const setKey = isInSet && content["at"] && getLastKey(parent)
    const changes = Object.keys(content).map(key=>{
        const value = content[key]
        const trans = by[key]
        const handler = trans && value && (trans[value] || trans[value[0]])
        const will =
            handler ?
                (key==="tp" ? handler : handler({ value, parent, isInSet })) :
            key.substring(0,1)===":" || key === "at" ?
                setupIncomingDiff(by,value,{ key, parent, isInSet }) :
            key === "$set" ?
                setupIncomingDiff(by,value,{ parent, isInSet: true }) :
            value
        return value === will ? null : ({ [key]: will })
    }).concat(setKey && {key:setKey}).filter(i=>i)

    return changes.length>0 ? spreadAll(content, ...changes) : content
}

export function VDomCore(react,reactDOM,update,log,activeTransforms,getRootElement){
        
    const joinSeeds = ifInputsChanged(log)("seedsFrom", {branchByKey:1}, changed => state => {
      const seedByKey = spreadAll(...oValues(state.branchByKey).map(brSt=>
          spreadAll(...oValues(brSt.seedByKey).filter(seed=>seed.element).map(seed=>({[seed.branchKey]:seed})))
      ))
      return branchByKey.all(brSt=>checkUpdate({seed:seedByKey[brSt.branchKey]})(brSt))(changed(state))
    })
    
    const joinBranches = ifInputsChanged(log)("branchesFrom", {branchByKey:1,activeBranchesStr:1}, changed => state => {
        return branchByKey.all(brSt=>{
            const isRoot  = brSt.branchKey && (state.activeBranchesStr||"").startsWith(brSt.branchKey)
            const isActive = brSt.branchKey && (state.activeBranchesStr||"").includes(brSt.branchKey) 
            return checkUpdate({ isActive, isRoot })(brSt)
        })(changed(state))
    })
    
    const findRootParent = ifInputsChanged(log)("rootParentFrom", {isRoot:1}, changed => state => {
        if(!state.isRoot) return changed(state)
        const rootParentElement = getRootElement()
        if(!rootParentElement) return state
        return {...changed(state), rootParentElement}
    })

    const findFrameParent = ifInputsChanged(log)("frameParentFrom", {seed:1}, changed => state => {
        const seed = state.seed
        const frameElement = seed && seed.element
        const fontSize = seed && seed.fontSize
        const contentWindow = frameElement && frameElement.contentWindow
        const body = contentWindow && contentWindow.document.body
        const frameParentElement = body && body.id && body
        if(frameElement && !frameParentElement) return state;
        if(frameParentElement)
            frameParentElement.style.fontSize = fontSize || ""
        return ({...changed(state), frameParentElement})
    })

    const setupRootElement = ifInputsChanged(log)("rootElementFrom", {rootParentElement:1,frameParentElement:1}, changed => state => {
        const parentNode = state.rootParentElement || state.frameParentElement
        const rootNativeElement = state.rootNativeElement || parentNode && parentNode.ownerDocument.createElement("span")
        if(rootNativeElement && rootNativeElement.parentElement !== parentNode) {
            if(parentNode) parentNode.appendChild(rootNativeElement) 
            else rootNativeElement.parentElement.removeChild(rootNativeElement)
        }
        return {...changed(state), rootNativeElement}
    })

    const SyncInputRoot = activeTransforms.tp.SyncInputRoot
    const rendering = ifInputsChanged(log)("renderedFrom", {incoming:1,ack:1,rootNativeElement:1}, changed => state => {
        if(state.incoming && state.rootNativeElement){
            const rootVirtualElement =
                react.createElement(SyncInputRoot,{ack:state.ack,incoming:state.incoming})
            reactDOM.render(rootVirtualElement, state.rootNativeElement)
        }
        return changed(state)
    })

    const mortality = ifInputsChanged(log)("lifeFrom", {isActive:1}, changed => state => {
        if(state.isActive) return changed(state)
        if(Date.now()-state.incomingTime < 100) return state 
        if(state.rootNativeElement) {
            reactDOM.unmountComponentAtNode(state.rootNativeElement)
            //parentNode.removeChild(was)
        }
        return null
    })
    
    const checkActivate = modify => modify("FRAME",chain([
        joinBranches,
        joinSeeds,
        branchByKey.all(chain([
            findRootParent,
            findFrameParent,
            setupRootElement,
            rendering,
            mortality
        ]))
    ]))

    const showDiff = (data,modify) => {
        const [branchKey,body] = splitFirst(" ", data)
        const value = JSON.parse(body)
        const ctx = {branchKey, modify}
        const nValue = setupIncomingDiff(activeTransforms,value,ctx)
        modify("SHOW_DIFF",branchByKey.one(branchKey, state => {
            const was = state && state.incoming
            if(!was && !value["$set"]) return null
            const incoming = update(was || {}, nValue)
            const incomingTime = Date.now()
            return {...state,incoming,incomingTime,branchKey}
        }))
    }

    const branches = (data,modify) => {
        modify("BRANCHES", checkUpdate({activeBranchesStr:data}))
    }

    const ackChange = (data,modify) => {
        const [branchKey,body] = splitFirst(" ", data)
        const index = parseInt(body)
        modify("ACK_CHANGE",branchByKey.one(branchKey,someKeys({ack:st=>({index})})))
    }

    const receivers = ({branches,showDiff,ackChange})
    return ({receivers,checkActivate})
}

const seedByKey = dictKeys(f=>({seedByKey:f}))

const checkUpdate = changes => state => (
    Object.keys(changes).every(k=>state && state[k]===changes[k]) ?
        state : {...state,...changes}
)

/********* sync ***************************************************************/

const NoContext = createContext()
const AckContext = createContext()
const SenderContext = createContext()
const nonMerged = ack => aPatch => !(aPatch && ack && aPatch.sentIndex <= ack.index)
export const useSender = () => useContext(SenderContext)
export const useSync = identity => {
    const [patches,setPatches] = useState([])
    const sender = useSender()
    const enqueuePatch = useCallback(aPatch=>{
        setPatches(aPatches=>[...aPatches,{...aPatch, sentIndex: sender.enqueue(identity,aPatch)}])
    },[sender,identity])
    const ack = useContext(patches.length>0 ? AckContext : NoContext)
    useEffect(()=>{
        setPatches(aPatches => aPatches.every(nonMerged(ack)) ? aPatches : aPatches.filter(nonMerged(ack)))
    },[ack])
    return [patches,enqueuePatch]
}
export function createSyncProviders({sender,ack,children}){
    return createElement(SenderContext.Provider, {value:sender},
        createElement(AckContext.Provider, {value:ack}, children)
    )
}

/********* sync input *********************************************************/

export function useSyncInput(identity,incomingValue,deferSend){
    const [patches,enqueuePatch] = useSync(identity)
    const [lastPatch,setLastPatch] = useState()
    const defer = deferSend(!!lastPatch)
    const onChange = useCallback(event => {
        const headers = ({...event.target.headers})
        const value = event.target.value
        enqueuePatch({ headers: {...headers,"x-r-changing":"1"}, value, skipByPath: true, retry: true, defer})
        setLastPatch({ headers, value, skipByPath: true, retry: true })
    },[enqueuePatch,defer])
    const onBlur = useCallback(event => {
        setLastPatch(wasLastPatch=>{
            if(wasLastPatch) enqueuePatch(wasLastPatch)
            return undefined
        })
    },[enqueuePatch])
    useEffect(()=>{
        setLastPatch(wasLastPatch => wasLastPatch && wasLastPatch.value === incomingValue ? wasLastPatch : undefined)
    },[incomingValue])
    const patch = patches.slice(-1).map(({value})=>({value}))[0]
    const value = patch ? patch.value : incomingValue
    const changing = patch || lastPatch ? "1" : undefined
    return ({value,changing,onChange,onBlur})
}
const SyncInput = memo(function SyncInput({value,onChange,...props}){
    const {identity,deferSend} = onChange
    const patch = useSyncInput(identity,value,deferSend)
    return props.children({...props, ...patch})
})

/********* traverse ***********************************************************/

/*
function reProp(props){
    Object.assign({},...Object.entries(props).map([k,v]=>
        k === "at" ? v :
        k.startsWith(":") ? undefined :
        { [k]: ()=>v.map(ik=>reProp(props[ik])) }
    ))
}*/

const tpOf = valueAt("tp")

function TraverseOne(props){
    if(identityOf(props)) {
        // const prop = reProp(props)
        return createElement(tpOf(props),props)
    }
    const {tp,...at} = props.at
    const children =
        at.content && at.content[0] === "rawMerge" ? props :
        props.chl ? traverseChildren(props) : at.content
    return at.onChange ?
        createElement(at.onChange.tp, at, uProp=>createElement(tp, uProp, children)) :
        createElement(tp, at, children)
}
function TraverseChildren(props){
    return (props.chl||[]).map(key => traverseOne(props[key]))
}

const TraverseOneMemo = memo(TraverseOne)
export const traverseOne = props => createElement(TraverseOneMemo,props)
const traverseChildren = TraverseChildren
// const TraverseChildrenMemo = memo(TraverseChildren)
// const traverseChildren = props => createElement(TraverseChildrenMemo,props)

/******************************************************************************/

// todo no resize anti-dos

export function VDomAttributes(sender){
    const inpSender = {
        enqueue: (identityCtx,patch) => {
            const sent = sender.send(identityCtx,patch)
            return parseInt(sent["x-r-index"])
        },
    }
    function SyncInputRoot({incoming,ack}){
        return createSyncProviders({ ack, sender: inpSender, children: traverseOne(incoming) })
    }

    const sendThen = ctx => event => {
        sender.send(ctx,{value:""})
    }
    const onClick = ({/*send,*/sendThen}) //react gives some warning on stopPropagation

    const onChange = {
        "local": ctx => ({identity:ctx,deferSend:changing=>true,tp:SyncInput}),
        "send": ctx => ({identity:ctx,deferSend:changing=>false,tp:SyncInput}),
        "send_first": ctx => ({identity:ctx,deferSend:changing=>changing,tp:SyncInput}),
    }

    const seed = ctx => element => {
        const rCtx = rootCtx(ctx)
        const path = ctxToPath(ctx)
        const branchKey = ctx.value[1]
        const fontSize = element && element.style.fontSize
        rCtx.modify("SEED",branchByKey.one(rCtx.branchKey, seedByKey.one(path,
          checkUpdate({branchKey,element,fontSize})
        )))
    }

    const ref = ({seed})
    const ctx = { ctx: ctx => ctx }
    const identity = { ctx: ctx => ctx }
    const path = { "I": ctxToPath }
    const tp = ({SyncInputRoot})
    const transforms = {onClick,onChange,ref,ctx,tp,path,identity}
    return ({transforms})
}
