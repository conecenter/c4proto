
import {createElement as $} from 'react'
import {identityAt} from "../main/vdom-util.js"
import {useSender} from "../main/vdom-hooks.js"
import {useSyncInput} from "../main/vdom-core.js"

const notDefer = _=>false
const changeIdOf = identityAt('change')
const activateIdOf = identityAt('activate')

function ExampleText({value}){
    return $("span",{},value)
}

function ExampleInput({identity,value}){
    const patch = useSyncInput(changeIdOf(identity), value, notDefer)
    return $("input",{...patch})
}

function ExampleButton({identity,caption}){
    const sender = useSender()
    return $("button",{ onClick: ev=>sender.enqueue(activateIdOf(identity),{}) }, caption)
}

export const components = {ExampleText,ExampleInput,ExampleButton}