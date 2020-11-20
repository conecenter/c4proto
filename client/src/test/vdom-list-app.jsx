
import { GridRoot, GridCell, Highlighter } from "../main/vdom-list.js"
import { createSyncProviders } from "../main/vdom-hooks.js"
import ReactDOM from "react-dom"
import React from "react"

const { createElement: $, useState } = React

function ImageElement({src,className}){
    return $("img",{src,className,draggable:"false"})
}

function mockData() {
    const srlIcon = $(ImageElement,{ src: "../temp/servicerequestline.svg", className: "rowIconSize", key: "image" })
    const meleqStr = $(Text,{ value: "MELEQ 11-Oct ● Vessel load" })
    const row = $("div",{ className: "descriptionRow", key: "row" }, srlIcon, meleqStr)
    return row
}

function Text({ value }) {
    return value
}

function App() {
    const [state, setState] = useState({ enableDrag: true })

    const { enableDrag } = state

    const exCol = (colKey, hideWill, minWidth, maxWidth) => ({
        colKey, hideWill, minWidth, maxWidth, ...(
            colKey === "expand" ? { isExpander: true } : {}
        )
    })

    const cols = [
        exCol("c0", 1, 5, 10),
        exCol("expand", 0, 2, 2),
        exCol("c1", 1, 5, 10),
        exCol("c2", 2, 5, 10),
        exCol("c3", 2, 15, 15),
        exCol("c4", 3, 5, 10),
        exCol("c5", 3, 5, 10),
        exCol("c6", 2, 15, 30),
        exCol("c7", 2, 5, 10),
        exCol("c8", 1, 5, 10),
        exCol("c9", 1, 5, 30),
        enableDrag && exCol("drag", 0, 1.5, 1.5),
    ].filter(Boolean)
    const exCell = rowKey => ({colKey}) => {
        return colKey==="drag" && rowKey === "drag" ? null : $(GridCell, {
            key: ":" + rowKey + colKey, rowKey, colKey,
            ...(rowKey === "head" ? { className: "tableHeadContainer headerColor" } : {}),
            ...(rowKey === "drag" ? { dragHandle: "x", style: { userSelect: "none", cursor: "pointer" } } : {}),
            ...(colKey === "drag" ? { dragHandle: "y", style: { userSelect: "none", cursor: "pointer" } } : {}),
            ...(colKey === "expand" ? { isExpander: true } : {}),
            children: (
                rowKey === "head" ? (
                    colKey === "drag" || colKey === "expand" ? null : $(Text,{ key: "text", value: "H" + colKey })
                ):
                rowKey === "drag" ? enableDrag && getColDragElement() :
                colKey === "expand" ? getExpanderElement() :
                colKey === "drag" ? enableDrag && getRowDragElement() :
                mockData()
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

    const rowKeys = [0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19].map(k => "r" + k)
    const byColumn = []
    const listEl = $(GridRoot, {
        key: "list",
        identity: {},
        cols,
        children: [
            ...(enableDrag ? cols.map(exCell("drag")).filter(Boolean) : []),
            ...cols.map(exCell("head")).filter(Boolean),
            ...rowKeys.flatMap(rowKey => cols.map(exCell(rowKey)).filter(Boolean)),
        ],
        rowKeys
    })
    const children = [
        $("button", { key: "dragOff", onClick: ev => setState(was => ({ ...was, enableDrag: false })) }, "no drag"),
        listEl,
        $(Highlighter,{key:"row-highlighter", attrName:"data-row-key"}),
        $(Highlighter,{key:"col-highlighter", attrName:"data-col-key"}),
        // <div className="test">test </div>
    ]

    const sender = { enqueue: () => { } }
    const ack = null

    return createSyncProviders({ sender, ack, children })

}

const containerElement = document.createElement("div")
document.body.appendChild(containerElement)
ReactDOM.render(<App/>, containerElement)

/****
features:
    row drag
    col drag
    col hide
    col expand
to try: useSync for expanded

todo: resolve tag by key (exists), so remove ':'
****/