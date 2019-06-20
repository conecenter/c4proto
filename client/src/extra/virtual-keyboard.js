"use strict";
import React from 'react'
import {eventManager,checkActivateCalls} from './event-manager.js'

export default function VirtualKeyboard({log,btoa,windowManager,StatefulComponent,reactPathConsumer}){
	const svgSrc = svg => "data:image/svg+xml;base64,"+btoa(svg)
	const $ = React.createElement	
	
	const getReactRoot = (el) => el.ownerDocument.body
	const isNodePosition = (el,v) => el&&el.ownerDocument&&el.ownerDocument.defaultView&&el.ownerDocument.defaultView.getComputedStyle(el).position == v	
	const {setTimeout,getWindowRect,setInterval,clearInterval} = windowManager
	const getPageYOffset = (el) => el&&el.ownerDocument&&el.ownerDocument.defaultView?el.ownerDocument.defaultView.pageYOffset:0
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
			this.props.onPress && this.props.onPress()
			if(this.props.fkey) eventManager.sendToWindow(eventManager.create(ev.target)("keydown",{key:this.props.fkey,bubbles:true,code:"vk"}))
			if(this.props.onClick) this.props.onClick(ev)			
		}
		onTouchStart(e){
			//this.onMouseDown(e)
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
			const className = "vkElement " + (this.props.className?this.props.className:"")
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
			const vkContainer = Array.from(this.root.querySelectorAll(".vk-container")).find(_=>_.offsetParent!==null||isNodePosition(_,"fixed"))			
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
			top += getPageYOffset(thisEl)
			return {top,left}	
		}				
		getParentWrapper(el){
			let e = el
			while(e){
				if(e.classList.contains("focusWrapper")) return e
				e = e.parentElement
			}
			return null
		}
		isOfParentNode(el,parentNode){
			let e = el
			while(e){
				if(e == parentNode) return true
				e = e.parenElement
			}
			return false
		}
		getFocusedElement(){
			const a = this.el.ownerDocument.querySelector(`*[data-path="${this.path}"]`)
			const b = this.el.ownerDocument.activeElement
			return a||b
		}
		getStickyElement(){
			return this.el.ownerDocument.querySelector(`*[data-sticky="sticky"]`)
		}
		getInput(){
			if(!this.root||!this.el) return null			
			const cNode = this.getParentWrapper(this.getStickyElement() || this.getFocusedElement())
			if(!cNode) return null
			const input = cNode.querySelector("input:not([readonly])")||cNode.querySelector('input[name="vk"]')
			return input
		}	
		showVk(){
			const input = this.getInput()				
			if(input) return true
			return false						
		}
		getRoot(){
			if(!this.root|| !this.root.parentElement) return getReactRoot(this.el)				
			return this.root
		}
		componentDidMount(){
			this.root = this.getRoot()
			if(!this.props.auto)
				checkActivateCalls.add(this.fitIn)			
			else 
				this.posInterval = setInterval(this.fitIn,100)
			
		}
		componentWillUnmount(){
			this.unmounted = true	
			clearInterval(this.posInterval)
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
			const mr = vkContainer.position == "mr"
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
			if(mr){
				top = vkContainer.rect.top + vkContainer.rect.height/2 - pHeight/2	
				left = vkContainer.rect.right - pWidth
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

			top+=this.root?(this.root.style.display=="block"?this.root.scrollTop:getPageYOffset(this.root)):0
			return {top,left, cpHeight:pHeight}
		}
		updateState(inI,show){					    					
			if(this.unmounted) return
			if(show) {
				this.setState(inI)
				setTimeout(()=>{if(!this.unmounted)this.setState({show})},300)
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
			this.root = this.getRoot()
			const emK = this.emRatio()
			if(!emK) return 
			const vkContainer = this.getVkContainer()			
			if(!vkContainer||!vkLayout) return this.state.show?this.updateState({},false):null	
			const show = vkContainer.static||this.showVk()
			const wRect = this.root&&this.root.getBoundingClientRect()				
			if( this.state.show==show && this.same(this.wRect,wRect) && vkLayout == this.vkLayout && vkContainer.o == this.vkContainerO) {
				if(!this.iter||this.iter<=0) return
				this.iter-=1
			}
			if(!this.iter||this.iter<0) this.iter=1
			let pWidth = Math.ceil(vkLayout.width * emK); pWidth == 0?1:pWidth
			const pHeight = Math.ceil(vkLayout.height * emK)
			const cHeight  = vkModule.getMaxHeight(this.root)
			let hK = (cHeight||vkContainer.rect.height)/pHeight; if(hK == 0) hK = 1
			let wK = vkContainer.rect.width/pWidth; if(wK == 0) wK = 1
			let fK = Math.min(hK,wK)*0.9;fK=fK>1?1:fK			 			
			
			this.wRect = wRect
			this.vkLayout = vkLayout
			this.vkContainerO = vkContainer.o
			const fontSize = fK
			const {top,left,cpHeight} = this.moveToAnchor(vkContainer,{pWidth,pHeight,fK})			
			if(this.state.fontSize!=fontSize || this.state.top!=top || this.state.left!=left || this.state.show!=show){		
				if(vkContainer.o.parentElement.classList.contains("vkView")) vkModule.onVk(show,cpHeight)					
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
		onRef(path){			
			return (ref)=>{
				this.path = path
				log(`path`,this.path)
				this.el = ref
			}
		}
		onPress(){
			//if(this.getFocusedElement().tagName == "BODY") 
			{
				const sticky = this.getStickyElement()
				if(sticky && !this.isOfParentNode(this.getFocusedElement(),sticky)) sticky.focus()
			}
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
			return $(reactPathConsumer,{},path=>([
				$("div",{key:"vk",ref:this.onRef(path),style:positionStyle,className},
					vkLayout?
					$("div",{style:wrapperStyle},[				
						buttons.map((btn,j)=>$(VKButton,{style:{...btn.style,...btnStyle},className:btn.className, key:genKey(btn.char,j),onPress:this.onPress, fkey:btn.char, onClick:btn.switcher?this.switchMode:null}, (btn.image)?$("img", mutate(btn.image), null):btn.value?btn.value:btn.char))
					]):
					null
				),
				$("div",{key:"emRef",className:"vkRemRef",style:{position:"absolute",zIndex:"-1",height:"1em"}, ref:ref=>this.remRef=ref},null)
			])	
			)
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
			if(!rootSpan||!rootSpan.ownerDocument||!rootSpan.ownerDocument.defaultView) return null
			const v = rootSpan.ownerDocument.defaultView.innerHeight			
			return v
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
			if(this.unmounted) return
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
				const vkMain = this.root.querySelector(".vkMain")
				this.prevRootHeight = vkMain.style.maxHeight
				vkMain.style.maxHeight="100%"
			}
			this.mObj = {f:this.updateVkView, maxHeight:parseInt(this.props.maxHeight)/100}
			this.vkReg = vkModule.regView(this.mObj,this.el)				
		}
		componentWillUnmount(){	
			this.unmounted = true
			if(this.root && this.root.parentElement) this.root.parentElement.style.maxHeight=this.prevRootHeight
			this.vkReg.unreg()			
		}
		render(){								
			const height = this.state.vkView&&this.state.height&&this.rootHeight? (Math.floor(this.rootHeight - this.state.height))+"px": "100%"						
			const style = {				
				overflowY:height=="100%"?"":"auto",				
				height:height
			}			
			const className = "vkMain"
			return $("div",{style, ref:ref=>this.el=ref,className},this.props.children)
		}
	}
	class VkViewElement extends StatefulComponent{		
		getInitialState(){return {vkView:false, height:null}}				
		updateVkView(vkView,height){
			if(this.unmounted) return
			//if(vkView === null && this.state.vkView) return this.setState({height})
			if(vkView!=this.state.vkView || height!=this.state.height) 
				this.setState({vkView,height:vkView?height:null})
		}		
		componentDidMount(){
			this.mObj = {f:this.updateVkView, maxHeight:parseInt(this.props.maxHeight)/100}
			this.vkReg = vkModule.regVk(this.mObj,this.el)			
		}
		componentWillUnmount(){			
			this.unmounted = true
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