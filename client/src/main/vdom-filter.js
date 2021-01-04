
import {createElement as $,useState,useLayoutEffect,useContext,createContext,useCallback,useEffect,cloneElement} from "react"
import {useWidth,useEventListener} from "../main/vdom-hooks.js"

//// move to shared

//// non-shared

const sum = l => l.reduce((a,b)=>a+b,0)


const fitButtonsSide = (allButtons,sideName,isExpanded,isMultiline) => {
    const {list,getWidth} = allButtons
    const isInOptLine = c => isMultiline && c.props.optButtons
    const condExpand = c => isExpanded && c.props.optButtons ? c.props.optButtons : [c]
    const sideButtons = list.filter(c => c.props.area===sideName)
    const buttons = sideButtons.filter(c => !isInOptLine(c)).flatMap(condExpand)
    const optButtons = sideButtons.filter(isInOptLine).flatMap(condExpand)
    const centralWidth = sideName === "rt" ? getWidth(buttons.slice(0,1)) : 0
    const width = Math.max(getWidth(buttons), centralWidth+getWidth(optButtons))
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
    const res = inner(0,null)
    return res
}

const emPerRow = 2

const fitFilters = (filters,outerWidth,rowCount,canReduceButtonWidth,isMultilineButtons,lt,rt) => {
    if(filters.length > 0 && rowCount <= 1 && !isMultilineButtons) return null
    const allButtonWidth = lt.width + rt.width
    const fitWidth = isMultilineButtons ? Math.max(0, outerWidth - allButtonWidth) : 0
    if(canReduceButtonWidth && outerWidth < allButtonWidth ) return null

    const minOuterWidth = //isExpanded ? outerWidth :
        Math.max(outerWidth,allButtonWidth,...getWidthLimits(filters))

    const resTemplate = [...Array(rowCount)].map((u,j)=>({
        items: [], leftWidth: j===0 ? fitWidth : minOuterWidth
    }))
    const groupedFilters = doFitFilters(filters,resTemplate)
    return groupedFilters && {groupedFilters,lt,rt}
}

const getWidthLimits = filters => getVisibleFilters(filters,0).map(c=>c.props.minWidth)

const fitRows = (filters,buttons,outerWidth,rowCount) => (
    fitFilters(filters, outerWidth, rowCount, true ,false, fitButtonsSide(buttons,"lt",true ,false), fitButtonsSide(buttons,"rt",true ,false)) ||
    fitFilters(filters, outerWidth, rowCount, true ,false, fitButtonsSide(buttons,"lt",true ,false), fitButtonsSide(buttons,"rt",false,false)) ||
    fitFilters(filters, outerWidth, rowCount, true ,false, fitButtonsSide(buttons,"lt",false,false), fitButtonsSide(buttons,"rt",false,false)) ||
    fitFilters(filters, outerWidth, rowCount, true ,true , fitButtonsSide(buttons,"lt",true ,true ), fitButtonsSide(buttons,"rt",true ,true )) ||
    fitFilters(filters, outerWidth, rowCount, true ,true , fitButtonsSide(buttons,"lt",true ,true ), fitButtonsSide(buttons,"rt",false,true )) ||
    fitFilters(filters, outerWidth, rowCount, false,true , fitButtonsSide(buttons,"lt",false,true ), fitButtonsSide(buttons,"rt",false,true )) ||
    fitRows(filters,buttons,outerWidth,rowCount+1)
)

const dMinMax = el => el.props.maxWidth - el.props.minWidth

const em = v => v+'em'

