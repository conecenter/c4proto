

import {createElement as $, useMemo, useState, useLayoutEffect, cloneElement, createContext, useCallback, useContext, useEffect, memo} from "../main/react-prod.js"

import {map,head as getHead,identityAt,deleted,weakCache} from "../main/vdom-util.js"
import {useSync} from "../main/vdom-core.js"

const dragRowIdOf = identityAt('dragRow')
const dragColIdOf = identityAt('dragCol')

//// col hiding

const sortedWith = f => l => l && [...l].sort(f)

//

const useWidth = () => {
    const [width,setWidth] = useState(Infinity)
    const [element,setElement] = useState(null)
    const resizeObserver = useMemo(()=>new ResizeObserver(entries => {
        const entry = entries[0]
        if(entry) {
            const {fontSize} = getComputedStyle(entry.target)
            setWidth(Math.round(entry.contentRect.width / parseFloat(fontSize)))
        }
    }))
    useLayoutEffect(()=>{
        element && resizeObserver.observe(element)
        return () => element && resizeObserver.unobserve(element)
    },[element])
    return { ref: setElement, width }
}

const partitionVisibleCols = (cols,outerWidth) => {
    const fit = (count,accWidth) => {
        const col = cols[count]
        if(!col) return count
        const willWidth = accWidth + col.props.minWidth
        if(outerWidth < willWidth) return count
        return fit(count+1,willWidth)
    }
    const count = fit(0,0)
    return [cols.slice(0,count),cols.slice(count)]
}

const sortedByHideWill = sortedWith((a,b)=>a.props.hideWill-b.props.hideWill)

const useHiddenCols = cols => {
    const { ref: outerRef, width: outerWidth } = useWidth()
    const [visibleCols,hiddenCols] = partitionVisibleCols(sortedByHideWill(cols),outerWidth)
    const hasHiddenCols = hiddenCols.length > 0
    const hiddenColSet = hasHiddenCols && new Set(colKeysOf(hiddenCols))
    const hideElementsForHiddenCols = mode => (
        hasHiddenCols ? (children => children.filter(c=>mode===hiddenColSet.has(c.props.colKey))) :
        mode ? (children => []) : (children => children)
    )
    const toNarrowCols = cols => !hasHiddenCols ? cols :
        cols.map(c=>cloneElement(c,{maxWidth:c.props.minWidth}))
    return {outerRef,hasHiddenCols,hideElementsForHiddenCols,toNarrowCols}
}

//// expanding

const useExpanded = () => {
    const [expanded,setExpanded] = useState({})
    const setExpandedItem = useCallback((key,f) => setExpanded(was => {
        const wasValue = !!was[key]
        const willValue = !!f(wasValue)
        return wasValue===willValue ? was : willValue ? {...was, [key]:1} : deleted({[key]:1})(was)
    }),[setExpanded])
    const toExpanderElements = useCallback(on => !on ? (c=>c) : children => children.map(c=>{
        const {isExpander,rowKey} = c.props
        return isExpander && rowKey ? cloneElement(c,{
            onClick: ev => setExpandedItem(rowKey, v=>!v),
            expander: expanded[rowKey] ? 'expanded' : 'collapsed',
        }) : c
    }),[expanded,setExpandedItem])
    const getExpandedCells = useCallback(({cols,rowKeys,children}) => {
        if(cols.length<=0) return []
        const posStr = (rowKey,colKey) => rowKey+colKey
        const expandedByPos = Object.fromEntries(
            children.filter(c=>expanded[c.props.rowKey])
            .map(c=>[posStr(c.props.rowKey,c.props.colKey),c])
        )
        return rowKeys.filter(rowKey=>expanded[rowKey]).map(rowKey=>{
            const pairs = cols.map(col=>{
                const cell = expandedByPos[posStr(rowKey,col.props.colKey)]
                return [col, cell]
            })
            return [rowKey, pairs]
        })
    },[expanded])
    return {toExpanderElements,setExpandedItem,getExpandedCells}
}

