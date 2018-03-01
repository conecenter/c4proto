"use strict";
import React from 'react'

export default function VirtualKeyboard({log,svgSrc,focusModule,eventManager,windowManager,miscReact}){
	const $ = React.createElement
	const $C = React.createClass
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
	const VKButton = React.createClass({
		getInitialState:function(){
			return {touch:false,mouseDown:false};
		},
		onClick:function(ev){
			if(this.props.fkey) eventManager.sendToWindow(eventManager.create("keydown",{key:this.props.fkey,bubbles:true,code:"vk"}))
			if(this.props.onClick){
				this.props.onClick(ev);				
				return;
			}			
		},
		onTouchStart:function(e){
			this.setState({touch:true});
		},
		onTouchEnd:function(e){
			this.setState({touch:false});
		},
		onMouseDown:function(){this.setState({mouseDown:true})},
		onMouseUp:function(){this.setState({mouseDown:false})},
		render:function(){					
			const bStyle={
				height:'2.2em',
				width:'2em',
				fontSize:"1em",
				border:`${GlobalStyles.borderWidth} ${GlobalStyles.borderStyle}`,
				fontStyle:'inherit',				
				backgroundColor:'#eeeeee',
				verticalAlign:'middle',
				overflow:"hidden",
				outline:(this.state.touch||this.state.mouseDown)?`${GlobalStyles.outlineWidth} ${GlobalStyles.outlineStyle} ${GlobalStyles.outlineColor}`:'none',				
				color:'inherit',
				padding:"0px",
				textAlign:'center',
				...this.props.style,				
				...((this.state.touch||this.state.mouseDown)?{backgroundColor:"rgb(25, 118, 210)"}:{})				
			};
			const className = "vkElement"
			return $("button",{style:bStyle,className,onTouchStart:this.onTouchStart,onTouchEnd:this.onTouchEnd,onMouseDown:this.onMouseDown,onMouseUp:this.onMouseUp,onClick:this.onClick},this.props.children);
		}
	});	
	const VirtualKeyboard = React.createClass({
		getInputVKType:function(){						
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
				case "num": res = {layout:layoutNumeric,ver:"simple"};break;
				case "extNum": res = {layout:alt?layoutAlpha:layoutNumeric,ver:"extended"};break;
				case "extFuncNum": res = {layout:alt?layoutAlpha:layoutNumeric,ver:alt?"extended":"extendedFunc"};break;
				default:res = {layout:layoutAlpha, ver:"simple"};break;
			}
			this.vkType = res
			return res
		},
		getInitialState:function(){
			return {left:0,top:0, alt:false,fontSize:1, show:false}
		},
		switchMode:function(e){
			this.setState({alt:!this.state.alt})			
		},		
		getVkContainer:function(){
			if(!this.root) return null
			const vkContainer = this.root.querySelector(".vk-container")
			if(!vkContainer) return null
			return {rect:vkContainer.getBoundingClientRect(),position:vkContainer.dataset.position,o:vkContainer,static:vkContainer.dataset.static}			
		},
		getPopupPos:function(thisEl,parentEl){
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
			
			/*if(bottomEdge<=windowRect.bottom){					//bottom
				left = pRect.left//rect.left - popRect.left
				top = pRect.bottom	
				if(left + popRect.width>windowRect.right)
					left = windowRect.right - popRect.width;
			}
			else if(topEdge>windowRect.top){	//top
				top = pRect.top - popRect.height
				left = pRect.left
				if(left + popRect.width>windowRect.right)
					left = windowRect.right - popRect.width;
			}
			else if(leftEdge>windowRect.left){	//left
				left = pRect.left - popRect.width;
				top = pRect.bottom - popRect.height/2;
				if(top<windowRect.top)
					top = windowRect.top
			}
			else if(rightEdge>windowRect.right){
				left = windowRect.right - popRect.width;
				top = pRect.bottom;
				if(top<windowRect.top)
					top = windowRect.top
			}
			else if(rightEdge<=windowRect.right){
				left = pRect.right;
				top = pRect.bottom - popRect.height/2;
				if(top<windowRect.top)
					top = windowRect.top
			}*/
			top += getPageYOffset()
			return {top,left}	
		},		
		getInput:function(){
			const cNode = focusModule.getFocusNode()
			if(!cNode) return null
			const input = cNode.querySelector("input:not([readonly])")
			return input
		},	
		showVk:function(){
			const input = this.getInput()	
			if(input) return true
			return false						
		},
		componentDidMount:function(){
			this.root = getReactRoot(this.el)
			if(this.props.isStatic) return
			checkActivateCalls.add(this.fitIn)			
		},
		componentWillUnmount:function(){
			if(this.props.isStatic) return
			checkActivateCalls.remove(this.fitIn)			
		},
		emRatio:function(){
			if(!this.remRef) return null
			return this.remRef.getBoundingClientRect().height
		},
		moveToAnchor:function(vkContainer,vkLayout){
			const bm = vkContainer.position == "bm"
			const tl = vkContainer.position == "tl"
			const ml = vkContainer.position == "ml"
			const tm = vkContainer.position == "tm"
			const tr = vkContainer.position == "tr"
			
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
			top+=getPageYOffset()					
			return {top,left}
		},
		updateState:function(inI,show){					    					
			if(show) {
				this.setState(inI)
				setTimeout(()=>{this.setState({show})},300)
			}
			else 
				this.setState({...inI,show})			
		},
		same:function(aRect,bRect){
			if(!aRect||!bRect) return false
			return aRect.top==bRect.top && aRect.left==bRect.left && aRect.height == bRect.height && aRect.width == bRect.width
		},
		fitIn:function(){			
			const vkLayout = this.getCurrentLayout()
			if(!vkLayout) return
			const emK = this.emRatio()
			if(!emK) return
			const vkContainer = this.getVkContainer()
			if(!vkContainer) return this.state.show?this.updateState({},false):null	
			const show = vkContainer.static||this.showVk()	
			const wRect = getWindowRect()				
			if( this.state.show==show && this.same(this.wRect,wRect) ) return				
			
			let pWidth = Math.ceil(vkLayout.width * emK); pWidth == 0?1:pWidth
			const pHeight = Math.ceil(vkLayout.height * emK)
			const cHeight  = vkModule.getMaxHeight(this.root)
			let hK = (vkContainer.rect.height||cHeight)/pHeight; if(hK == 0) hK = 1
			let wK = vkContainer.rect.width/pWidth; if(wK == 0) wK = 1
			let fK = Math.min(hK,wK)*0.9;fK=fK>1?1:fK			 			
			
			this.wRect = wRect
			const fontSize = fK
			const {top,left} = this.moveToAnchor(vkContainer,{pWidth,pHeight,fK})			
			if(this.state.fontSize!=fontSize || this.state.top!=top || this.state.left!=left || this.state.show!=show){		
				if(vkContainer.o.parentElement.classList.contains("vkView")) vkModule.onVk(show,cHeight)					
				this.updateState({fontSize,top,left},show)
			}							
		},
		getCurrentLayout:function(){			
			const vkType = this.getInputVKType()			
			const vkLayout = this.props[vkType.layout]
			if(!vkLayout) return null	
			return vkLayout[vkType.ver]			
		},
		getDefaultFontSize:function(){
			return this.props.style.fontSize?parseFloat(this.props.style.fontSize):1
		},
		render:function(){			
			const genKey = (char,i) => `${char}_${i}`			
			const vkLayout = this.getCurrentLayout()
			if(!vkLayout) return null
			const visible = "visible"
			const borderSpacing = '0.1em'
			const positionStyle = {
				backgroundColor:"white",				
				position:this.props.isStatic?"":"absolute",												
				zIndex:this.state.show||this.props.isStatic?"1000":"-1",
				left:this.state.left+"px",
				top:this.state.top+"px",								
				display:this.state.show?"":"none",
				width:vkLayout.width+"em",
				height:vkLayout.height+"em",
				fontSize:this.state.fontSize +'em'
			}
			const wrapperStyle = {									
				height:"100%",
				width:"100%"
			}
			const btnStyle = {
				position:"absolute"
			}		
			
			const mutate = img => {
				const cp = {...img}				
				cp.src = svgSrc(cp.src)
				return cp
			}
			
			const buttons = vkLayout.buttons			
			const className = "vkKeyboard"	
			return $("div",{},[
				$("div",{key:"vk",ref:ref=>this.el=ref,style:positionStyle,className},
					$("div",{style:wrapperStyle},[				
						buttons.map((btn,j)=>$(VKButton,{style:{...btn.style,...btnStyle}, key:genKey(btn.char,j), fkey:btn.char, onClick:btn.switcher?this.switchMode:null}, (btn.image)?$("img", mutate(btn.image), null):btn.value?btn.value:btn.char))
					])
				),
				$("div",{key:"remRef",className:"vkRemRef",style:{position:"absolute",zIndex:"-1",height:"1em"}, ref:ref=>this.remRef=ref},null)
			])	
			
		},
	});	
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
			const root = rootSpan.parentElement
			if(!root) return null
			const fR = roots.find(r=>r.root == rootSpan)
			if(!fR) return null
			return fR.height
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
	const VKMainViewElement = $C({
		getInitialState:function(){
			return {height:null, vkView:false}
		},		
		updateVkView:function(vkView, height){
			this.rootHeight = vkModule.getRootHeight(this.root)			
			if(vkView === null && height === null && this.state.vkView){
				return this.setState({})
			}
			if(vkView!=this.state.vkView || height!=this.state.height) 
				this.setState({vkView,height:vkView?height:null})
		},		
		componentDidMount:function(){			
			this.root = getReactRoot(this.el)
			if(this.root) {
				this.prevRootHeight = this.root.parentElement.style.maxHeight
				this.root.parentElement.style.maxHeight="100%"
			}
			this.mObj = {f:this.updateVkView, maxHeight:parseInt(this.props.maxHeight)/100}
			this.vkReg = vkModule.regView(this.mObj,this.el)				
		},
		componentWillUnmount:function(){			
			if(this.root) this.root.parentElement.style.maxHeight=this.prevRootHeight
			this.vkReg.unreg()			
		},
		render:function(){								
			const height = this.state.vkView&&this.state.height&&this.rootHeight? (Math.floor(this.rootHeight - this.state.height))+"px": "100%"			
			const style = {				
				overflowY:height=="100%"?"":"auto",				
				height:height
			}			
			return $("div",{style, ref:ref=>this.el=ref},this.props.children)
		}
	})
	const VkViewElement = $C({
		getInitialState:function(){
			return {vkView:false, height:null}
		},
		updateVkView:function(vkView,height){
			//if(vkView === null && this.state.vkView) return this.setState({height})
			if(vkView!=this.state.vkView || height!=this.state.height) 
				this.setState({vkView,height:vkView?height:null})
		},
		
		componentDidMount:function(){
			this.mObj = {f:this.updateVkView, maxHeight:parseInt(this.props.maxHeight)/100}
			this.vkReg = vkModule.regVk(this.mObj,this.el)			
		},
		componentWillUnmount:function(){			
			this.vkReg.unreg(this.mObj)			
		},
		render:function(){			
			const height = this.state.vkView&&this.state.height?Math.floor(this.state.height)+"px":0+"px"			
			const style = {				
				position:"absolute",
				width:"100%",
				height:height
			}
			const className = "vkView"
			return $("div",{style,ref:ref=>this.el=ref,className},this.props.children)
		}
	})
	
	
	
	const transforms= {
		tp:{
            VirtualKeyboard,VKMainViewElement,VkViewElement           
		}		
	};
	const checkActivate = checkActivateCalls.check	
	return ({transforms,checkActivate});
}