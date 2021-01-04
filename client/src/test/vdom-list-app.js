
import { GridRoot, GridCell, Highlighter } from "../main/vdom-list.js"
import { createSyncProviders } from "../main/vdom-hooks.js"
import React, { useEffect } from "react"
import {Text, LocationCell, StockCell, ProjectCell, NumMarkCell, ByCell, InputElement } from './MockData.jsx'
import { ImageElement } from "../temp/image.js"
import { getMaxTableWidthWithout } from "../main/vdom-util.js"
const { createElement: $ } = React

export default function VdomListElement({maxFilterAreaWidth, setMaxFilterAreaWidth, enableDrag, cols, setCols}) {
    const exCol =  (colKey, hideWill, min, max, caption) => ({
        colKey, hideWill, caption,
        width: { tp: "bound", min, max },
        ...(
            colKey === "expand" ? { isExpander: true } : {}
        )
    })

    useEffect(() => {
        setCols([
            exCol("icon", 2, 3, 5, "Icon"),
            exCol("c0", 1, 14, 30, "By"),
            exCol("expand", 0, 2, 2),
            exCol("c1", 1, 5, 10, "Project"),
            exCol("c2", 2, 5, 10, "Stock"),
            exCol("c3", 2, 15, 15, "Cargo"),
            exCol("input", 5, 7, 10, "From"),
            exCol("c5", 3, 5, 10, "Out"),
            exCol("c6", 2, 15, 30, "Remains"),
            exCol("c7", 2, 5, 10, "In"),
            exCol("c8", 1, 5, 10, "Container"),
            exCol("c9", 1, 5, 30, "Marking"),
            exCol("c10", 1, 7, 10, "Location"),
            enableDrag && exCol("drag", 0, 1.5, 3.5),
        ].filter(Boolean))
    }, [setCols, enableDrag])

    const exCell = rowKey => ({colKey, caption}) => {
        return colKey==="drag" && rowKey === "drag" ? null : $(GridCell, {
            key: ":" + rowKey + colKey, rowKey, colKey,
            ...(rowKey === "head" ? { className: "tableHeadContainer headerColor" } : {}),
            ...(rowKey === "drag" ? { dragHandle: "x", style: { userSelect: "none", cursor: "pointer" } } : {}),
            ...(colKey === "drag" ? { dragHandle: "y", style: { userSelect: "none", cursor: "pointer" } } : {}),
            ...(colKey === "expand" ? { expanding: "expander" } : {}),
            ...(colKey === "icon" ? { expanding: "none" } : {}),
            children: (
                rowKey === "head" ? (
                    colKey === "drag" || colKey === "expand" ? null : $(Text,{ key: "text", value: caption })
                ):
                rowKey === "drag" ? enableDrag && getColDragElement() :
                colKey === "expand" ? getExpanderElement() :
                colKey === "drag" ? enableDrag && getRowDragElement() :
                colKey === "input" ? InputCell() :
                colKey === "icon" ? "I" :
                colKey === "c0" ? ByCell() :
                colKey === "c1" ? ProjectCell() :
                colKey === "c2" ? StockCell() :
                colKey === "c8" ? getCargoIconCell() :
                colKey === "c9" ? NumMarkCell() :
                colKey === "c10" ? LocationCell() :
                $(Text, {value:colKey+rowKey, key:"defaultCell"})
            ) 
        })
    }

    function getExpanderElement() {
        return $(
            "div",
            { className: "textLineSize absolutelyCentered", key: "expanderElem" },
            $(ImageElement, { color: "#90929F", className: "expanderElement", src: '../temp/downarrowrow.svg' })
        )
    }

    function getColDragElement() {
        return $(
            "div",
            { className: "textLineSize absolutelyCentered", key: "dragElem" },
            $(ImageElement, { color: "adaptive", className: "dragElement", src: '../temp/drag.svg' })
        )
    }

    function getRowDragElement() {
        return $(
            "div",
            { className: "textLineSize absolutelyCentered", key: "dragElem" },
            $(ImageElement, { color: "adaptive", className: "dragElement", src: '../temp/drag.svg' })
        )
    }

    function InputCell(){
        return $(InputElement,{value:"", key:"tableInput"})
    }

    function getCargoIconCell() {
        return $(ImageElement, { className: "rowIconSize", src: '../temp/container.svg', key: "cargoIconCell" })
    }

    const rowKeys = [0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19].map(k => "r" + k)
    const byColumn = []
    
    useEffect(() => {
        setMaxFilterAreaWidth(getMaxTableWidthWithout(cols,["expand"]))
    }, [maxFilterAreaWidth, cols])
    const listEl = $(GridRoot, {
        key: "list",
        identity: {},
        cols,
        children: [
        ...(enableDrag ? cols.map(exCell("drag")).filter(Boolean) : []),
        ...cols.map(exCell("head")).filter(Boolean),
        ...rowKeys.flatMap(rowKey => cols.map(exCell(rowKey)).filter(Boolean)),
        ],
        rowKeys,
        setMaxFilterAreaWidth,
        maxFilterAreaWidth,
        enableDrag
    })
    const children = [
        listEl,
        $(Highlighter,{key:"row-highlighter", attrName:"data-row-key"}),
        $(Highlighter,{key:"col-highlighter", attrName:"data-col-key"}),
        // <div className="test">test </div>
    ]

    const sender = { enqueue: () => { } }
    const ack = null

    return createSyncProviders({ sender, ack, children })

}

/*const containerElement = document.createElement("div")
document.body.appendChild(containerElement)
ReactDOM.render(<App/>, containerElement)*/

/****
features:
    row drag
    col drag
    col hide
    col expand
to try: useSync for expanded

todo: resolve tag by key (exists), so remove ':'
****/