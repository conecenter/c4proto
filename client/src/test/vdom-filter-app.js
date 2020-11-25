
import React from "react"
import ReactDOM from "react-dom"

const {createElement:$,useState,useMemo} = React

import {FilterArea,FilterButtonExpander,FilterItem,FilterButtonPlace,PopupManager} from "../main/vdom-filter.js"

function ModeButton({setState,dataKey}){
    return $("button",{ onClick: ev=>setState(was=>({...was,[dataKey]:!was[dataKey]})) }, dataKey)
}

function filterButton({key,minWidth,area,className,caption}){
    return $(FilterButtonPlace, {key,minWidth,area}, $("div",{className},caption))
}

function App(){
    const [state,setState] = useState({})
    const {noFilters,showSome0,showSome1} = state
    const identities = useMemo(()=>({lt:{},rt:{}}),[])

    const expander = $("div",{className:"exampleButton"})

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
                $(FilterButtonExpander,{ key: 0, minWidth: 2, area: "lt", identity: identities.lt, children: expander, optButtons: [
                    filterButton({ key: 3, minWidth: 4, className: "exampleButton", caption: "B" }),
                    filterButton({ key: 2, minWidth: 4, className: "exampleButton", caption: "B" }),
                ] }),
                filterButton({ key: 1, minWidth: 2, area: "lt", className: "exampleButton" }),
                filterButton({ key: 2, minWidth: 2, area: "rt", className: "exampleButton" }),
                filterButton({ key: 3, minWidth: 2, area: "rt", className: "exampleButton" }),
                $(FilterButtonExpander,{ key: 4, minWidth: 2, area: "rt", identity: identities.rt, children: expander, optButtons: [
                    filterButton({ key: 7, minWidth: 4, className: "exampleButton", caption: "B" }),
                    filterButton({ key: 6, minWidth: 3, className: "exampleButton", caption: "B" }),
                    filterButton({ key: 5, minWidth: 4, className: "exampleButton", caption: "B" }),
                ] }),
            ],
            centerButtonText: "of",
        }),
        "AFTER"
    )
}

const containerElement = document.createElement("div")
document.body.appendChild(containerElement)
ReactDOM.render($(App),containerElement)
