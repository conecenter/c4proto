

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

const sortedByPriority = sortedBy((a,b)=>b.props.priority-a.props.priority)

const excluding = keys => {
    const set = new Set(keys)
    return key => !set.has(key)
}

const useExpanded = () => {
    const [expanded,setExpanded] = useState({})
    const toggleExpanded = key => () => setExpanded(was => was[key] ? deleted({[key]:1})(was) : {...was, [key]:1} )
    return [expanded,toggleExpanded]
}

export function GridCell({children,rowKey,colKey,...props}){
    const isExpanded = colKey === "expanded"
    const gridRow = isExpanded ? `expanded${rowKey}` : rowKey
    const gridColumn = isExpanded ? "1 / -1" : colKey
    const style = { ...props.style, gridRow, gridColumn }
    return $("div",{...props,style},children)
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

const GridRootMemo = memo(({identity,children,rowKeys,cols,enableColDrag,enableRowDrag,dragging,applyDragRow,applyDragCol}) => {
    const {gridStart,onMouseDown,axis} = dragging


    const { ref: outerRef, width: outerWidth } = useWidth()
    const [expanded,toggleExpanded] = useExpanded()

    console.log("inner render")

    const sortedRowKeys = applyDragRow(rowKeys)


    const rowDragCol = $(GridCol,{colKey:"drag",minWidth:1,maxWidth:1})
    const expandCol = $(GridCol,{colKey:"expand",minWidth:1,maxWidth:1})

    const colByKey = Object.fromEntries(cols.map(c=>[c.props.colKey,c]))
    const byPriorityCols = [expandCol,rowDragCol,...sortedByPriority(cols)]
    const [visibleCols,hiddenCols] = partitionVisibleCols(byPriorityCols,outerWidth)
    const excludingColKeys = excluding(colKeysOf(hiddenCols))
    const sortedCols = applyDragCol(colKeysOf(cols)).filter(excludingColKeys).map(k=>colByKey[k])
    const hasHiddenCols = hiddenCols.length > 0
    console.log("hiddenCols.length "+hiddenCols.length)

    const gridTemplateRows = map(k=>`[${k}] auto`)([
        ...(enableColDrag ? ["drag"]:[]),
        "head",
        ...(!hasHiddenCols ? sortedRowKeys : sortedRowKeys.flatMap(k=>[k,`expanded${k}`])),
    ]).join(" ")

    const allCols = [
        ...(enableRowDrag ? [rowDragCol]:[]),
        ...sortedCols,
        ...(hasHiddenCols ? [expandCol]:[]),
    ]

    const gridTemplateColumns = map(c=>`[${c.props.colKey}] minmax(${c.props.minWidth}em,${hasHiddenCols?c.props.minWidth:c.props.maxWidth}em)`)(allCols).join(" ")

    const colDragElements = enableColDrag ? map(col=>$(GridCell,{
        ...pos("drag",col.props.colKey),
        onMouseDown: onMouseDown("x"),
        style: {userSelect: "none", cursor: "hand"}
    }, "o"))(cols) : []

    const headElements = map(col=>$(GridCell,{...pos("head",col.props.colKey)},col.props.caption))(cols)

    const rowDragElements = enableRowDrag ? map(rowKey=>$(GridCell,{
        ...pos(rowKey,"drag"),
        onMouseDown: onMouseDown("y"),
        style: {userSelect: "none", cursor: "hand"}
    }, "o"))(rowKeys) : []

    const expandElements = hasHiddenCols ? rowKeys.map(rowKey=>(
        $(GridCell,{...pos(rowKey,"expand")},$(ExpandButton,{isExpanded:expanded[rowKey],toggle:toggleExpanded(rowKey)}))
    )) : []

    const expandedElements = /*hasHiddenCols ? rowKeys.filter(k=>expanded[rowKey]).map(rowKey=>(
        $(GridCell,{ rowKey, colKey: "expanded" },

                                $("div",{key: cellKey},
                                    $("label",{},colByKey(cellKey).caption),
                                    $("span",{},cell.props.children)
                                )

        )
    )) : */ []

    const allChildren = [...headElements, ...colDragElements, ...rowDragElements, ...expandElements, ...expandedElements, ...children].filter(cell=>excludingColKeys(cell.props.colKey))

    const dragKey = axis && switchAxis(c=>c.props.colKey, c=>c.props.rowKey)(axis)
    const draggingChildren = gridStart ?
        map(c=>(
            dragKey(c)===gridStart ? toDraggingElement(axis)(c) : c
        ))(allChildren) :
        allChildren

    const res = $("div",{ style: { display: "grid", gridTemplateColumns, gridTemplateRows }, ref: outerRef }, draggingChildren)
    return res
})

function ExpandButton({isExpanded,toggle}){
    return $("div",{ onClick: ev => toggle() }, isExpanded ? "V":">")
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
