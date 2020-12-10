
import {PivotRoot,PivotCell} from "../main/vdom-pivot.js"

import ReactDOM from "react-dom"
import React from "react"

const { createElement: $ } = React

function App(){
    const className = "PivotCell"
    return $(PivotRoot,{
        cols: [
            { tp: "terminal", sliceKey: "c-head", width: { tp: "bound", min: 1, max: 10 } },
            { tp: "group", sliceKey: "c-body", slices: [
                { tp: "terminal", sliceKey: "c-body-0", width: { tp: "bound", min: 1, max: 20 } },
                { tp: "terminal", sliceKey: "c-body-1", width: { tp: "bound", min: 1, max: 30 } },
            ]}
        ],
        rows: [
            { tp: "terminal", sliceKey: "r-head", width: "unbound" },
            { tp: "group", sliceKey: "r-body", slices: [
                { tp: "terminal", sliceKey: "r-body-0", width: "unbound" },
                { tp: "terminal", sliceKey: "r-body-1", width: "unbound" },
            ]}
        ],
        children: [
            $(PivotCell,{ key: "1", colKey: "c-body", rowKey: "r-head", className, children: ["1"] }),
            $(PivotCell,{ key: "2", colKey: "c-head", rowKey: "r-body", className, children: ["2"] }),
            $(PivotCell,{ key: "3", colKey: "c-body-1", rowKey: "r-body-0", className, children: ["3"] }),
            $(PivotCell,{ key: "4", colKey: "c-body-0", rowKey: "r-body-1", className, children: ["4"] }),
        ],
    })
}

const containerElement = document.createElement("div")
document.body.appendChild(containerElement)
ReactDOM.render($(App), containerElement)
