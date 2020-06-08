
import {splitFirst,spreadAll,oValues}    from "../main/util"
import {ifInputsChanged,dictKeys,branchByKey,rootCtx,ctxToPath,chain,someKeys} from "../main/vdom-util"



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

function SyncMod(react){
    const {createContext,createElement,useState,useContext,useCallback,useEffect} = react
    const NoContext = createContext()
    const AckContext = createContext()
    const SenderContext = createContext()
    const useSync = ({identity,deferSend}) => {
        const [patch,setPatch] = useState()
        const sender = useContext(SenderContext)
        const enqueuePatch = useCallback(aPatch=>{
            setPatch({...aPatch, sentIndex: sender.enqueue(identity,aPatch)})
        },[sender,identity])
        const ack = useContext(patch ? AckContext : NoContext)
        useEffect(()=>{
            setPatch(aPatch => aPatch && ack && aPatch.sentIndex <= ack.index ? undefined : aPatch)
        },[ack])
        const flush = sender.flush
        useEffect(()=>{
            deferSend || flush()
        },[sender,patch,deferSend])
        return [patch,enqueuePatch,flush]
    }
    function createSyncProviders({sender,ack,children}){
        return createElement(SenderContext.Provider, {value:sender},
            createElement(AckContext.Provider, {value:ack}, children)
        )
    }
    return ({useSync,createSyncProviders})
}
function SyncInputMod({useCallback},{useSync}){
    function useSyncInput(options){
        const [patch,enqueuePatch,flush] = useSync(options)
        const onChange = useCallback(event => {
            enqueuePatch({ headers: event.target.headers, value: event.target.value, changing: true})
        },[])
        const onBlur = useCallback(event=>flush(),[flush])
        return [patch,onChange,onBlur]
    }
    return ({useSyncInput})
}

// no x-r-changing, todo focus server handler


// todo no resize anti-dos

export function VDomAttributes(react, sender){
    const {memo,createElement} = react
    const {useSync,createSyncProviders} = SyncMod(react)
    const {useSyncInput} = SyncInputMod(react,{useSync})

    function traverseOne(prop){
        const content =
            prop.at.content && prop.at.content[0] === "rawMerge" ? prop :
            prop.chl && createElement(Traverse,prop) ||  prop.at.content || null
        return prop.at.onChange ?
            createElement(SyncInput, prop.at, uProp=>createElement(uProp.tp, uProp, content)) :
            createElement(prop.at.tp, prop.at, content)
    }
    const Traverse = memo(function Traverse(props){
        return (props.chl||[]).map(key => traverseOne(props[key]))
    })
    const SyncInput = memo(function SyncInput(props){
        const [patch,onChange,onBlur] = useSyncInput(props.onChange)
        return props.children({...props, onChange, onBlur, ...patch})
    })

    const inpSender = {
        enqueue: (identityCtx,patch) => {
            const sent = sender.send(identityCtx,{ ...patch, skipByPath: true })
            return parseInt(sent["x-r-index"])
        },
        flush: ()=>sender.flush()
    }
    function SyncInputRoot({incoming,ack}){
        return createSyncProviders({ ack, sender: inpSender, children: traverseOne(incoming) })
    }

    const sendThen = ctx => event => sender.send(ctx,{value:""})
    const onClick = ({/*send,*/sendThen}) //react gives some warning on stopPropagation

    const onChange = {
        "local": ctx => ({identity:ctx,deferSend:true}),
        "send": ctx => ({identity:ctx,deferSend:false})
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
    const path = { "I": ctxToPath }
    const tp = ({Traverse,ReControlledInput:"input",SyncInputRoot})
    const transforms = {onClick,onChange,ref,ctx,tp,path}
    return ({transforms})
}
