"use strict";
import React from 'react'

export default function VirtualKeyboard({log,svgSrc,focusModule,eventManager,windowManager,miscReact,StatefulComponent}){
	const $ = React.createElement	
	const checkActivateCalls=(()=>{
		const callbacks=[]
		const add = (c) => callbacks.push(c)
		const remove = (c) => {
			const index = callbacks.indexOf(c)
			callbacks.splice(index,1)
		}
		const check = () => callbacks.forEach(c=>c())
		return {add,remove,check}
	})();
	const {getReactRoot} = miscReact
	const {setTimeout,getPageYOffset,getWindowRect} = windowManager
	const GlobalStyles = (()=>{
		let styles = {
			outlineWidth:"0.04em",
			outlineStyle:"solid",
			outlineColor:"blue",
			outlineOffset:"-0.1em",
			boxShadow:"0 0 0.3125em 0 rgba(0, 0, 0, 0.3)",
			borderWidth:"1px",
			borderStyle:"solid",
			borderSpacing:"0em",
		}
		const update = (newStyles) => styles = {...styles,...newStyles}
		return {...styles,update};
	})()
	class VKButton extends StatefulComponent{		
		getInitialState(){return {mouseDown:false}}
		onClick(ev){
			if(this.props.fkey) eventManager.sendToWindow(eventManager.create("keydown",{key:this.props.fkey,bubbles:true,code:"vk"}))
			if(this.props.onClick) this.props.onClick(ev)			
		}
		onTouchStart(e){
			this.setState({mouseDown:true})
		}
		onMouseDown(e){
			this.setState({mouseDown:true})
			this.onClick(e)
			e.preventDefault()
		}
		onMouseUp(){this.setState({mouseDown:false})}
		render(){					
			const bStyle={
				height:'2.2em',
				width:'2em',
				fontSize:"1em",
				border:`${GlobalStyles.borderWidth} ${GlobalStyles.borderStyle}`,
				fontStyle:'inherit',				
				backgroundColor:'#eeeeee',
				verticalAlign:'middle',
				overflow:"hidden",
				outline:(this.state.mouseDown?`${GlobalStyles.outlineWidth} ${GlobalStyles.outlineStyle} ${GlobalStyles.outlineColor}`:'none'),				
				color:'inherit',
				padding:"0px",
				textAlign:'center',
				...this.props.style,				
				...(this.state.mouseDown?{backgroundColor:"rgb(25, 118, 210)"}:{})
			};
			const className = "vkElement"
			return $("button",{style:bStyle,className,onTouchStart:this.onTouchStart,onTouchEnd:this.onMouseUp,onMouseDown:this.onMouseDown,onMouseUp:this.onMouseUp},this.props.children);
		}
	}
	class VirtualKeyboard extends StatefulComponent{
		getInputVKType(){						
			const layoutAlpha = "layoutAlpha"
			const layoutNumeric = "layoutNumeric"
			const input = this.getInput()
			let vkType = input&&input.dataset.type;vkType = vkType?vkType:"text"
			const vkContainer = this.getVkContainer()
			vkType = vkContainer&&vkContainer.static?vkContainer.static:vkType
			let res
			const alt = this.state.alt
			switch(vkType){
				case "text": res = {layout:layoutAlpha,ver:"simple"};break;
				case "extText": res = {layout:alt?layoutNumeric:layoutAlpha,ver:"extended"};break;
				case "extFuncText": res = {layout:alt?layoutNumeric:layoutAlpha,ver:"extendedFunc"};break;
				case "num": res = {layout:layoutNumeric,ver:"simple"};break;
				case "extNum": res = {layout:alt?layoutAlpha:layoutNumeric,ver:"extended"};break;
				case "extFuncNum": res = {layout:alt?layoutAlpha:layoutNumeric,ver:"extendedFunc"};break;
				case "none": res = {layout:"none",ver:"simple"};break;
				default:res = {layout:layoutAlpha, ver:"simple"};break;
			}
			//this.vkType = res
			return res
		}		
		getInitialState(){return {left:0,top:0, alt:false,fontSize:1, show:false}}		
		switchMode(e){
			this.setState({alt:!this.state.alt})			
		}	
		getVkContainer(){
			if(!this.root) return null
			const vkContainer = this.root.querySelector(".vk-container")
			if(!vkContainer) return null
			return {rect:vkContainer.getBoundingClientRect(),position:vkContainer.dataset.position,o:vkContainer,static:vkContainer.dataset.static}			
		}
		getPopupPos(thisEl,parentEl){
			if(!thisEl||!parentEl) return null;			
			const pRect = parentEl.parentNode.getBoundingClientRect()					
			const popRect = thisEl.getBoundingClientRect()

			this.prevVkContainer = null
			const windowRect = getWindowRect()								
			const rightEdge = pRect.right + popRect.width
			const leftEdge = pRect.left - popRect.width
			const bottomEdge = pRect.bottom + popRect.height
			const topEdge = pRect.top - popRect.height;
			let top = 0
			let left = 0			
			top += getPageYOffset()
			return {top,left}	
		}		
		getInput(){
			if(!this.root) return null
			const cNode = focusModule.getFocusNode()
			if(!cNode || !this.root.contains(cNode)) return null
			const input = cNode.querySelector("input:not([readonly])")
			return input
		}	
		showVk(){
			const input = this.getInput()	
			if(input) return true
			return false						
		}
		componentDidMount(){
			this.root = getReactRoot(this.el)			
			checkActivateCalls.add(this.fitIn)			
		}
		componentWillUnmount(){			
			checkActivateCalls.remove(this.fitIn)		
		}
		emRatio(){
			if(!this.remRef) return null
			return this.remRef.getBoundingClientRect().height
		}
		moveToAnchor(vkContainer,vkLayout){
			const bm = vkContainer.position == "bm"
			const tl = vkContainer.position == "tl"
			const ml = vkContainer.position == "ml"
			const tm = vkContainer.position == "tm"
			const tr = vkContainer.position == "tr"
			const br = vkContainer.position == "br"
			
			let top = vkContainer.rect.top 			
			let left = vkContainer.rect.left
			
			const pHeight = vkLayout.pHeight*vkLayout.fK
			const pWidth = vkLayout.pWidth*vkLayout.fK
			if(tm){				
				top = vkContainer.rect.height == 0?top - pHeight:vkContainer.rect.top				
				left = vkContainer.rect.left + vkContainer.rect.width/2 - pWidth/2
			}
			if(bm){
				top = vkContainer.rect.bottom - pHeight
				left = vkContainer.rect.left + vkContainer.rect.width/2 - pWidth/2
			}
			if(tl){
				top = vkContainer.rect.top 
				left = vkContainer.rect.left	
			}
			if(ml){
				top = vkContainer.rect.top + vkContainer.rect.height/2 - pHeight/2	
				left = vkContainer.rect.left					
			}			
			if(tr){
				top = vkContainer.rect.top 
				left = vkContainer.rect.right - pWidth
			}
			if(br){
				top = vkContainer.rect.bottom - pHeight
				left = vkContainer.rect.right - pWidth
			}
			if(this.root && this.root.style.position == "absolute"){
				const w = this.root.getBoundingClientRect()
				top-= w.top
				left -= w.left				
			}
			top+=getPageYOffset()					
			return {top,left}
		}
		updateState(inI,show){					    					
			if(show) {
				this.setState(inI)
				setTimeout(()=>{this.setState({show})},300)
			}
			else 
				this.setState({...inI,show})			
		}
		same(aRect,bRect){
			if(!aRect||!bRect) return false
			return aRect.top==bRect.top && aRect.left==bRect.left && aRect.height == bRect.height && aRect.width == bRect.width
		}
		fitIn(){			
			const vkLayout = this.getCurrentLayout()			
			if(!vkLayout && this.vkLayout == vkLayout) return
			const emK = this.emRatio()
			if(!emK) return
			const vkContainer = this.getVkContainer()			
			if(!vkContainer||!vkLayout) return this.state.show?this.updateState({},false):null	
			const show = vkContainer.static||this.showVk()	
			const wRect = getWindowRect()				
			if( this.state.show==show && this.same(this.wRect,wRect) && vkLayout == this.vkLayout && vkContainer.o == this.vkContainerO) return				
			
			let pWidth = Math.ceil(vkLayout.width * emK); pWidth == 0?1:pWidth
			const pHeight = Math.ceil(vkLayout.height * emK)
			const cHeight  = vkModule.getMaxHeight(this.root)
			let hK = (vkContainer.rect.height||cHeight)/pHeight; if(hK == 0) hK = 1
			let wK = vkContainer.rect.width/pWidth; if(wK == 0) wK = 1
			let fK = Math.min(hK,wK)*0.9;fK=fK>1?1:fK			 			
			
			this.wRect = wRect
			this.vkLayout = vkLayout
			this.vkContainerO = vkContainer.o
			const fontSize = fK
			const {top,left} = this.moveToAnchor(vkContainer,{pWidth,pHeight,fK})			
			if(this.state.fontSize!=fontSize || this.state.top!=top || this.state.left!=left || this.state.show!=show){		
				if(vkContainer.o.parentElement.classList.contains("vkView")) vkModule.onVk(show,cHeight)					
				this.updateState({fontSize,top,left},show)
			}							
		}
		getCurrentLayout(){			
			const vkType = this.getInputVKType()			
			const vkLayout = this.props[vkType.layout]
			if(!vkLayout) return null	
			return vkLayout[vkType.ver]			
		}
		getDefaultFontSize(){
			return this.props.style.fontSize?parseFloat(this.props.style.fontSize):1
		}		
		render(){			
			const genKey = (char,i) => `${char}_${i}`			
			const vkLayout = this.getCurrentLayout()		
			const visible = "visible"
			const borderSpacing = '0.1em'
			const positionStyle = {
				backgroundColor:"white",				
				position:this.props.isStatic?"":"absolute",												
				zIndex:this.state.show||this.props.isStatic?"1000":"-1",
				left:this.state.left+"px",
				top:this.state.top+"px",								
				display:this.state.show?"":"none",
				fontSize:this.state.fontSize +'em'
				//width:vkLayout?vkLayout.width+"em":"",
				//height:vkLayout?vkLayout.height+"em":""				
			}
			const wrapperStyle = {									
				height:"100%",
				width:"100%"
			}
			const btnStyle = {
				position:"absolute"
			}		
			const mainStyle = {				
			}
			const mutate = img => {
				const cp = {...img}				
				cp.src = svgSrc(cp.src)
				return cp
			}
			
			const buttons = vkLayout?vkLayout.buttons:[]
			const className = "vkKeyboard"	
			return $("div",{},[
				$("div",{key:"vk",ref:ref=>this.el=ref,style:positionStyle,className},
					vkLayout?
					$("div",{style:wrapperStyle},[				
						buttons.map((btn,j)=>$(VKButton,{style:{...btn.style,...btnStyle}, key:genKey(btn.char,j), fkey:btn.char, onClick:btn.switcher?this.switchMode:null}, (btn.image)?$("img", mutate(btn.image), null):btn.value?btn.value:btn.char))
					]):
					null
				),
				$("div",{key:"emRef",className:"vkRemRef",style:{position:"absolute",zIndex:"-1",height:"1em"}, ref:ref=>this.remRef=ref},null)
			])	
			
		}
	}	
	
	const vkModule = (() => {
		const views = []
		const vks = []
		const roots = []
		let updates = 0		
		const periodicUpdate = () => {
			if(views.length == 0 && vks.length == 0) {
				checkActivateCalls.remove(periodicUpdate)
				return
			}
			if(!updates && views.length >0 && vks.length > 0 )	{
				checkActivateCalls.add(periodicUpdate)
				updates +=1
				return
			}
			roots.forEach(r=>{
				if(r.root && !r.root.parentElement) return
				const height = Math.floor(r.root.parentElement.getBoundingClientRect().height)
				if(r.height!=height) {
					r.height = height					
					views.some(v=>(v.root == r.root)?(v.obj.f(null,null)||true):false)
				}
			})
		}
		const reg = (obj,el,ar) => {			
			const root = getReactRoot(el)
			const sv = {root,obj}
			const rv = {root,height:null}
			ar.push(sv)
			roots.push(rv)
			const unreg = () => {
				const index = ar.indexOf(sv)				
				ar.splice(index, 1)
				const rIndex = roots.indexOf(rv)
				if(rIndex>0) roots.splice(rIndex,1)
			}
			periodicUpdate()
			return {unreg}
		}
		const getRootHeight = (rootSpan) => {
			/*const root = rootSpan.parentElement
			if(!root) return null
			const fR = roots.find(r=>r.root == rootSpan)
			if(!fR) return null
			return fR.height*/
			return windowManager.getWindowRect().height
		}
		const getMaxHeight = (rootSpan) => {
			const root = rootSpan.parentElement
			if(!root) return 0
			const cR = vks.find(r=>r.root == rootSpan)
			if(!cR) return 0
			return getRootHeight(rootSpan)*cR.obj.maxHeight
		}
		const regView = (obj,el) => reg(obj,el,views)
		const regVk = (obj,el) => reg(obj,el,vks)		
		const onVk = (showVk,val) =>{			
			views.forEach(v=>v.obj.f(showVk,val))
			vks.forEach(v=>v.obj.f(showVk,val))
		}
		return {regView,regVk, onVk,getRootHeight,getMaxHeight}
	})()
	class VKMainViewElement extends StatefulComponent{		
		getInitialState(){return {height:null, vkView:false}}		
		updateVkView(vkView, height){
			this.rootHeight = vkModule.getRootHeight(this.root)			
			if(vkView === null && height === null && this.state.vkView){
				return this.setState({})
			}
			if(vkView!=this.state.vkView || height!=this.state.height) 
				this.setState({vkView,height:vkView?height:null})
		}		
		componentDidMount(){			
			this.root = getReactRoot(this.el)
			if(this.root) {
				this.prevRootHeight = this.root.parentElement.style.maxHeight
				this.root.parentElement.style.maxHeight="100%"
			}
			this.mObj = {f:this.updateVkView, maxHeight:parseInt(this.props.maxHeight)/100}
			this.vkReg = vkModule.regView(this.mObj,this.el)				
		}
		componentWillUnmount(){			
			if(this.root) this.root.parentElement.style.maxHeight=this.prevRootHeight
			this.vkReg.unreg()			
		}
		render(){								
			const height = this.state.vkView&&this.state.height&&this.rootHeight? (Math.floor(this.rootHeight - this.state.height))+"px": "100%"			
			const style = {				
				overflowY:height=="100%"?"":"auto",				
				height:height
			}			
			return $("div",{style, ref:ref=>this.el=ref},this.props.children)
		}
	}
	class VkViewElement extends StatefulComponent{		
		getInitialState(){return {vkView:false, height:null}}				
		updateVkView(vkView,height){
			//if(vkView === null && this.state.vkView) return this.setState({height})
			if(vkView!=this.state.vkView || height!=this.state.height) 
				this.setState({vkView,height:vkView?height:null})
		}		
		componentDidMount(){
			this.mObj = {f:this.updateVkView, maxHeight:parseInt(this.props.maxHeight)/100}
			this.vkReg = vkModule.regVk(this.mObj,this.el)			
		}
		componentWillUnmount(){			
			this.vkReg.unreg(this.mObj)			
		}
		render(){			
			const height = this.state.vkView&&this.state.height?Math.floor(this.state.height)+"px":0+"px"			
			const style = {				
				position:"absolute",
				width:"100%",
				height:height
			}
			const className = "vkView"
			return $("div",{style,ref:ref=>this.el=ref,className},this.props.children)
		}
	}
	
	const transforms= {
		tp:{
            VirtualKeyboard,VKMainViewElement,VkViewElement           
		}		
	}
	const checkActivate = checkActivateCalls.check	
	return ({transforms,checkActivate});
}