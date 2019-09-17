
import React from 'react'

export function ExampleComponents(components){
    const {ReControlledInput} = components
    const ExampleInput = prop => {
        const style = prop.changing ? {...prop.style, backgroundColor: "yellow"} : prop.style
        return React.createElement(ReControlledInput, {...prop, style}, null)
    }
    const transforms= {
        tp: ({ExampleInput})
    };
    return ({transforms});

}

export function ExampleAuth(pairOfInputAttributes,components){
    const {ReControlledInput} = components
    const ChangePassword = prop => {
        const [attributesA,attributesB] = pairOfInputAttributes(prop,{"x-r-auth":"change"})
        const button = attributesA.value && attributesA.value === attributesB.value ?
            React.createElement("input", {type:"button", onClick: prop.onBlur, value: "change"}, null) :
            null
        return React.createElement("div",{},[
            "New password ",
            React.createElement(ReControlledInput, {...attributesA, type:"password"}, null),
            ", again ",
            React.createElement(ReControlledInput, {...attributesB, type:"password"}, null),
            " ",
            button
        ])
    }
    const SignIn = prop => {
        const [attributesA,attributesB] = pairOfInputAttributes(prop,{"x-r-auth":"check"})
        return React.createElement("div",{},[
            "Username ",
            React.createElement(ReControlledInput, {...attributesA, type:"text"}, null),
            ", password ",
            React.createElement(ReControlledInput, {...attributesB, type:"password"}, null),
            " ",
            React.createElement("input", {type:"button", onClick: prop.onBlur, value: "sign in"}, null)
        ])
    }
    const transforms= {
        tp: {SignIn,ChangePassword}
    };
    return ({transforms});
}