const expandRowKeys = rowKeys => rowKeys.flatMap(rowKey=>[{rowKey},{rowKey,rowKeyMod:"-expanded"}])

//// drag model

const getDropOrder = (keys,from,to) => {
    const fromIndex = keys.indexOf(from)
    const toIndex = keys.indexOf(to)
    if(fromIndex < 0 || toIndex < 0 || fromIndex === toIndex) return null
    return fromIndex < toIndex ? [to,from] : [from,to]
}

const applyPatches = patches => value => { //memo?
    return patches.reduce((acc,{headers:{ "x-r-drag-key": from, "x-r-drop-key": to }})=>{
        const order = getDropOrder(acc,from,to)
        return !order ? acc : acc.flatMap(key => key===from ? [] : key===to ? order : [key])
    },value)
}
const createPatch = (from,to) => {
    const headers = { "x-r-drag-key": from, "x-r-drop-key": to }
    return {headers,retry:true}
}
const useSortRoot = (identity,dropKeys) => {
    const [patches,enqueuePatch] = useSync(identity)
    const drop = useCallback((from,to)=>{
        console.log(from,to)
        if(dropKeys.includes(to)) enqueuePatch(createPatch(from,to))
    },[enqueuePatch,dropKeys])
    const patchedKeys = useMemo(()=>applyPatches(patches)(dropKeys),[patches,dropKeys])
    return [patchedKeys,drop]
}

const remapCols = cols => {
    const colByKey = Object.fromEntries(cols.map(c=>[c.props.colKey,c]))
    return colKeys => colKeys.map(k=>colByKey[k])
}

//// main

const getGridRow = ({rowKey,rowKeyMod}) => rowKey+(rowKeyMod||'')

const spanAll = "1 / -1"

export function GridCell({children,rowKey,rowKeyMod,colKey,isExpander,expander,isRowDragHandle,...props}){
    const gridRow = getGridRow({rowKey,rowKeyMod})
    const gridColumn = colKey
    const style = { ...props.style, gridRow, gridColumn }
    const expanderProps = isExpander ? {'data-expander':expander||'passive'} : {}
    return $("div",{...props,...expanderProps,style},children)
}

const pos = (rowKey,colKey)=>({ key: rowKey+colKey, rowKey, colKey })

const colKeysOf = children => children.map(c=>c.props.colKey)

export function GridCol(props){
    return []
}

const getGridTemplateStyle = ({columns,rows}) => {
    const gridTemplateRows = map(o=>`[${getGridRow(o)}] auto`)(rows).join(" ")
    const gridTemplateColumns = map(c=>{
        const key = c.props.colKey
        const width = `minmax(${c.props.minWidth}em,${c.props.maxWidth}em)`
        return `[${key}] ${width}`
    })(columns).join(" ")
    return { display: "grid", gridTemplateRows, gridTemplateColumns }
}

export function GridRoot({identity,rowKeys,cols,...props}){
    const [patchedRowKeys,dropRow] = useSortRoot(dragRowIdOf(identity),rowKeys)
    const colKeys = useMemo(()=>colKeysOf(cols),[cols])
    const [patchedColKeys,dropCol] = useSortRoot(dragColIdOf(identity),colKeys)
    const patchedCols = useMemo(()=>remapCols(cols)(patchedColKeys),[cols,patchedColKeys])
    const drop = useCallback((axis,from,to)=>switchAxis(dropCol,dropRow)(axis)(from,to), [dropCol,dropRow])
    const keysByAxis = useMemo(()=>({x:patchedColKeys,y:patchedRowKeys}),[patchedColKeys,patchedRowKeys])
    const [style,dragging] = useGridDrag({drop,keysByAxis})
    return $("div",{style},$(GridRootMemo,{
        ...props, dragging, cols: patchedCols, rowKeys: patchedRowKeys
    }))
}

