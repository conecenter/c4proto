
import React from "react"
import ReactDOM from "react-dom"

const {createElement:$,useState,useMemo} = React

import {FilterArea,FilterButtonExpander,FilterItem,FilterButtonPlace,PopupManager} from "../main/vdom-filter.js"

function ModeButton({setState,dataKey}){
    return $("button",{ onClick: ev=>setState(was=>({...was,[dataKey]:!was[dataKey]})) }, dataKey)
}

function filterButton({key,area,className,caption}){
    const res = $(FilterButtonPlace, {key,area}, $("div",{className},caption))
    return res
}

function App(){
    const [state,setState] = useState({})
    const {noFilters,showSome0,showSome1} = state
    const identities = useMemo(()=>({lt:{},rt:{}}),[])

    const expander = {
        children: [$("div",{key:"closedExpander",className:"exampleButton closedExpander"},"v")],
        openedChildren: [$("div",{key:"openedExpander",className:"exampleButton openedExpander"},"v")],
    }

    return $(PopupManager, {},
        $(ModeButton,{ key: "show all 0", setState, dataKey: "showSome0" }),
        $(ModeButton,{ key: "show all 1", setState, dataKey: "showSome1" }),
        $(ModeButton,{ key: "noFilters", setState, dataKey: "noFilters" }),
        $("div",{style:{height:"10em"}},"BEFORE"),
        $(FilterArea,{ key: "app",
            filters: noFilters ? [] : [
                $(FilterItem,{ key: 1, children: "1 1", minWidth: 5, maxWidth:10, canHide: !showSome1, className: "exampleFilterItem" }),
                $(FilterItem,{ key: 2, children: "2 0", minWidth: 10, maxWidth:10, canHide: !showSome0, className: "exampleFilterItem" }),
                $(FilterItem,{ key: 3, children: "3 0", minWidth: 5, maxWidth:10, canHide: !showSome0, className: "exampleFilterItem" }),
                $(FilterItem,{ key: 4, children: "4 0", minWidth: 5, maxWidth:10, canHide: !showSome0, className: "exampleFilterItem" }),
                $(FilterItem,{ key: 5, children: "5 1", minWidth: 5, maxWidth:10, canHide: !showSome1, className: "exampleFilterItem" }),
            ],
            buttons: [
                $(FilterButtonExpander,{ key: 6, area: "lt", identity: identities.lt, ...expander, optButtons: [
                    filterButton({ key: 7, className: "exampleButton", caption: "BigButton" }),
                    filterButton({ key: 8, className: "exampleButton", caption: "BigButton" }),
                ] }),
                filterButton({ key: 9, area: "lt", className: "exampleButton", caption: "A1" }),
                filterButton({ key: 0, area: "rt", className: "exampleButton", caption: "of" }),
                filterButton({ key: 10, area: "rt", className: "exampleButton", caption: "A2" }),
                filterButton({ key: 11, area: "rt", className: "exampleButton", caption: "A3" }),
                $(FilterButtonExpander,{ key: 12, area: "rt", identity: identities.rt, ...expander, optButtons: [
                    filterButton({ key: 13, className: "exampleButton", caption: "BigButton" }),
                    filterButton({ key: 14, className: "exampleButton", caption: "BigButton" }),
                    filterButton({ key: 15, className: "exampleButton", caption: "BigButton" }),
                ] }),
            ],
            className: "filterArea",
        }),
        "AFTER"
    )
}

const containerElement = document.createElement("div")
document.body.appendChild(containerElement)
ReactDOM.render($(App),containerElement)
