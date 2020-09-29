

import {createElement as $, useMemo, useState, useLayoutEffect, cloneElement, createContext, useCallback, useContext, useEffect, memo} from "../main/react-prod.js"

import {map,head as getHead,identityAt,deleted,weakCache} from "../main/vdom-util.js"
import {useSync} from "../main/vdom-core.js"

const dragRowIdOf = identityAt('dragRow')
const dragColIdOf = identityAt('dragCol')

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

const sortedBy = f => l => l && [...l].sort(f)

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

const sortedByHideWill = sortedBy((a,b)=>a.props.hideWill-b.props.hideWill)




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



const getGridRow = ({rowKey,rowKeyMod}) => rowKey+(rowKeyMod||'')

export function GridCell({children,rowKey,rowKeyMod,colKey,isExpander,expander,isRowDragHandle,...props}){
    const gridRow = getGridRow({rowKey,rowKeyMod})
    const gridColumn = colKey === "all" ? "1 / -1" : colKey
    const style = { ...props.style, gridRow, gridColumn }
    const expanderProps = isExpander ? {'data-expander':expander||'passive'} : {}
    return $("div",{...props,...expanderProps,style},children)
}

const pos = (rowKey,colKey)=>({ key: rowKey+colKey, rowKey, colKey })

//

const applyPatches = patches => value => { //memo?
    return patches.reduce((acc,{headers:{ "x-r-drag-key": from, "x-r-drop-key": to }})=>{
        const fromIndex = acc.indexOf(from)
        const toIndex = acc.indexOf(to)
        if(fromIndex < 0 || toIndex < 0 || fromIndex === toIndex) return acc
        const order = fromIndex < toIndex ? [to,from] : [from,to]
        return acc.flatMap(key => key===from ? [] : key===to ? order : [key])
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
    const doApplyPatches = useCallback(applyPatches(patches),[patches])
    return [doApplyPatches,drop]
}

//

const colKeysOf = children => children.map(c=>c.props.colKey)

export function GridCol(props){
    return []
}

export function GridRoot(props){
    const {identity,rowKeys,cols} = props
    const [applyDragRow,dropRow] = useSortRoot(dragRowIdOf(identity),rowKeys)
    const colKeys = useMemo(()=>colKeysOf(cols))
    const [applyDragCol,dropCol] = useSortRoot(dragColIdOf(identity),colKeys)
    const drop = useCallback((axis,from,to)=>switchAxis(dropCol,dropRow)(axis)(from,to), [dropCol,dropRow])
    const [style,dragging] = useGridDrag(drop)
    return $("div",{style},$(GridRootMemo,{...props,dragging,applyDragRow,applyDragCol}))
}

/*const childByKey = children => {
    const res = Object.fromEntries(children.map(c=>[c.key,c]))
    return k => Array.isArray(k) ? k.map(ck=>res[ck]) : res[k]
}*/

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

const wrapApplyDragCol = f => cols => {
    const colByKey = Object.fromEntries(cols.map(c=>[c.props.colKey,c]))
    return f(colKeysOf(cols)).map(k=>colByKey[k])
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

const expandRowKeys = rowKeys => rowKeys.flatMap(rowKey=>[{rowKey},{rowKey,rowKeyMod:"-expanded"}])



const GridRootMemo = memo(({identity,children,rowKeys,cols,dragging,applyDragRow,applyDragCol}) => {
    console.log("inner render")

    const {toExpanderElements,setExpandedItem,getExpandedCells} = useExpanded()

    const {outerRef,hasHiddenCols,hideElementsForHiddenCols,toNarrowCols} =
        useHiddenCols(cols)

    const allCols = hideElementsForHiddenCols(false)(wrapApplyDragCol(applyDragCol)(cols))

    const gridTemplateStyle = getGridTemplateStyle({
        rows: [{rowKey:"drag"}, {rowKey:"head"}, ...expandRowKeys(applyDragRow(rowKeys))],
        columns: toNarrowCols(allCols),
    })

    const headElements = map(col=>$(GridCell,{...pos("head",col.props.colKey)},col.props.caption))(cols)

    const dragStyle = { style: {userSelect: "none", cursor: "hand"} }

    const colDragElements = cols.filter(c=>c.props.canDrag).map(col=>$(GridCell,{
        ...pos("drag",col.props.colKey), onMouseDown: dragging.onMouseDown("x"), ...dragStyle,
    }, "o"))

    const expandedElements = getExpandedCells({
        rowKeys, children, cols: hideElementsForHiddenCols(true)(cols),
    }).map(([rowKey,pairs])=>(
        $(GridCell,{ ...pos(rowKey,"all"), rowKeyMod: "-expanded", style: { display: "flex", flexFlow: "row wrap" } },
            pairs.map(([col,cell])=>(
                $("div",{key: col.key, style: { flexBasis: `${col.props.minWidth}em` }}, $("label",{},col.props.caption), cell)
            ))
        )
    ))

    const allChildren = toExpanderElements(hasHiddenCols)(toDraggingElements(dragging)(hideElementsForHiddenCols(false)([
        ...colDragElements, ...headElements, ...children, ...expandedElements
    ])))

    useEffect(()=>{
        const {gridStart,axis} = dragging
        if(axis==="y") setExpandedItem(gridStart,v=>false)
    },[setExpandedItem,dragging])

    const res = $("div",{ style: { ...gridTemplateStyle }, ref: outerRef }, allChildren)
    return res
})

const toDraggingElements = dragging => wasChildren => {
    const {gridStart,axis} = dragging
    const onMouseDown = dragging.onMouseDown("y")
    const children = wasChildren.map(c=>c.props.isRowDragHandle?cloneElement(c,{onMouseDown,isRowDragHandle:undefined}):c)
    if(!axis) return children
    const dragKey = switchAxis(c=>c.props.colKey, c=>c.props.rowKey)(axis)
    const toDrEl = toDraggingElement(axis)
    return map(c=>dragKey(c)===gridStart ? toDrEl(c) : c)(children)
}

//

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

const switchAxis = (xF,yF) => axis => (
    axis === "x" ? xF : axis === "y" ? yF : never()
)

const getGridStart = switchAxis(el=>el.style.gridColumnStart, el=>el.style.gridRowStart)
const getClientPos = switchAxis(ev=>ev.clientX, ev=>ev.clientY)
const getScrollPos = switchAxis(ev=>ev.view.scrollX, ev=>ev.view.scrollY)
const getClientSize = switchAxis(ev=>ev.view.document.documentElement.clientWidth, ev=>ev.view.document.documentElement.clientHeight)
const getRectRange = switchAxis(rect=>({from:rect.left,to:rect.right}), rect=>({from:rect.top,to:rect.bottom}))
const stickyArgs = switchAxis((from,to)=>({left:from,right:to}), (from,to)=>({top:from,bottom:to}))

const getDraggingData = axis => ev => ({
    clientPos: getClientPos(axis)(ev),
    scrollPos: getScrollPos(axis)(ev),
    clientSize: getClientSize(axis)(ev)
})

const getDraggingElements = axis => ev => {
    const {target} = ev
    const {parentElement} = target
    const gridStart = getGridStart(axis)(target)
    return (
        parentElement && parentElement.style.display==="grid" && gridStart &&
        {target,parentElement,gridStart}
    )
}

const startDraggingFromEvent = axis => ev => {
    const elements = getDraggingElements(axis)(ev)
    if(!elements) return null
    const {target,parentElement,gridStart} = elements
    const rectRange = getRectRange(axis)(target.getBoundingClientRect())
    const move = getDraggingData(axis)(ev)
    return {start:{axis,gridStart,rectRange,...move},move}
}

const dropFromEvent = axis => ev => doDrop => {
    const elements = getDraggingElements(axis)(ev)
    if(!elements) return null
    const {target,parentElement,gridStart} = elements
    const move = getDraggingData(axis)(ev)
    const toElements = [...parentElement.children].filter(el=>{
        const rectRange = getRectRange(axis)(el.getBoundingClientRect())
        return rectRange.from < move.clientPos && move.clientPos < rectRange.to
    })
    const fromTos = toElements.map(el=>[axis,gridStart,getGridStart(axis)(el)])
    const fromTo =  fromTos.find(([axis,from,to]) => from!==to)
    if(fromTo) doDrop(...fromTo)
}

const getDraggingRootStyle = dragging => {
    const dPos = dragging.move.clientPos - dragging.start.clientPos
    const movedUp = dPos < dragging.start.scrollPos - dragging.move.scrollPos
    const varFrom = movedUp ? "" : (dragging.start.rectRange.from+dPos)+"px"
    const fromEnd = v => dragging.move.clientSize - v
    const varTo = fromEnd(dragging.start.rectRange.to+dPos)+"px"
    return {"--drag-from":varFrom,"--drag-to":varTo}
}

const toDraggingElement = axis => child => cloneElement(child,{style:{
    ...child.props.style,
    position: "sticky",
    ...stickyArgs(axis)("var(--drag-from)","var(--drag-to)"),
}})

const useGridDrag = onDrop => {
    const [dragging,setDragging] = useState()
    const onMouseDown = useCallback(axis => ev => {
        setDragging(startDraggingFromEvent(axis)(ev))
    },[])
    const axis = dragging && dragging.start.axis
    const move = useCallback(ev=>{
        if(ev.buttons > 0){
            const move = getDraggingData(axis)(ev)
            setDragging(was => was && {...was,move})
        } else {
            setDragging(null)
            dropFromEvent(axis)(ev)(onDrop)
        }
    },[onDrop,axis])
    useDocumentEventListener("mousemove",dragging && move)
    useDocumentEventListener("mouseup",dragging && move)
    const rootStyle = useMemo(() => dragging ? getDraggingRootStyle(dragging) : {}, [dragging])
    const gridStart = dragging && dragging.start.gridStart
    const draggingStart = useMemo(()=>({onMouseDown,axis,gridStart}),[onMouseDown,gridStart,axis])
    return [rootStyle,draggingStart]
}