const GridRootMemo = memo(({children,rowKeys,cols,dragging}) => {
    console.log("inner render")

    const {toExpanderElements,setExpandedItem,getExpandedCells} = useExpanded()

    const {outerRef,hasHiddenCols,hideElementsForHiddenCols,toNarrowCols} =
        useHiddenCols(cols)

    const gridTemplateStyle = getGridTemplateStyle({
        rows: [{rowKey:"drag"}, {rowKey:"head"}, ...expandRowKeys(rowKeys), {rowKey:"drop"}],
        columns: [...toNarrowCols(hideElementsForHiddenCols(false)(cols)), $(GridCol,{colKey:"drop",minWidth:1,maxWidth:1})],
    })

    const headElements = map(col=>$(GridCell,{...pos("head",col.props.colKey)},col.props.caption))(cols)

    const dragStyle = { style: {userSelect: "none", cursor: "pointer"} }

    const colDragElements = cols.filter(c=>c.props.canDrag).map(col=>$(GridCell,{
        ...pos("drag",col.props.colKey), onMouseDown: dragging.onMouseDown("x"), ...dragStyle,
    }, "o"))

    const dropElements = getDropElements(dragging)

    const expandedElements = getExpandedCells({
        rowKeys, children, cols: hideElementsForHiddenCols(true)(cols),
    }).map(([rowKey,pairs])=>(
        $(GridCell,{ ...pos(rowKey,spanAll), rowKeyMod: "-expanded", style: { display: "flex", flexFlow: "row wrap" } },
            pairs.map(([col,cell])=>(
                $("div",{key: col.key, style: { flexBasis: `${col.props.minWidth}em` }}, $("label",{},col.props.caption), cell)
            ))
        )
    ))

    const allChildren = toExpanderElements(hasHiddenCols)(toDraggingElements(dragging)(hideElementsForHiddenCols(false)([
        ...colDragElements, ...headElements, ...children, ...expandedElements, ...dropElements
    ])))

    useEffect(()=>{
        const {gridStart,axis} = dragging
        if(axis==="y") setExpandedItem(gridStart,v=>false)
    },[setExpandedItem,dragging])

    const res = $("div",{ style: { ...gridTemplateStyle }, ref: outerRef }, allChildren)
    return res
}/*,(a,b)=>{

    console.log(a.dragging===b.dragging)
    console.log(a.cols===b.cols)
    console.log(a.rowKeys===b.rowKeys)
    return false
}*/)

//// dragging

export const DocumentContext = createContext()

const useDocumentEventListener = (evName,callback) => {
    const doc = useContext(DocumentContext)
    useEffect(()=>{
        if(!callback) return undefined
        doc.addEventListener(evName,callback)
        return ()=>doc.removeEventListener(evName,callback)
    },[doc,evName,callback])
}

//

const toDraggingElements = dragging => wasChildren => {
    const {gridStart,axis} = dragging
    const onMouseDown = dragging.onMouseDown("y")
    const children = wasChildren.map(c=>c.props.isRowDragHandle?cloneElement(c,{onMouseDown}):c)
    if(!axis) return children
    const dragKey = switchAxis(c=>c.props.colKey, c=>c.props.rowKey)(axis)
    const toDrEl = toDraggingElement(axis)
    return map(c=>dragKey(c)===gridStart ? toDrEl(c) : c)(children)
}

const switchAxis = (xF,yF) => axis => (
    axis === "x" ? xF : axis === "y" ? yF : never()
)

const getClientPos = switchAxis(ev=>ev.clientX, ev=>ev.clientY)
const getScrollPos = switchAxis(w=>w.scrollX, w=>w.scrollY)
const getClientSize = switchAxis(el=>el.clientWidth, el=>el.clientHeight)
const stickyFrom = switchAxis("left", "top")
const stickyTo = switchAxis("right", "bottom")
const sizeAttr = switchAxis("width", "height")
const spanAllDir = switchAxis(pos(spanAll,"drop"),pos("drop",spanAll))

