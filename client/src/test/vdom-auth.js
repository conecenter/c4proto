
import React from 'react'

export function ExampleAuth(pairOfInputAttributes){
    const ChangePassword = prop => {
        const [attributesA,attributesB] = pairOfInputAttributes(prop,{"X-r-auth":"change"})
        const button = attributesA["data-value"] && attributesA["data-value"] === attributesB["data-value"] ?
            React.createElement("input", {type:"button", onClick: prop.onBlur, value: "change"}, null) :
            null
        return React.createElement("div",{},[
            "New password ",
            React.createElement("input", {...attributesA, type:"password"}, null),
            ", again ",
            React.createElement("input", {...attributesB, type:"password"}, null),
            " ",
            button
        ])
    }
    const SignIn = prop => {
        const [attributesA,attributesB] = pairOfInputAttributes(prop,{"X-r-auth":"check"})
        return React.createElement("div",{},[
            "Username ",
            React.createElement("input", {...attributesA, type:"text"}, null),
            ", password ",
            React.createElement("input", {...attributesB, type:"password"}, null),
            " ",
            React.createElement("input", {type:"button", onClick: prop.onBlur, value: "sign in"}, null)
        ])
    }
    const transforms= {
        tp: {SignIn,ChangePassword}
    };
    return ({transforms});
}