import { GridRoot, GridCell, GridCol } from "../../main/vdom-list.js"
import { createSyncProviders } from '../../main/vdom-hooks'
import { ImageElement } from '../image'
import React from 'react'
import HighlightProvider from '../../providers/HighlightProvider'
import MockData from "./MockData.js"

const { createElement: $, useState } = React

export default function VdomListElement() {
    const [state, setState] = useState({ enableColDrag: true })

    const { enableColDrag } = state

    const exCol = (colKey, hideWill, minWidth, maxWidth) => $(GridCol, {
        key: ":" + colKey, colKey, hideWill, minWidth, maxWidth,
        ...(
            colKey === "drag" ? {} : colKey === "expand" ? { isExpander: true, canDrag: enableColDrag } :
                { caption: "H" + colKey, canDrag: enableColDrag }
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
        exCol("drag", 0, 1.5, 1.5),
    ]
    const exCell = rowKey => col => 
    $(GridCell, {
        key: ":" + rowKey + col.key, rowKey, colKey: col.props.colKey,
        ...(col.props.colKey === "drag" ? { isRowDragHandle: true, style: { userSelect: "none", cursor: "pointer" } } : {}),
        ...(col.props.colKey === "expand" ? { isExpander: true } : {}),
        children: [
            col.props.colKey === "expand" ? getExpanderElement() :
            col.props.colKey === "drag" ? getDragElement() : MockData()
        ]
    })


    function getExpanderElement() {
        return $(
            "div",
            { className: "textLineSize absolutelyCentered", key: "expanderElem" },
            $(ImageElement, { color: "#90929F", className: "expanderElement", src: '/icons/downarrowrow.svg' })
        )
    }

    function getDragElement() {
        return $(
            "div",
            { className: "textLineSize absolutelyCentered", key: "dragElem" },
            $(ImageElement, { color: "adaptive", className: "dragElement", src: '/icons/drag.svg' })
        )
    }

    const rowKeys = [0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19].map(k => "r" + k)
    const byColumn = []
    const listEl = $(GridRoot, {
        key: "list",
        identity: {},
        cols,
        children: rowKeys.flatMap(rowKey => cols.map(exCell(rowKey))),
        rowKeys
    })
    const children = [
        $("button", { key: "colDrag", onClick: ev => setState(was => ({ ...was, enableColDrag: false })) }, "colDrag"),
        listEl,
        // <div className="test">test </div>
    ]

    const childrenWithHighlight = <HighlightProvider children={children}/>

    const sender = { enqueue: () => { } }
    const ack = null

    return createSyncProviders({ sender, ack, children: childrenWithHighlight })
}

        // const containerElement = document.createElement("div")
        // document.body.appendChild(containerElement)
        // ReactDOM.render($(App), containerElement)