const getDragElementData = switchAxis(el => {
    const rect = el.getBoundingClientRect()
    return { gridStart: el.style.gridColumnStart, rectFrom: rect.left, rectTo: rect.right }
}, el => {
    const rect = el.getBoundingClientRect()
    return { gridStart: el.style.gridRowStart, rectFrom: rect.top, rectTo: rect.bottom }
})

const similarDragHandle = switchAxis(el => el.style.gridRowStart, el => el.style.gridColumnStart)

const getDraggingData = axis => ev => {
    if(!axis) return
    const {target} = ev
    const {parentElement} = target
    const drag = getDragElementData(axis)(target)
    if(!(parentElement && parentElement.style.display==="grid" && drag.gridStart)) return
    const clientPos = getClientPos(axis)(ev)
    const scrollPos = getScrollPos(axis)(ev.view)
    const clientSize = getClientSize(axis)(ev.view.document.documentElement)
    const dragHandleKey = similarDragHandle(axis)(target)
    const dropTo = [...parentElement.children].filter(
        el=>similarDragHandle(axis)(el)===dragHandleKey
    ).map(getDragElementData(axis)).find(r => (
        r.rectFrom < clientPos && clientPos < r.rectTo &&
        drag.gridStart!==r.gridStart
    ))
    return {axis,clientPos,scrollPos,clientSize,drag,dropTo}
}

const getDraggingRootStyle = (dragging,keys) => {
    const dPos = dragging.move.clientPos - dragging.start.clientPos
    const movedUp = dPos < dragging.start.scrollPos - dragging.move.scrollPos
    const varDragFrom = movedUp ? "" : (dragging.start.drag.rectFrom+dPos)+"px"
    const fromEnd = v => (dragging.move.clientSize - v)+"px"
    const varDragTo = fromEnd(dragging.start.drag.rectTo+dPos)
    //
    const {drag,dropTo} = dragging.move
    const order = dropTo && getDropOrder(keys,drag.gridStart,dropTo.gridStart)
    const linePos = !order ? "" : fromEnd(dropTo.gridStart === order[0] ? dropTo.rectTo : dropTo.rectFrom)
    return {
        "--drag-from":varDragFrom,
        "--drag-to":varDragTo,
        "--drop-display": linePos ? "":"none",
        "--drop-to": linePos,
    }
}

const toDraggingElement = axis => child => cloneElement(child,{style:{
    ...child.props.style,
    position:"sticky",
    [stickyFrom(axis)]: "var(--drag-from)",
    [stickyTo(axis)]: "var(--drag-to)",
}})

const getDropElements = dragging => dragging.axis ? [$(GridCell,{...spanAllDir(dragging.axis),style:{
    [sizeAttr(dragging.axis)]: "3px",
    backgroundColor:"red",
    position:"sticky",
    display: "var(--drop-display)",
    [stickyTo(dragging.axis)]: "var(--drop-to)",
}})] : []

const useGridDrag = ({drop,keysByAxis}) => {
    const [dragging,setDragging] = useState()
    const onMouseDown = useCallback(axis => ev => {
        const elements = getDraggingData(axis)(ev)
        if(!elements) return null
        setDragging({start:elements,move:elements})
    },[setDragging])
    const axis = dragging && dragging.start.axis
    const move = useCallback(ev=>{
        const elements = getDraggingData(axis)(ev)
        if(!elements) return null
        if(ev.buttons > 0){
            setDragging(was => was && {...was,move:elements})
        } else {
            setDragging(null)
            const {drag,dropTo} = elements
            if(dropTo) drop(axis,drag.gridStart,dropTo.gridStart)
        }
    },[setDragging,drop,axis])
    useDocumentEventListener("mousemove",dragging && move)
    useDocumentEventListener("mouseup",dragging && move)
    const keys = keysByAxis[axis]
    const rootStyle = useMemo(() => dragging ? getDraggingRootStyle(dragging,keys) : {}, [dragging,keys])
    const gridStart = dragging && dragging.start.drag.gridStart
    const draggingStart = useMemo(()=>({onMouseDown,axis,gridStart}),[onMouseDown,gridStart,axis])
    return [rootStyle,draggingStart]
}
