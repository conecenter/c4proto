"use strict";
import React 	from 'react'
import ReactDOM from 'react-dom'
import PureRenderMixin from 'react/lib/ReactComponentWithPureRenderMixin'

export default function Errors({log,uiElements,bodyManager}){
	const callbacks = [];
	let tmpNode = null;	
	const $ = React.createElement	
	const reg = (callback) => {
		callbacks.push(callback)
		const unreg = ()=> {
			const index = callbacks.findIndex(c => c == callback)
			callbacks.splice(index,1)
		}
		return {unreg}
	}
	const fail = (data)=> {
		if(callbacks.length>0)
			callbacks.forEach(c=>c(data))
		else
			showError(data)
	}
	const showError = (data) => {		
		tmpNode = bodyManager.createElement("div")
		tmpNode.className="ouch"
		bodyManager.addFirst(tmpNode)
		const onClick = () => {
			ReactDOM.unmountComponentAtNode(tmpNode)
			bodyManager.remove(tmpNode)
			tmpNode = null
		}		
		const ErrorElement = uiElements[0].ErrorElement
		const rootErrorElement = $(ErrorElement,{onClick,data})
		ReactDOM.render(rootErrorElement,tmpNode)
	}
	const receivers = {fail};	
	return {receivers,reg};
}