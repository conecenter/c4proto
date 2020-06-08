
import {createElement} from 'react'

export function ExampleComponents({Traverse}){
    const ExampleInput = prop => {
        const style = prop.changing ? {...prop.style, backgroundColor: "yellow"} : prop.style
        return createElement("input", {...prop, style}, null)
    }
    //
    const ContainerLeftRight = prop => {
        return createElement("table",{},createElement("tbody",{},createElement("tr",{},[
            createElement("td",{key:"left"},createElement(Traverse, {...prop.children, chl: prop.children.leftChildList})),
            createElement("td",{key:"right"},createElement(Traverse, {...prop.children, chl: prop.children.rightChildList}))
        ])))
    }
    //
    const transforms= {
        tp: ({ExampleInput,ContainerLeftRight})
    };
    return ({transforms});

}

export function ExampleAuth(pairOfInputAttributes){
    const ChangePassword = prop => {
        const [attributesA,attributesB] = pairOfInputAttributes(prop,{"x-r-auth":"change"})
        const button = attributesA.value && attributesA.value === attributesB.value ?
            createElement("input", {type:"button", onClick: prop.onBlur, value: "change"}, null) :
            null
        return createElement("div",{},[
            "New password ",
            createElement("input", {...attributesA, type:"password"}, null),
            ", again ",
            createElement("input", {...attributesB, type:"password"}, null),
            " ",
            button
        ])
    }
    const SignIn = prop => {
        const [attributesA,attributesB] = pairOfInputAttributes(prop,{"x-r-auth":"check"})
        return createElement("div",{},[
            "Username ",
            createElement("input", {...attributesA, type:"text"}, null),
            ", password ",
            createElement("input", {...attributesB, type:"password"}, null),
            " ",
            createElement("input", {type:"button", onClick: prop.onBlur, value: "sign in"}, null)
        ])
    }
    const transforms= {
        tp: {SignIn,ChangePassword}
    };
    return ({transforms});
}