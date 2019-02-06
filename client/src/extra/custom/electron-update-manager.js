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
		const value = props.progress+"%"
		return $("div",{style:{position:"relative",height:"auto",margin:"0.625em 0.3125em 0.625em 0.3125em",backgroundColor:"#eeeeee",overflow:"hidden",boxSizing:"border-box",marginBottom:"1.5em"}},
			$("div",{style:{width:value,height:"1em",float:"left",textAlign:"center",lineHeight:"1em",backgroundColor:"#1ba1e2"},color:"black"},props.progress>0?value:null)
		)	 		
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
		getInitialState(){return {progress:0,text:""}}
		update(){
			const obj = getUpdateProgress()			
			if(obj!= undefined) {
				checked = false
				const prg = obj.val
				if(this.state.progress!=prg){
					this.setState({progress:prg,text:obj.text})				
				}
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
				...this.props.style
			}
			const dialogStyle = {				
				margin:"3.5rem auto 0em auto",
				maxWidth:"30em",
				...this.props.dialogStyle
			}
			if(checked) return $("div",{style},this.props.children)
			const status = `${this.props.status} ${this.state.progress>0?" downloading: "+this.state.text:""}`
			return $(FlexGroup,{style:dialogStyle,caption:""},[
				$(StatusElement,{key:1},status),
				$(ProgressBar,{key:2,progress:this.state.progress})
			])
		}
	}
	
	const transforms = {
		tp:{UpdaterElement}
	}
	const checkActivate = checkActivateCalls.check
	return {transforms,checkActivate}
}