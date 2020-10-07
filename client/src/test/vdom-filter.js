
import {createElement as $,useState,useMemo,useLayoutEffect} from "../main/react-prod.js"

//// shared

const spanAll = "1 / -1"

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



//// non-shared

const sum = l => l.reduce((a,b)=>a+b,0)

const fitButtonsSide = (props,sideName,isExpanded) => {
    const buttons = props.buttons.filter(c => c.props.area===sideName && !(isExpanded && c.props.isOptExpander))
    const optButtons = isExpanded ? props.optButtons.filter(c => c.props.area===sideName) : []
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

const fitFilters = ({filters,outerWidth},rowCount,isExpanded,lt,rt) => {
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

const fitRows = (props,rowCount) => (
    fitFilters(props, rowCount, true , fitButtonsSide(props,"lt",true ), fitButtonsSide(props,"rt",true )) ||
    fitFilters(props, rowCount, true , fitButtonsSide(props,"lt",true ), fitButtonsSide(props,"rt",false)) ||
    fitFilters(props, rowCount, false, fitButtonsSide(props,"lt",false), fitButtonsSide(props,"rt",false)) ||
    fitRows(props, rowCount+1)
)

const dMinMax = el => el.props.maxWidth - el.props.minWidth

const em = v => v+'em'

export function FilterArea({filters,buttons,optButtons,centerButtonText}){
    const [gridElement,setGridElement] = useState(null)
    const outerWidth = useWidth(gridElement)
    const {groupedFilters,lt,rt} = fitRows({filters,buttons,optButtons,outerWidth},1)
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
        //$("div",{ key: "filter", style: { gridRow: spanAll, gridColumn: 'filter', position: "relative" } },filterGroupElements),
        $("div",{ key: "up-center-btn", style: { gridRow: "up", gridColumn: 'center-btn', display: "flex", alignItems: "center" } },centerButtonText),
        $("div",{ key: "up-lt-btn", style: { gridRow: "up", gridColumn: 'lt-btn', display: "flex", alignItems: "center", justifyContent: "flex-end" } },lt.buttons),
        $("div",{ key: "up-rt-btn", style: { gridRow: "up", gridColumn: 'rt-btn', display: "flex", alignItems: "center", justifyContent: "flex-start" } },rt.buttons),
        $("div",{ key: "dn-lt-btn", style: { gridRow: "dn", gridColumn: 'lt-btn', display: "flex", alignItems: "center", justifyContent: "flex-end" } },lt.optButtons),
        $("div",{ key: "dn-rt-btn", style: { gridRow: "dn", gridColumn: 'rt-btn', display: "flex", alignItems: "center", justifyContent: "flex-start" } },rt.optButtons),
        filterGroupElements
    )
}