export function FilterArea({filters,buttons,className/*,maxFilterAreaWidth*/}){
    const [gridElement,setGridElement] = useState(null)
    const outerWidth = useWidth(gridElement)

    const [btnWidths,setBtnWidths] = useState({})
    const getButtonWidth = item => btnWidths[item.key]||0
    const getButtonsWidth = items => sum(items.map(getButtonWidth))

    const {groupedFilters,lt,rt} = fitRows(filters||[],{list:buttons||[],getWidth:getButtonsWidth},outerWidth,1)
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


    useLayoutEffect(()=>{
        if(!gridElement) return
        const fontSize = parseFloat(getComputedStyle(gridElement).fontSize)
        const widths = [...gridElement.children].map(el=>{
            const key = el.getAttribute("data-btn-key")
            return key && [key, el.getBoundingClientRect().width / fontSize]
        }).filter(Boolean)
        setBtnWidths(was=>(
            widths.every(([key,width]) => width - (key in was ? was[key] : 0) <= 0) ?
                was : Object.fromEntries(widths)
        ))
    },[gridElement,buttons]) // ? are all child elements ready ; do we miss some due to these deps ?
    const centerWidth = getButtonsWidth(rt.buttons.slice(0,1))
    const btnPosByKey = Object.fromEntries([
        ...lt.buttons.map((item,itemIndex,items)=>[   item.key,0       , outerWidth-rt.width-getButtonsWidth(items.slice(itemIndex))]),
        ...lt.optButtons.map((item,itemIndex,items)=>[item.key,emPerRow, outerWidth-rt.width-getButtonsWidth(items.slice(itemIndex))]),
        ...rt.buttons.map((item,itemIndex,items)=>[   item.key,0       , outerWidth-rt.width+getButtonsWidth(items.slice(0,itemIndex))]),
        ...rt.optButtons.map((item,itemIndex,items)=>[item.key,emPerRow, outerWidth-rt.width+centerWidth+getButtonsWidth(items.slice(0,itemIndex))]),
    ].map((([key,top,left])=>[key,{top,left}])))
    const btnElements = buttons.flatMap(c => [c,...(c.props.optButtons||[])]).map(c=>{
        const pos = btnPosByKey[c.key]
        return cloneElement(c,{getButtonWidth,pos:{
            "data-btn-key": c.key,
            style: {
                height:"2em", position: "absolute", boxSizing: "border-box",
                top: em(pos?pos.top:0), left: em(pos?pos.left:0),
                visibility: pos?null:"hidden",
            }
        }})
    })

    const children = [...filterGroupElements,...btnElements]
    /* maxWidth: maxFilterAreaWidth ? em(maxFilterAreaWidth) : "100vw"*/
    const style = { position: "relative", height: yRowToEm(groupedFilters.length) }
    return $("div",{ style, className, ref: setGridElement, children })
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

const PopupContext = createContext()
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
export function PopupManager({children}){
    const [popup,setPopup] = useState(null) // todo useSync
    return $(PopupContext.Provider,{value:[popup,setPopup]},children)
}

export function FilterButtonExpander({identity,optButtons:rawOptButtons,pos,className,popupClassName,popupItemClassName,children,openedChildren,getButtonWidth}){
    const optButtons = rawOptButtons || []
    const [popupElement,setPopupElement] = useState(null)
    const [popupStyle,popupParentStyle] = usePopupPos(popupElement)
    const width = em(Math.max(...optButtons.map(getButtonWidth)))
    const [isOpened,open] = usePopupState(identity,popupElement)
    const {style:posStyle,...posData} = pos
    const parentStyle = {...popupParentStyle,...posStyle}

    console.log("p-render-")
    return $("div",{className,style:parentStyle,onClick:ev=>open(),...posData},
        isOpened ? [
            openedChildren,
            $("div",{key:"popup",className:popupClassName,style:{...popupStyle,width},ref:setPopupElement},optButtons.map(btn=>{
                return $("div",{ key: btn.key, className: popupItemClassName }, btn.props.children)
            }))
        ] : children
    )
}

export function FilterButtonPlace({pos,className,children}){
    return $("div",{className,...pos},children)
}

export function FilterItem({className,children}){
    return $("div",{className},children)
}

export const components = {FilterArea,FilterButtonExpander,FilterButtonPlace,FilterItem,PopupManager}