"use strict"
import React from 'react'

export default function ElectronUpdateManager(log,window,metroUi,StatefulComponent){
	const {process,location,require,setInterval,clearInterval}  = window
	const getUpdateProgress = () => window.updateProgress
	const FlexGroup = metroUi.transforms.tp.FlexGroup
	let checked = false
	let announceOnce = false
	const $ = React.createElement
	const ProgressBar = props => {		
		const value = props.progress.val+"%"
		const text = props.progress.text
		const style = {
			position:"relative",
			height:"auto",
			margin:"0.625em 0.3125em 0.625em 0.3125em",			
			lineHeight:"1em",
			color:"black",
			backgroundColor:"#eeeeee",
			overflow:"hidden",
			boxSizing:"border-box"			
			}
		return $("div",{style},[
				$("span",{key:"t",style:{position:"absolute"}},props.progress.val>0?text:null),
				$("div",{key:"p",style:{width:value,height:"1em",backgroundColor:"#1ba1e2"}})
			])	 		
	}
	const StatusElement = ({children}) => {
		return React.createElement("div",{style:{height:"1.4em",marginTop:"1em",marginLeft:"0.25em"},key:3},children)
	}
	const checkActivateCalls=(()=>{
		const callbacks=[]
		const add = (c) => {
			callbacks.push(c)
			const remove = () => {
				const index = callbacks.indexOf(c)
				if(index>=0)
					callbacks.splice(index,1)
			}
			return {remove}
		}		
		const check = () => callbacks.forEach(c=>c())
		return {add,check}
	})();
	class UpdaterElement extends StatefulComponent{		
		getInitialState(){return {progress:[]}}
		update(){
			const obj = getUpdateProgress()
			if(obj == undefined || !Array.isArray(obj) || obj.length == 0) return checked = true			
			checked = false									
			if(obj.some(_=>{
				const a = this.state.progress.find(o=>o.text == _.text)
				return !a || a.val !=_.val
			})){				
				this.setState({progress:[...obj]})								
			}			
		}		
		componentWillMount(){
			if(!process) return checked = true
			if(process && !process.execPath.includes(this.props.termApp)) return checked = true
			if(getUpdateProgress() == undefined) return checked = true
		}
		componentDidMount(){
			const node = window.document.querySelector("#dev-content")
			const nodeOld = window.document.querySelector("#content")						
			while (node&&node.hasChildNodes()) node.removeChild(node.lastChild)		
			while (nodeOld&&nodeOld.hasChildNodes()) nodeOld.removeChild(nodeOld.lastChild)	
			this.bind = checkActivateCalls.add(this.update)			
		}
		componentWillUnmount(){
			if(this.bind) this.bind.remove()			
		}
		render(){			
			const style = {		
				position:"fixed",
				top:"0",
				zIndex:"12000",
				width:"100%",
				...this.props.style
			}
			const dialogStyle = {				
				margin:"3.5rem auto 0em auto",
				maxWidth:"30em",				
				backgroundColor:"white",
				...this.props.dialogStyle
			}
			const status = this.props.status
			const t = (!checked)?$("div",{key:"update",style,},$(FlexGroup,{style:dialogStyle,caption:"",key:"updated"},[
					$(StatusElement,{key:"status"},status),
					...this.state.progress.map((prg,i)=>$(ProgressBar,{key:i,progress:prg}))
				])):null			
			return [t,$("div",{key:"kids"},this.props.children)]
		}
	}
	
	const transforms = {
		tp:{UpdaterElement}
	}
	const checkActivate = checkActivateCalls.check
	return {transforms,checkActivate}
}