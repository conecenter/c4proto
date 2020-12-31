
import React, { useCallback} from "react"
const { createElement: $, useState, useMemo } = React

import { FilterArea, FilterButtonExpander, FilterItem, FilterButtonPlace, PopupManager } from "../main/vdom-filter.js"
import { getMaxTableWidthWithout } from "../main/vdom-util.js"
import { ImageElement } from "../temp/image.js"
import { ButtonElement } from "./MockData.jsx"
import VdomListElement from "./vdom-list-app.js"

function ModeButton({ setState, dataKey, area, caption, BGcolor }) {
    const res = filterButton({ onClick: ev => setState(was => ({ ...was, [dataKey]: !was[dataKey] })), area, caption, BGcolor, key: dataKey }, dataKey)
    return res
}

function filterButton({ key, area, caption, onClick, BGcolor }) {
    const res = $(FilterButtonPlace, { key, area }, $(ButtonElement, { caption, onClick, BGcolor: BGcolor || "primary" }))
    return res
}

export default function VdomApp() {
    const [state, setState] = useState({})
    const [maxFilterAreaWidth, setMaxFilterAreaWidth] = useState(0)
    const [cols, setCols] = useState([])
    const { noFilters, showSome0, showSome1, enableDrag = true} = state
    const identities = useMemo(() => ({ lt: {}, rt: {} }), [])

   /* const expander = {
        children: [$("div", { key: "closedExpander", className: "exampleButton closedExpander" }, "v")],
        openedChildren: [$("div", { key: "openedExpander", className: "exampleButton openedExpander" }, "v")],
    }*/
    
    const expanderSVG = () => $(ImageElement, {src:"../temp/filterbutton.svg", className:"filterButtonIcon", key:"filterbuttonFiltersImage", color:"#ffffff"})

    const expander = {
        children: [filterButton({ key: "closedExpander", className: "exampleButton closedExpander",
            caption: expanderSVG() })],
        openedChildren: [filterButton({ key: "openedExpander", className: "exampleButton openedExpander",
            caption: expanderSVG() })],
    }
    
    const dragOffClick = useCallback(() => {
        const hidedColNames = ["expand"]
        enableDrag && hidedColNames.push("drag")
        setMaxFilterAreaWidth(getMaxTableWidthWithout(cols, hidedColNames))
        setState(was => ({ ...was, enableDrag: !enableDrag }))
    }, [cols,setCols,enableDrag])

    return $(PopupManager, {},
        $(FilterArea, {
            key: "app",
            maxFilterAreaWidth,
            filters: noFilters ? [] : [
                $(FilterItem, { key: 1, value: "Number/Marking", children: "1 1", minWidth: 5, maxWidth: 10, canHide: !showSome1 }),
                $(FilterItem, { key: 2, value: "Location", children: "2 0", minWidth: 10, maxWidth: 10, canHide: !showSome0 }),
                $(FilterItem, { key: 3, value: "Location Feature", children: "3 0", minWidth: 5, maxWidth: 10, canHide: !showSome0 }),
                $(FilterItem, { key: 4, value: "Mode", children: "4 0", minWidth: 5, maxWidth: 10, canHide: !showSome0 }),
                $(FilterItem, { key: 5, value: "From", children: "5 1", minWidth: 5, maxWidth: 10, canHide: !showSome1 }),
            ],
            buttons: [
                $(FilterButtonExpander, {
                    key: 6, area: "lt", identity: identities.lt, ...expander, popupClassName: "gridPopup",  popupItemClassName: "popupItem", optButtons: [
                        ModeButton({ key: "show all 0", setState, dataKey: "showSome0", caption: "show all 0" }),
                        ModeButton({ key: "show all 1", setState, dataKey: "showSome1", caption: "show all 1"  }),
                        filterButton({ key: "dragOff", onClick: dragOffClick, caption: (enableDrag ? "disable" : "enable") + " drag",
                        BGcolor: enableDrag ? "lightPrimary" : "green" }),
                    ]
                }),
                filterButton({ key: 9, area: "lt", caption: "20" }),
                filterButton({ key: 0, area: "rt", caption: "of", BGcolor: "green" }),
                filterButton({ key: 10, area: "rt", caption: "20" }),
                ModeButton({ key: "noFilters", area: "rt", setState, dataKey: "noFilters", 
                caption: $(ImageElement, {src:"../temp/hidefilters.svg", className:"hideFilterIcon", key:"hideFiltersImage", color:"#ffffff"}) }),
            ],
            className: "filterArea darkPrimaryColor",
        }),
        $(VdomListElement, {maxFilterAreaWidth, setMaxFilterAreaWidth, enableDrag, cols, setCols})
    )
}

/*const containerElement = document.createElement("div")
document.body.appendChild(containerElement)
ReactDOM.render($(App), containerElement)*/
