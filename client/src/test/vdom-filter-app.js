
import React from "react"
import ReactDOM from "react-dom"

const {createElement:$,useState,useMemo} = React

import {FilterArea,FilterExpander,FilterItem,FilterButton,PopupContext} from "../main/vdom-filter.js"

function ModeButton({setState,dataKey}){
    return $("button",{ onClick: ev=>setState(was=>({...was,[dataKey]:!was[dataKey]})) }, dataKey)
}

function App(){
    const [state,setState] = useState({})
    const {noFilters,showAll} = state
    const canHide = !showAll
    const [popup,setPopup] = useState(null)
    const identities = useMemo(()=>({lt:{},rt:{}}),[])

    return $(PopupContext.Provider, {value:[popup,setPopup]},
        $(ModeButton,{ key: "showAll", setState, dataKey: "showAll" }),
        $(ModeButton,{ key: "noFilters", setState, dataKey: "noFilters" }),
        $("div",{style:{height:"10em"}},"BEFORE"),
        $(FilterArea,{ key: "app",
            filters: noFilters ? [] : [
                $(FilterItem,{ key: 1, children: "1?", minWidth: 5, maxWidth:10, canHide, className: "exampleFilterItem" }),
                $(FilterItem,{ key: 2, children: 2, minWidth: 10, maxWidth:10, className: "exampleFilterItem" }),
                $(FilterItem,{ key: 3, children: 3, minWidth: 5, maxWidth:10, className: "exampleFilterItem" }),
                $(FilterItem,{ key: 4, children: 4, minWidth: 5, maxWidth:10, className: "exampleFilterItem" }),
                $(FilterItem,{ key: 5, children: "5?", minWidth: 5, maxWidth:10, canHide, className: "exampleFilterItem" }),
            ],
            buttons: [
                $(FilterExpander,{ key: 0, minWidth: 2, area: "lt", identity: identities.lt, optButtons: [
                    $(FilterButton,{ key: 3, minWidth: 4, className: "exampleButton", caption: "B" }),
                    $(FilterButton,{ key: 2, minWidth: 4, className: "exampleButton", caption: "B" }),
                ] }),
                $(FilterButton,{ key: 1, minWidth: 2, area: "lt", className: "exampleButton" }),
                $(FilterButton,{ key: 2, minWidth: 2, area: "rt", className: "exampleButton" }),
                $(FilterButton,{ key: 3, minWidth: 2, area: "rt", className: "exampleButton" }),
                $(FilterExpander,{ key: 4, minWidth: 2, area: "rt", identity: identities.rt, optButtons: [
                    $(FilterButton,{ key: 7, minWidth: 4, className: "exampleButton", caption: "B" }),
                    $(FilterButton,{ key: 6, minWidth: 4, className: "exampleButton", caption: "B" }),
                    $(FilterButton,{ key: 5, minWidth: 4, className: "exampleButton", caption: "B" }),
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
