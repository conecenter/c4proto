
import {createElement as $,useState,useMemo,useLayoutEffect,useContext,createContext,useCallback,useEffect} from "../main/react-prod.js"

//// move to shared

const useWidth = element => {
    const [width,setWidth] = useState(Infinity)
    const resizeObserver = useMemo(()=>new ResizeObserver(entries => {
        const entry = entries[0]
        if(entry) {
            const {fontSize} = getComputedStyle(entry.target)
            setWidth(entry.contentRect.width / parseFloat(fontSize))
        }
    }))
    useLayoutEffect(()=>{
        element && resizeObserver.observe(element)
        return () => element && resizeObserver.unobserve(element)
    },[element])
    return width
}

const useEventListener = (el,evName,callback) => {
    useEffect(()=>{
        if(!callback || !el) return undefined
        el.addEventListener(evName,callback)
        return ()=>el.removeEventListener(evName,callback)
    },[el,evName,callback])
}

//// non-shared

const sum = l => l.reduce((a,b)=>a+b,0)


const fitButtonsSide = (allButtons,sideName,expandMode) => { // expandMode: 0 collapse 1 1-row 2 2-row
    const sideButtons = allButtons.filter(c => c.props.area===sideName)
    const buttons = sideButtons.flatMap(c => (
        c.props.optButtons && expandMode===1 ? c.props.optButtons :
        c.props.optButtons && expandMode===2 ? [] :
        [c]
    ))
    const optButtons = expandMode===2 && sideButtons.flatMap(c => c.props.optButtons || []) || []
    const width = Math.max(
        sum(buttons.map(c=>c.props.minWidth)),
        sum(optButtons.map(c=>c.props.minWidth))
    )
    return {width,buttons,optButtons}
}

const getVisibleFilters = (filters,hideEmptyFromIndex) => filters.filter(
    (c,j) => !c.props.canHide || j < hideEmptyFromIndex
)

const replaced = (wasItem,willItem) => l => l.map(item=>item===wasItem?willItem:item)

const doFitFilters = (filters,resTemplate) => {
    const fit = (res,filter) => {
        if(!res) return null
        const w = filter.props.minWidth
        const row = res.find(row=>row.leftWidth>=w)
        if(!row) return null
        const leftWidth = row.leftWidth-w
        const items = [...row.items,filter]
        return replaced(row,{leftWidth,items})(res)
    }
    const inner = (hideEmptyFromIndex,fallback) => {
        if(hideEmptyFromIndex > filters.length) return fallback
        const groupedFilters = getVisibleFilters(filters,hideEmptyFromIndex).reduce(fit, resTemplate)
        if(!groupedFilters) return fallback
        return inner(hideEmptyFromIndex+1,groupedFilters)
    }
    return inner(0,null)
}

const centerButtonWidth = 1
const emPerRow = 2

const fitFilters = (filters,outerWidth,rowCount,isExpanded,lt,rt) => {
    const allButtonWidth = lt.width + centerButtonWidth + rt.width
    const fitWidth = outerWidth - allButtonWidth
    if(isExpanded && fitWidth < 0 ) return null
    const minOuterWidth = isExpanded ? outerWidth :
        Math.max(outerWidth,allButtonWidth,...getWidthLimits(filters))
    const resTemplate = [...Array(rowCount)].map((u,j)=>({
        items: [], leftWidth: j===0 ? fitWidth : minOuterWidth
    }))
    const groupedFilters = doFitFilters(filters,resTemplate)
    return groupedFilters && {groupedFilters,lt,rt}
}

const getWidthLimits = filters => getVisibleFilters(filters,0).map(c=>c.props.minWidth)

const fitRows = (filters,buttons,outerWidth,expandMode,rowCount) => (
    fitFilters(filters, outerWidth, rowCount, true , fitButtonsSide(buttons,"lt",expandMode), fitButtonsSide(buttons,"rt",expandMode)) ||
    fitFilters(filters, outerWidth, rowCount, true , fitButtonsSide(buttons,"lt",expandMode), fitButtonsSide(buttons,"rt",         0)) ||
    fitFilters(filters, outerWidth, rowCount, false, fitButtonsSide(buttons,"lt",         0), fitButtonsSide(buttons,"rt",         0)) ||
    fitRows(filters,buttons,outerWidth,expandMode,rowCount+1)
)

const dMinMax = el => el.props.maxWidth - el.props.minWidth

const em = v => v+'em'

export function FilterArea({filters,buttons,centerButtonText}){
    const [gridElement,setGridElement] = useState(null)
    const outerWidth = useWidth(gridElement)
    const expandMode = filters && filters.length > 0 ? 2:1
    const {groupedFilters,lt,rt} = fitRows(filters||[],buttons||[],outerWidth,expandMode,1)
    const dnRowHeight = groupedFilters && groupedFilters[0] && groupedFilters[0].items.length>0 || lt.optButtons.length + rt.optButtons.length > 0 ? emPerRow : 0
    const yRowToEm = h => em(h * emPerRow*2 - emPerRow + dnRowHeight)

    const filterGroupElements = groupedFilters.flatMap(({items,leftWidth},rowIndex)=>{
        const proportion = Math.min(1,leftWidth/sum(items.map(dMinMax)))
        const getWidth = item => item.props.minWidth+dMinMax(item)*proportion
        return items.map((item,itemIndex)=>$("div",{ key:item.key, style:{
            position: "absolute",
            height: em(emPerRow*2),
            top: yRowToEm(rowIndex),
            width: em(getWidth(item)),
            left: em(sum(items.slice(0,itemIndex).map(getWidth))),
            boxSizing: "border-box",
        }},item))
    })

    const gridTemplateRows = '[up] '+em(emPerRow)+' [dn] '+em(dnRowHeight)
    const gridTemplateColumns = '[lt-btn] '+em(lt.width)+' [center-btn] '+em(centerButtonWidth)+' [rt-btn] '+em(rt.width)
    const style = { display: "grid", alignContent: "start", justifyContent: "end", gridTemplateRows, gridTemplateColumns, position: "relative", height: yRowToEm(groupedFilters.length) }
    return $("div",{ style, className:"filterArea", ref: setGridElement },
        $("div",{ key: "up-center-btn", style: { gridRow: "up", gridColumn: 'center-btn', display: "flex", alignItems: "center" } },centerButtonText),
        $("div",{ key: "up-lt-btn", style: { gridRow: "up", gridColumn: 'lt-btn', display: "flex", alignItems: "center", justifyContent: "flex-end" } },lt.buttons),
        $("div",{ key: "up-rt-btn", style: { gridRow: "up", gridColumn: 'rt-btn', display: "flex", alignItems: "center", justifyContent: "flex-start" } },rt.buttons),
        $("div",{ key: "dn-lt-btn", style: { gridRow: "dn", gridColumn: 'lt-btn', display: "flex", alignItems: "center", justifyContent: "flex-end" } },lt.optButtons),
        $("div",{ key: "dn-rt-btn", style: { gridRow: "dn", gridColumn: 'rt-btn', display: "flex", alignItems: "center", justifyContent: "flex-start" } },rt.optButtons),
        filterGroupElements
    )
}

////

const useAnimationFrame = (element,callback) => {
    useEffect(() => {
        if(!callback || !element) return
        const {requestAnimationFrame,cancelAnimationFrame} = element.ownerDocument.defaultView
        const animate = () => {
            callback()
            req = requestAnimationFrame(animate,element)
        }
        let req = requestAnimationFrame(animate,element)
        return () => cancelAnimationFrame(req)
    },[element,callback])
}

const prepCheckUpdPopupPos = element => {
    if(!element) return was=>was
    const {width:popupWidth,height:popupHeight} =
        element.getBoundingClientRect()
    const {width:parentWidth,height:parentHeight,top:parentTop,left:parentLeft} =
        element.parentElement.getBoundingClientRect()
    const {clientWidth,clientHeight} = element.ownerDocument.documentElement
    const check = (left,top) => (
        parentLeft + left > 0 &&
        parentLeft + left + popupWidth < clientWidth &&
        parentTop + top > 0 &&
        parentTop + top + popupHeight < clientHeight ?
        {position:"absolute",left,top} : null
    )
    const pos =
        check(0,parentHeight) || check(parentWidth-popupWidth,parentHeight) ||
        check(0,-popupHeight) || check(parentWidth-popupWidth,-popupHeight) ||
        {
            position:"absolute",
            left: (clientWidth-popupWidth)/2-parentLeft,
            top: (clientHeight-popupHeight)/2-parentTop,
        }
    return was=>(
        Math.abs(was.top-pos.top) < 0.5 &&
        Math.abs(was.left-pos.left) < 0.5 ?
        was : pos
    )
}
const popupParentStyle = {position:"relative"}
const usePopupPos = element => {
    const [position,setPosition] = useState({})
    const checkUpdPos = useCallback(()=>{
        setPosition(prepCheckUpdPopupPos(element))
    },[element,setPosition])
    useLayoutEffect(()=>{ checkUpdPos() },[checkUpdPos])
    useAnimationFrame(element,checkUpdPos)
    return [position,popupParentStyle]
}

export const PopupContext = createContext()
const usePopupState = (identity,popupElement) => {
    const [popup,setPopup] = useContext(PopupContext)
    const isOpened = useCallback(p => p===identity, [identity])
    const setOpened = useCallback(() => setPopup(identity), [setPopup,identity])
    const doc = popupElement && popupElement.ownerDocument
    const checkClose = useCallback(ev=>{
        setPopup(was=>isOpened(was)?null:was)
    },[setPopup,isOpened])
    useEventListener(doc,"click",checkClose)
    return [isOpened(popup),setOpened]
}


export function FilterExpander({identity,optButtons}){
    const [popupElement,setPopupElement] = useState(null)
    const [popupStyle,parentStyle] = usePopupPos(popupElement)
    const width = em(Math.max(...optButtons.map(c=>c.props.minWidth)))
    const [isOpened,open] = usePopupState(identity,popupElement)

    console.log("p-render")
    return $("div",{className:"filterExpander",style:parentStyle,onClick:ev=>open()},
        isOpened && $("div",{style:{...popupStyle,width},ref:setPopupElement},optButtons)
    )
}