"use strict";
import React from 'react'
import {pairOfInputAttributes}  from "../main/vdom-util"
import Errors from "../extra/errors"
import {ctxToPath,rootCtx} from "../main/vdom-util"

/*
todo:
extract mouse/touch to components https://facebook.github.io/react/docs/jsx-in-depth.html 'Functions as Children'
jsx?
*/


export default function MetroUi({log,requestState,svgSrc,documentManager,focusModule,eventManager,overlayManager,dragDropModule,windowManager,miscReact,Image,miscUtil,StatefulComponent}){
	const $ = React.createElement	
	const Branches = (()=>{
		let main =""
		const store = (o)=>{
			if(!o.length) return
			main = o.split(/[,;]/)[0]
		}
		const get = () => main
		const isSibling = (o) => {
			if(!o) return false
			if(main.length==0) return false
			return main != o.branchKey
		}
		return {store, get, isSibling}
	})()
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
	const checkActivateCalls=(()=>{
		const callbacks=[]
		const add = (c) => callbacks.push(c)
		const remove = (c) => {
			const index = callbacks.indexOf(c)
			if(index>=0) callbacks.splice(index,1)
		}
		const check = () => callbacks.forEach(c=>c())
		return {add,remove,check}
	})();
	const {isReactRoot,getReactRoot} = miscReact
	const {setTimeout,clearTimeout,setInterval,clearInterval,getPageYOffset,addEventListener,removeEventListener,getWindowRect,getComputedStyle,urlPrefix} = windowManager
	const {Provider, Consumer} = React.createContext("");
	const ImageElement = ({src,style,forceSrcWithoutPrefix}) => {
		const srcM = !forceSrcWithoutPrefix?(urlPrefix||"")+src: src;
		return $("img",{src:srcM,style})
	}

	const resizeListener = (() =>{
		const delay = 500
		const callbacks = []
		let wait
		const reg = (o)=>{
			callbacks.push(o)
			const unreg=()=>{
				const index = callbacks.indexOf(o)
				if(index>=0) callbacks.splice(index,1)
				return null	
			}
			return {unreg}
		}
		const onResize = () =>{
			if(wait) wait = clearTimeout(wait)
			wait = setTimeout(()=>{
				callbacks.forEach(c=>c())
				wait = null
			},delay)	
		}
		addEventListener("resize",onResize)
		return {reg}
	})()

	const FlexContainer = ({flexWrap,children,style}) => $("div",{style:{
		display:'flex',
		flexWrap:flexWrap?flexWrap:'nowrap',
		...style
		}},children);
	const FlexElement = ({expand,minWidth,maxWidth,style,children})=>$("div",{style:{
		flexGrow:expand?'1':'0',
		flexShrink:'1',
		minWidth:'0px',
		flexBasis:minWidth?minWidth:'auto',
		maxWidth:maxWidth?maxWidth:'auto',
		...style
	}},children);	
	
	class ButtonElement extends StatefulComponent{		
		getInitialState(){return {mouseOver:false,touch:false, ripple:false}}
		mouseOver(){
			this.setState({mouseOver:true});
			if(this.props.onMouseOver)
				this.props.onMouseOver();
		}
		mouseOut(){
			this.setState({mouseOver:false});
			if(this.props.onMouseOut)
				this.props.onMouseOut();
		}
		onTouchStart(e){
			this.setState({touch:true});			
		}
		onTouchEnd(e){		
			this.setState({touch:false,mouseOver:false});		
		}
		onClick(e){			
			if(this.props.onClick){
				setTimeout(function(){this.props.onClick(e)}.bind(this),(this.props.delay?parseInt(this.props.delay):0));
			}					
		}	
		onMouseDown(e){
			this.onClick(e)
			e.stopPropagation()
		}
		onEnter(event){
			//log(`Enter ;`)
			event.stopPropagation()
			if(!this.el) return
			this.el.click()
			const cEvent = eventManager.create("cTab",{bubbles:true})
			this.el.dispatchEvent(cEvent)							
		}	
		componentDidMount(){
			this.updatePeriod = 50
			this.updateAt = 0
			if(!this.el) return
			this.el.addEventListener("enter",this.onEnter)
			this.el.addEventListener("click",this.onClick)
			if(this.props.ripple)
				checkActivateCalls.add(this.rippleAnim)
		}
		componentDidUpdate(prevProps,prevState){
			if(this.props.ripple && !prevProps.ripple)
				checkActivateCalls.add(this.rippleAnim)
			if(!this.props.ripple && prevProps.ripple)
				checkActivateCalls.remove(this.rippleAnim)
		}
		componentWillUnmount(){
			this.el.removeEventListener("enter",this.onEnter)
			this.el.removeEventListener("click",this.onClick)
			checkActivateCalls.remove(this.rippleAnim)
		}
		componentWillReceiveProps(nextProps){
			this.setState({mouseOver:false,touch:false});
		}
		rippleAnim(){
			if(this.updateAt<=0) {
				const width = this.el.getBoundingClientRect().width
				const height = this.el.getBoundingClientRect().height
				const rBox = Math.max(width,height)
				const top = width>height?-(rBox-height)/2:0
				const left = width<height?-(rBox-width)/2:0								
				this.setState({ripple:!this.state.ripple, rBox,top,left})
				this.updateAt = this.updatePeriod				
			}
			this.updateAt-=1
		}
		render(){		
			const defbg = "#eeeeee"
			const bg = this.props.style&&this.props.style.backgroundColor?this.props.style.backgroundColor:defbg			
			const oStyle = this.props.ripple?{margin:"0px"}:{}
			const style={
				border:'none',
				cursor:'pointer',
				paddingInlineStart:'0.4em',
				paddingInlineEnd:'0.4em',
				padding:'0 1em',
				minHeight:'1em',
				minWidth:'1em',
				fontSize:'1em',
				alignSelf:'center',
				fontFamily:'inherit',				
				outline:this.state.touch?`${GlobalStyles.outlineWidth} ${GlobalStyles.outlineStyle} ${GlobalStyles.outlineColor}`:'none',
				outlineOffset:GlobalStyles.outlineOffset,
				...this.props.style,
				...oStyle,				
				...(this.state.mouseOver && Object.keys(this.props.overStyle||{}).length==0?{opacity:"0.8"}:null),
				...(this.state.mouseOver?this.props.overStyle:null),				
			}
			const className = this.props.className
			
			const wrap = (el) =>{
				if(this.props.ripple && this.state.top!==undefined && this.state.left!==undefined && this.state.rBox){					
					const rEl = $("div",{key:"rp",style:{
						width:this.state.rBox+"px",
						height:this.state.rBox+"px",
						position:"absolute",
						top:this.state.top+"px",
						left:this.state.left+"px",
						backgroundColor:"transparent",
						transition:this.state.ripple?"transform 2.1s":"transform 0s",						
						borderRadius:"50%",						
						boxShadow: "inset 0px 0px 2.4em 0.5em rgba(255,255,255,0.9)",						
						transform:this.state.ripple?"scale(2,2)":"scale(0,0)",
						pointerEvents:"none"
					}})
					return $("div",{style:{position:"relative",overflow:"hidden",...this.props.style}},[el,rEl])
				}
				else 
					return el
			}
			const el = $("button",{title:this.props.hint,className,key:"btn",style,ref:ref=>this.el=ref,onMouseOver:this.mouseOver,onMouseOut:this.mouseOut,onTouchStart:this.onTouchStart,onTouchEnd:this.onTouchEnd},this.props.children)	
			return wrap(el)
		}
	}
	
	const uiElements = []
	const errors = Errors({log,uiElements,documentManager})
	
	
	class ErrorElement extends StatefulComponent{	
		getInitialState(){return {show:false,data:null}}
		callback(data){
			//log(`hehe ${data}`)
			this.setState({show:true,data})
		}
		componentDidMount(){
			this.binding = errors.reg(this.callback)
			//log(this.props.data)
		}
		onClick(e){
			//log(`click`)
			this.setState({show:false,data:null})
			if(this.props.onClick) this.props.onClick(e)
		}
		componentWillUnmount(){
			if(this.binding) this.binding.unreg()
		}
		render(){
			if(this.state.show||this.props.data!=undefined){
				const fillColor = "black"
				const closeSvg = `
				<svg version="1.1" xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" x="0px" y="0px" viewBox="0 0 348.333 348.334" style="enable-background:new 0 0 348.333 348.334;" xml:space="preserve" fill="${fillColor}">
					<path d="M336.559,68.611L231.016,174.165l105.543,105.549c15.699,15.705,15.699,41.145,0,56.85 c-7.844,7.844-18.128,11.769-28.407,11.769c-10.296,0-20.581-3.919-28.419-11.769L174.167,231.003L68.609,336.563 c-7.843,7.844-18.128,11.769-28.416,11.769c-10.285,0-20.563-3.919-28.413-11.769c-15.699-15.698-15.699-41.139,0-56.85   l105.54-105.549L11.774,68.611c-15.699-15.699-15.699-41.145,0-56.844c15.696-15.687,41.127-15.687,56.829,0l105.563,105.554   L279.721,11.767c15.705-15.687,41.139-15.687,56.832,0C352.258,27.466,352.258,52.912,336.559,68.611z"/>
				</svg>`;
				const noteSvg = `
				<svg fill="red" version="1.1" xmlns="http://www.w3.org/2000/svg" x="0" y="0" viewBox="0 0 490 490" style="enable-background:new 0 0 490 490;" xml:space="preserve">  
					<path d="M244.5,0C109.3,0,0,109.3,0,244.5S109.3,489,244.5,489S489,379.7,489,244.5S379.7,0,244.5,0z M244.5,448.4
							 c-112.4,0-203.9-91.5-203.9-203.9S132.1,40.6,244.5,40.6s203.9,91.5,203.9,203.9S356.9,448.4,244.5,448.4z" />
					<path d="M354.8,134.2c-8.3-8.3-20.8-8.3-29.1,0l-81.2,81.2l-81.1-81.1c-8.3-8.3-20.8-8.3-29.1,0s-8.3,20.8,0,29.1l81.1,81.1
							 l-81.1,81.1c-8.3,8.3-8.6,21.1,0,29.1c6.5,6,18.8,10.4,29.1,0l81.1-81.1l81.1,81.1c12.4,11.7,25,4.2,29.1,0
							 c8.3-8.3,8.3-20.8,0-29.1l-81.1-81.1l81.1-81.1C363.1,155,363.1,142.5,354.8,134.2z" />
				</svg>`;
				const closeSvgData = svgSrc(closeSvg)
				const noteSvgData = svgSrc(noteSvg)
				const closeImg = $("img",{src:closeSvgData,style:{width:"1.5em",display:"inherit",height:"0.7em"}})
				const noteImg = $("img",{src:noteSvgData,style:{width:"1.5em",display:"inherit"}})
				const data = this.props.data?this.props.data:this.state.data
				const buttonEls = this.props.onClick?[
					//$(ButtonElement,{key:"but1",onClick:this.onClick,style:{margin:"5mm",flex:"0 0 auto"}},"OK"),
						$(ButtonElement,{key:"but2",onClick:this.onClick,style:{/*margin:"5mm",*/margin:"0px",flex:"0 0 auto"}},closeImg)
					]:null
				const style = {
					backgroundColor:"white",
					padding:"0em 1.25em",
					borderTop:"0.1em solid #1976d2",
					borderBottom:"0.1em solid #1976d2",
					...this.props.style
				}	
				const errorEl = $("div",{style},
					$("div",{style:{display:"flex",/*height:"2em",*/height:"auto",margin:"0.2em"}},[
						$("div",{key:"msg",style:{display:"flex",flex:"1 1 auto",minWidth:"0"}},[
							$("div",{key:"icon",style:{alignSelf:"center"}},noteImg),
							$("div",{key:"msg",style:{alignSelf:"center",color:"red",flex:"0 1 auto",margin:"0em 0.5em",overflow:"hidden",textOverflow:"ellipsis"/*,whiteSpace:"nowrap"*/}},data)						
						]),
						buttonEls
					])
				)			
				return errorEl
			}	
			else 
				return null
		}
	}
	uiElements.push({ErrorElement})
	
	const MenuBurger = (props) => {	
	
		const c = {transition:"all 100ms",transformOrigin:"center"}
		const alt1 = props.isBurgerOpen?{transform: "rotate(-45deg)"}:{}
		const alt2 = props.isBurgerOpen?{opacity: "0"}:{}
		const alt3 = props.isBurgerOpen?{transform: "rotate(45deg)"}:{}	
		const color = props.style&&props.style.color?props.style.color:"white"
		const svg = $("svg",{xmlns:"http://www.w3.org/2000/svg","xmlnsXlink":"http://www.w3.org/1999/xlink",height:"1.5em",width:"1.5em", style:{"enableBackground":"new 0 0 32 32"}, version:"1.1", viewBox:"0 0 32 32","xmlSpace":"preserve"},[				
				$("line",{style:{...c,...alt1},key:1,"strokeLinecap":"round",x1:"2",y1:props.isBurgerOpen?"16":"9",x2:"30",y2:props.isBurgerOpen?"16":"9","strokeWidth":"4","stroke":color}),							
				$("line",{style:{...c,...alt2},key:2,"strokeLinecap":"round",x1:"2",y1:"17",x2:"30",y2:"17","strokeWidth":"4","stroke":color}),								
				$("line",{style:{...c,...alt3},key:3,"strokeLinecap":"round",x1:"2",y1:props.isBurgerOpen?"16":"25",x2:"30",y2:props.isBurgerOpen?"16":"25","strokeWidth":"4","stroke":color})				
		])
		const style = {
			backgroundColor:"inherit",
			cursor:"pointer",
			...props.style
		}
		return $("div",{style,onClick:props.onClick},svg)
	}
	
	class MenuBarElement extends StatefulComponent{		
		getInitialState(){return {fixedHeight:"",scrolled:false, isBurger:false}}	
		process(){
			if(!this.el) return;
			const height = this.el.getBoundingClientRect().height + "px";			
			if(height !== this.state.fixedHeight) this.setState({fixedHeight:height});			
		}
		onScroll(){
			const scrolled = getPageYOffset()>0;
			if(!this.state.scrolled&&scrolled) this.setState({scrolled}) 
			else if(this.state.scrolled&&!scrolled) this.setState({scrolled})
		}
		componentWillUnmount(){
			checkActivateCalls.remove(this.calc)
			//removeEventListener("scroll",this.onScroll);
		}		
		calc(){
			if(!this.leftEl) return						
			const tCLength = Math.round(Array.from(this.leftEl.children).reduce((a,e)=>a+e.getBoundingClientRect().width,0))
			const tLength = Math.round(this.leftEl.getBoundingClientRect().width)
			
			if(!this.bpLength && tCLength>0 && tCLength>=tLength && !this.state.isBurger) {
				this.bpLength = tCLength				
				this.setState({isBurger:true})
			}
			if(this.bpLength && this.bpLength<tLength && this.state.isBurger){					
				this.bpLength = null
				this.setState({isBurger:false})
			}
		}
		openBurger(e){
			if(this.props.onClick)
				this.props.onClick(e)			
		}
		componentDidMount(){
			checkActivateCalls.add(this.calc)
			//this.process();
			//addEventListener("scroll",this.onScroll);
		}
		parentEl(node){
			if(!this.leftEl||!node) return true
			let p = node
			while(p && p !=this.leftEl){
				p = p.parentElement
				if(p == this.leftEl) return true
			}
			return false
		}
		onBurgerBlur(e){
			if(this.props.isBurgerOpen && !this.parentEl(e.relatedTarget)) this.openBurger(e)
		}
		render(){
			const style = {
				//height:this.state.fixedHeight,				
			}
			const menuStyle = {
				position:"static",
				width:"100%",
				zIndex:"6662",
				top:"0rem",
				boxShadow:this.state.scrolled?GlobalStyles.boxShadow:"",
				...this.props.style				
			}
			const barStyle = {
				display:'flex',
				flexWrap:'nowrap',
				justifyContent:'flex-start',
				backgroundColor:'#2196f3',
				verticalAlign:'middle',				
				width:"100%",
				...this.props.style				
			}
			const burgerPopStyle = {
				position:"absolute",
				zIndex:"1000",
				backgroundColor:"inherit"
			}
			const left = this.props.children.filter(_=>!_.key.includes("right"))						
			//const svgData = svgSrc(svg)
			//const errors = this.props.children[1]
			const right = this.props.children.filter(_=>_.key.includes("right"))			
			const menuBurger = $("div",{onBlur:this.onBurgerBlur,tabIndex:"0", style:{backgroundColor:"inherit",outline:"none"}},[
				$(MenuBurger,{style:{marginLeft:"0.5em"},isBurgerOpen:this.props.isBurgerOpen,key:"burger",onClick:this.openBurger}),
				this.props.isBurgerOpen?$("div",{style:burgerPopStyle,key:"popup"},left):null
			])
			return $("div",{style:style},
				$("div",{style:barStyle,className:"menuBar",ref:ref=>this.el=ref,},[
					$("div",{key:"left", ref:ref=>this.leftEl=ref,style:{whiteSpace:"nowrap",backgroundColor:"inherit",flex:"1",alignSelf:"center",display:"flex"}},this.state.isBurger?menuBurger:left),
					$("div",{key:"right",style:{alignSelf:"center"}},right)
				])				
			)
		}		
	}
	const getParentNode = function(childNode,className){
		let parentNode = childNode.parentNode;
		while(parentNode!=null&&parentNode!=undefined){
			if(parentNode.classList.contains(className)) break;
			parentNode = parentNode.parentNode;
		}
		return parentNode;
	}
	class MenuDropdownElement extends StatefulComponent{		
		getInitialState(){return {maxHeight:"",right:null}}	
		calcMaxHeight(){
			if(!this.el) return;			
			const elTop = this.el.getBoundingClientRect().top;
			const innerHeight = getWindowRect().height;
			if(this.props.isOpen&&parseFloat(this.state.maxHeight)!=innerHeight - elTop)						
				this.setState({maxHeight:innerHeight - elTop + "px"});				
		}
		calc(){
			if(!this.el) return
			const menuRoot = getParentNode(this.el,"menuBar");
			const maxRight = menuRoot.getBoundingClientRect().right
			const elRight = this.el.getBoundingClientRect().right			
			if(elRight>maxRight){				
				if(this.state.right != 0) this.setState({right:0})
			}
		}
		componentDidMount(){
			//checkActivateCalls.add(this.calc)
			this.calc()
		}
		componentDidUpdate(){
			this.calc()
		}
		componentWillUnmount(){
			//checkActivateCalls.remove(this.calc)
		}
		render(){
			return $("div",{
				ref:ref=>this.el=ref,
				style: {
					position:'absolute',					
					minWidth:'7em',					
					boxShadow:GlobalStyles.boxShadow,
					zIndex:'10002',
					transitionProperty:'all',
					transitionDuration:'0.15s',
					transformOrigin:'50% 0%',
					borderWidth:GlobalStyles.borderWidth,
					borderStyle:GlobalStyles.borderStyle,
					borderColor:"#2196f3",					
					maxHeight:this.state.maxHeight,
					right:this.state.right!==null?this.state.right+"px":"",
					...this.props.style
				}
			},this.props.children);			
		}				
	}
	class FolderMenuElement extends StatefulComponent{		
		getInitialState(){return {mouseEnter:false,touch:false}}			
		mouseEnter(e){
			this.setState({mouseEnter:true});
		}
		mouseLeave(e){
			this.setState({mouseEnter:false});
		}
		onClick(e){
		    if(this.props.onClick)
		        this.props.onClick(e);
			e.stopPropagation();			
		}
		render(){		
			const selStyle={
				position:'relative',
                backgroundColor:'inherit',
                whiteSpace:'nowrap',
                paddingRight:'0.8em',
				cursor:"pointer",
				...this.props.style,
				...(this.state.mouseEnter?this.props.overStyle:null)
			};						
				
			return $("div",{				
			    style:selStyle,
			    onMouseEnter:this.mouseEnter,
			    onMouseLeave:this.mouseLeave,
			    onClick:this.onClick			   
			},this.props.children);
		}
	}
	class ExecutableMenuElement extends StatefulComponent{		
		getInitialState(){return {mouseEnter:false}}
		mouseEnter(e){
			this.setState({mouseEnter:true});
		}
		mouseLeave(e){
			this.setState({mouseEnter:false});
		}
		onClick(e){
			if(this.props.onClick)
				this.props.onClick(e);
		}
		render(){
			const newStyle={
                minWidth:'7em',
               // height:'2.5em',
               // backgroundColor:'#c0ced8',
                cursor:'pointer',
				...this.props.style,
				...(this.state.mouseEnter?this.props.overStyle:null)
			};       
		return $("div",{
            style:newStyle,    
            onMouseEnter:this.mouseEnter,
            onMouseLeave:this.mouseLeave,
            onClick:this.onClick
		},this.props.children);
		}
	}
	const TabSet=({style,children})=>$("div",{style:{
		borderBottomWidth:GlobalStyles.borderWidth,
		borderBottomStyle:GlobalStyles.borderStyle,		         
		overflow:'hidden',
		display:'flex',
		marginTop:'0rem',
		...style
	}},children);
	class DocElement extends StatefulComponent{
		sentData(){
			const values = this.el.getBoundingClientRect()
			const remH = this.remRef.getBoundingClientRect().height
			const w = getWindowRect()
			const ww = w.width
			const wh = w.height
			const b = documentManager.body().clientWidth
			const ss = w - b
			const width = ww
			const height = wh
			if(width!=this.width||height!=this.height){
				this.props.onWResize && this.props.onWResize("change",`${width},${height},${remH}`)
				this.width = width
				this.height = height
			}
		}
		onResize(){
			if(!this.el || !this.remRef) return				
			const isSibling = Branches.isSibling(this.ctx)
			if(isSibling) return			
			if(this.unmounted) return
			this.sentData()			
		}
		componentWillUnmount(){
			this.unmounted = true			
			if(this.resizeL) {
				log(`delete listener`)
				this.resizeL = this.resizeL.unreg()
			}
		}
		initListener(){			
			const isSibling = Branches.isSibling(this.ctx)
			if(isSibling) return
			if(this.props.onWResize && this.el && this.remRef && !this.resizeL) {
				log(`init listener`)
				this.sentData()
				this.resizeL = resizeListener.reg(this.onResize)
			}
		}
		componentDidUpdate(prevProps){
			this.initListener()
		}
		componentDidMount(){
			const node = documentManager.body().querySelector("#dev-content");
			const nodeOld = documentManager.body().querySelector("#content");
			this.ctx = rootCtx(this.props.ctx)
			if(node)
			while (node.hasChildNodes())
				node.removeChild(node.lastChild);
			
			if(nodeOld)
			while (nodeOld.hasChildNodes())
				nodeOld.removeChild(nodeOld.lastChild)			
			this.initListener()
		}
		render(){			
			const isSibling = Branches.isSibling(this.ctx)						
			return [
				$("div",{key:"1",style:this.props.style,ref:ref=>this.el=ref},this.props.children),				
				$("div",{key:"2",style:{height:"1em", position:"absolute",zIndex:"-1", top:"0px"},ref:ref=>this.remRef=ref})				
			]
		}
	}
	const GrContainer= ({style,children})=>$("div",{style:{
		boxSizing:'border-box',           
		fontSize:'0.875em',
		lineHeight:'1.1em',
		margin:'0px auto',
		paddingTop:'0.3125em',
		...style
	}},children);
	class FlexGroup extends StatefulComponent{		
		getInitialState(){return {rotated:false,captionOffset:"",containerMinHeight:""}}
		getCurrentBpPixels(){
			const bpPixels = parseInt(this.props.bp || this.bp)
			if(!this.emEl) return bpPixels*13
			return bpPixels * this.emEl.getBoundingClientRect().height;
		}
		shouldRotate(){			
			const elWidth = this.groupEl.getBoundingClientRect().width
			const bpWidth = this.getCurrentBpPixels()
			if(elWidth<bpWidth && this.state.rotated){
				this.setState({rotated:false});
				return true;
			}
			else if(elWidth> bpWidth && !this.state.rotated){
				this.setState({rotated:true});
				return true;
			}	
			return false;
		}
		recalc(){
			if(!this.captionEl) return;
			const block=this.captionEl.getBoundingClientRect();
			const cs=getComputedStyle(this.groupEl);			
			const containerMinHeight=(Math.max(block.height,block.width) + parseFloat(cs.paddingBottom||0) + parseFloat(cs.paddingTop||0)) +'px';			
			const captionOffset=(-Math.max(block.height,block.width))+'px';
			if(this.state.captionOffset!=captionOffset || this.state.containerMinHeight!=containerMinHeight)
				this.setState({captionOffset,containerMinHeight});
			this.shouldRotate();
		}
		componentDidMount(){
			this.bp = "15"
			if(this.props.caption){
				checkActivateCalls.add(this.recalc)
			}					
		}
		componentDidUpdate(prevProps,prevState){						
			if(prevProps.caption && !this.props.caption)
				checkActivateCalls.remove(this.recalc)
			if(!prevProps.caption && this.props.caption)
				checkActivateCalls.add(this.recalc)
		}
		componentWillUnmount(){
			if(this.props.caption){
				checkActivateCalls.remove(this.recalc)
			}
		}
		render(){			
			const style={
				backgroundColor:'white',
				borderColor:'#b6b6b6',
				borderStyle:'dashed',
				borderWidth:GlobalStyles.borderWidth,
				margin:'0.4em',
				padding:this.props.caption&&this.state.rotated?'0.5em 1em 1em 1.6em':'0.5em 0.5em 1em 0.5em',
				minHeight:this.state.rotated?this.state.containerMinHeight:"",
				position:"relative",
				...this.props.style
			};
			const captionStyle={
				color:"#727272",
				lineHeight:"1",
				marginLeft:this.state.rotated?"calc("+this.state.captionOffset+" - 1.7em)":"0em",
				position:this.state.rotated?"absolute":"static",
				transform:this.state.rotated?"rotate(-90deg)":"none",
				transformOrigin:"100% 0px",
				whiteSpace:"nowrap",
				marginTop:this.state.rotated?"1.5em":"0em",
				fontSize:"0.875em",
				display:"inline-block",
				...this.props.captionStyle
			};
			const emElStyle={
				position:"absolute",
				top:"0",
				zIndex:"-1",
				height:"1em"
			}
			const captionEl = this.props.caption? $("div",{ref:ref=>this.captionEl=ref,style:captionStyle,key:"caption"},this.props.caption): null;
			const emRefEl = $("div",{ref:ref=>this.emEl=ref,key:"emref",style:emElStyle});
			return $("div",{ref:ref=>this.groupEl=ref,style:style},[				
				captionEl,
				emRefEl,
				this.props.children
			])
		}	
	}	
	class ChipElement extends StatefulComponent{
		onClick(){
			if(this.props.onClick)
				this.props.onClick()
		}
		onEnter(event){
			//log(`Enter ;`)
			event.stopPropagation()
			if(!this.el) return
			this.onClick()
			const cEvent = eventManager.create("cTab",{bubbles:true})
			this.el.dispatchEvent(cEvent)							
		}
		componentDidMount(){
			if(!this.el) return
			this.el.addEventListener("enter",this.onEnter)
		}
		componentWillUnmount(){
			this.el.removeEventListener("enter",this.onEnter)
		}
		render(){
			const {value,style,tooltip,children} = this.props			
			const title = tooltip?tooltip:null
			return	$("div",{style:{
				fontSize:'1em',
				color:'white',
				textAlign:'center',
				borderRadius:'0.28em',
				//border:`${GlobalStyles.borderWidth} ${GlobalStyles.borderStyle} transparent`,		
				backgroundColor:"#eee",
				cursor:this.props.onClick?'pointer':'default',
				//width:'3.8em',
				display:'inline-block',				
				margin:'0 0.1em',
				verticalAlign:"top",
				paddingTop:"0.05em",
				paddingBottom:"0.2em",
				paddingLeft:"0.4em",
				paddingRight:children?"0em":"0.4em",		
				whiteSpace:"nowrap",
				alignSelf:"center",
				MozUserSelect:"none",
				userSelect:"none",				
				...style
			},className:"button",onClick:this.onClick,ref:ref=>this.el=ref,'data-src-key':this.props.srcKey,title},[value,children])
		}
	}
	const ChipDeleteElement = ({style,onClick}) =>$(Interactive,{},(actions)=>{
			const fillColor = style&&style.color?style.color:"black";
			const svg = `
			<svg version="1.1" xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" x="0px" y="0px" viewBox="0 0 348.333 348.334" style="enable-background:new 0 0 348.333 348.334;" xml:space="preserve" fill="${fillColor}">
				<path d="M336.559,68.611L231.016,174.165l105.543,105.549c15.699,15.705,15.699,41.145,0,56.85 c-7.844,7.844-18.128,11.769-28.407,11.769c-10.296,0-20.581-3.919-28.419-11.769L174.167,231.003L68.609,336.563 c-7.843,7.844-18.128,11.769-28.416,11.769c-10.285,0-20.563-3.919-28.413-11.769c-15.699-15.698-15.699-41.139,0-56.85   l105.54-105.549L11.774,68.611c-15.699-15.699-15.699-41.145,0-56.844c15.696-15.687,41.127-15.687,56.829,0l105.563,105.554   L279.721,11.767c15.705-15.687,41.139-15.687,56.832,0C352.258,27.466,352.258,52.912,336.559,68.611z"/>
			</svg>`;
			const svgData = svgSrc(svg)
			const deleteEl = $("img",{src:svgData,style:{height:"0.6em",verticalAlign:"middle"}},null);
			return $("div",{style:{
				//"float":"right",
				//color:"#666",
				width:"0.8em",
				cursor:"pointer",
				//height:"100%",
				display:"inline-block",
				//borderRadius:"0 0.3em 0.3em 0",
				//backgroundColor:"transparent",			
				...style
			},onMouseOver:actions.onMouseOver,onMouseOut:actions.onMouseOut,onClick},$("span",{style:{
				fontSize:"0.7em",
				position:"relative",
				bottom:"calc(0.1em)"
			}},deleteEl))
		}
	)	
	
	
	class TableElement extends StatefulComponent{		
		check(){
			if(!this.el) return			
			if(!this.emEl) return
			if(!this.el.parentElement) return	
			const isSibling = Branches.isSibling(this.ctx)
			if(isSibling) return
			const emRect= this.emEl.getBoundingClientRect()
			if(emRect.height == 0) return
			const dRect = this.el.getBoundingClientRect()
			const pdRect = this.el.parentElement.getBoundingClientRect()			
			if(this.prev!=pdRect.width){
				if(dRect.width>pdRect.width || Math.round(this.props.clientWidth) != parseInt(dRect.width)){					
					const emWidth = pdRect.width/emRect.height;
					log(emWidth.toString())
					this.props.onClickValue("change",emWidth.toString())					
				}				
				//log(`set: ${dRect.width}`)
				this.prev = pdRect.width
			}			
		}
		onInputEnter(e){
			const event = eventManager.create("keydown",{bubbles:true,key:"ArrowDown"})			
			e.stopPropagation()
			eventManager.sendToWindow(event)
		}
		componentDidMount(){
			if(this.props.dynamic) checkActivateCalls.add(this.check)
			if(!this.el) return	
			this.ctx = rootCtx(this.props.ctx)
			this.el.addEventListener("cTab",this.onInputEnter)
		}
		componentWillUnmount(){
			if(this.props.dynamic) checkActivateCalls.remove(this.check)			
			this.el.removeEventListener("cTab",this.onInputEnter)
		}
		render(){			
			const {style,children} = this.props
			const emElStyle={
				position:"absolute",
				top:"0",
				zIndex:"-1",
				height:"1em"
			}			
			const emRefEl = $("div",{ref:ref=>this.emEl=ref,key:"emref",style:emElStyle});
			return [
				$("table",{
					key:"table",
					style:{
					borderCollapse:'separate',
					borderSpacing:GlobalStyles.borderSpacing,
					width:'100%',
					lineHeight:"1.1",
					minWidth:"0",
					...style
					},ref:ref=>this.el=ref},children),
					emRefEl
				]
		}
	}
	class THeadElement extends StatefulComponent{		
		getInitialState(){return {dims:null,floating:false}}
		findTable(){
			if(!this.el) return;
			let parent = this.el.parentElement;
			while(parent&&parent.tagName!="TABLE") parent = parent.parentElement;
			return parent;
		}
		onScroll(ev){
			const tableEl  = this.findTable();
			const target = ev.target;
			if(!tableEl||target.lastElementChild != tableEl) return;			
			const floating = target.getBoundingClientRect().top > tableEl.getBoundingClientRect().top;
			if( floating&& !this.state.floating ) this.setState({floating});
			else if(!floating && this.state.floating) this.setState({floating});				
		}
		calcDims(){
			if(!this.el) return;
			const dim =this.el.getBoundingClientRect();
			const height = dim.height +"px";
			const width = dim.width +"px"			
			this.setState({dims:{height,width}});
		}
		render(){
			const height = this.state.floating&&this.state.dims?this.state.dims.height:"";
			const width = this.state.floating&&this.state.dims?this.state.dims.width:"";
			const style={
				position:this.state.floating?"absolute":"",
				height:height,
				display:this.state.floating?"table":"",
				width:width,				
				...this.props.style
			};
			const expHeaderStyle ={
				height: height,
				display:this.state.floating?"block":"none",
			};
			
			return this.state.floating?$("div",{style:expHeaderStyle},$("thead",{style:style},this.props.children)):$("thead",{ref:ref=>this.el=ref,style:style},this.props.children);				
			
		}
	}
	//let lastFocusTr = null
	const TBodyElement = ({style,children})=>$("tbody",{style:style},children);	
	class THElement extends StatefulComponent{		
		getInitialState(){return {last:false,focused:false}}
		onFocus(e){
			if(!this.el) return
			focusModule.switchTo(this)
			this.setState({focused:true})
			const focusMarkerInput = this.el.querySelector("input")
			let detail = null
			if(focusMarkerInput){
				const cls = Array.from(focusMarkerInput.classList)
				const marker = "marker-"
				const cl = cls.find(_=>_.indexOf(marker)==0)
				if(cl) detail = cl.substring(marker.length)
			}
			const cEvent = eventManager.create("cFocus",{bubbles:true,detail})
			this.el.dispatchEvent(cEvent)
			/*const pc = e.path.find(el=>Array.from(el.classList).some(cl=>cl.includes("marker")))
			if(!pc || pc==this.el){
				const clickEvent = eventManager.create("click",{bubbles:true})
				this.el.dispatchEvent(clickEvent)
			}*/
			//e.stopPropagation()
		}
		onBlur(e){
			if(e&&e.relatedTarget && e.relatedTarget.classList.contains("vkElement")) return
			focusModule.switchOff(this,e&&e.relatedTarget)
			this.setState({focused:false})
		}
		checkForSibling(){
			if(!this.el) return;
			if(!this.el.nextElementSibling) if(!this.state.last) this.setState({last:true})
			if(this.el.nextElementSibling) if(this.state.last) this.setState({last:false})	
		}
		componentDidMount(){
			this.checkForSibling()
			if(this.el && this.el.tagName=="TD") {
				//this.el.addEventListener("focus",this.onFocus,true)
				//this.el.addEventListener("blur",this.onBlur)
				//this.el.addEventListener("enter",this.onEnter)
				this.binding = focusModule.reg(this)
				if(this.props.draggable || this.props.droppable)
					this.dragBinding = dragDropModule.dragReg({node:this.el,dragData:this.props.dragData})			
			}			
		}
		componentDidUpdate(prevProps,_){
			this.checkForSibling()
			if(this.dragBinding)
				this.dragBinding.update({node:this.el,dragData:this.props.dragData})
			else if(this.props.draggable || this.props.droppable)
				this.dragBinding = dragDropModule.dragReg({node:this.el,dragData:this.props.dragData})			
		    if(!this.props.draggable && !this.props.droppable) return
			if(prevProps.mouseEnter!=this.props.mouseEnter && this.props.mouseEnter) this.dragBinding.dragOver(this.el)
		}			
		componentWillUnmount(){
			if(this.dragBinding) this.dragBinding.release();
			//if(this.el) this.el.removeEventListener("focus",this.onFocus)	
			//if(this.el) this.el.removeEventListener("enter",this.onEnter)					
			if(this.binding) this.binding.unreg()			
		}			
		onClick(e){
			if(this.props.onClick) this.props.onClick(e)
		}
		onMouseDown(e){			
			if(!this.props.draggable) return;
			if(!this.el) return;			
			if(this.dragBinding){
				const aEl = documentManager.activeElement()
				if(aEl) aEl.blur()
				this.dragBinding.dragStart(e,this.el)
			}			
			e.preventDefault();
		}
		onMouseUp(e){
			if(!this.props.droppable) return;
			if(this.dragBinding)
				this.dragBinding.dragDrop(this.el)
			//const data = dragDropModule.onDrag()&&dragDropModule.getData()
			/*if(data && this.props.onDragDrop){
				dragDropModule.release()
				e.stopPropagation();
				this.props.onDragDrop("dragDrop",data)
			}*/
		}
		render(){
			const {style,colSpan,children,rowSpan} = this.props
			const rowSpanObj = rowSpan?{rowSpan}:{}
			const nodeType = this.props.nodeType?this.props.nodeType:"th"
			//const hightlight = this.props.droppable&&this.props.mouseEnter&&dragDropModule.onDrag()
			const tabIndex = this.props.tabIndex?{tabIndex:this.props.tabIndex}:{}
			const focusActions = nodeType=="td"?{onFocus:this.onFocus,onBlur:this.onBlur}:{}
			const className = "marker"
			
			return $(nodeType,{style:{
				borderBottom:`${GlobalStyles.borderWidth} ${GlobalStyles.borderStyle} #b6b6b6`,
				borderLeft:'none',
				borderRight:!this.state.last?`${GlobalStyles.borderWidth} ${GlobalStyles.borderStyle} #b6b6b6`:"none",
				borderTop:`${GlobalStyles.borderWidth} ${GlobalStyles.borderStyle} #b6b6b6`,
				fontWeight:'bold',
				padding:'0.1em 0.2em',
				verticalAlign:'middle',
				overflow:"hidden",				
				textOverflow:"ellipsis",
				cursor:this.props.draggable?"move":"auto",
				backgroundColor:"transparent",
				outlineWidth:"1px",
				outlineColor:"red",
				outlineStyle:this.state.focused?"dashed":"none",
				outlineOffset:"-1px",
				...style
			},colSpan,
			ref:ref=>this.el=ref,
			onClick:this.onClick,
			onMouseDown:this.onMouseDown,
			onTouchStart:this.onMouseDown,			
			onMouseEnter:this.props.onMouseEnter,
			onMouseLeave:this.props.onMouseLeave,
			onMouseUp:this.onMouseUp,
			onTouchEnd:this.onMouseUp,
			className:className,
			...rowSpanObj,
			...tabIndex,
			...focusActions,			
			},children)
		}
	}
	const TDElement = (props) =>{		
		if(props.droppable)
			return $(Interactive,{},actions=>$(THElement,{...props,style:{padding:'0.1em 0.2em',fontSize:'1em',fontWeight:'normal',borderBottom:'none',...props.style},nodeType:"td",...actions,tabIndex:"1"}))
		else	
			return $(THElement,{...props,style:{padding:'0.1em 0.2em',fontSize:'1em',fontWeight:'normal',borderBottom:'none',...props.style},nodeType:"td",tabIndex:"1"})
	}	
	class TRElement extends StatefulComponent{		
		getInitialState(){return {touch:false,mouseOver:false}}
		onTouchStart(e){
			if(this.props.onClick){
				this.setState({touch:true});
			}
		}
		onTouchEnd(e){
			if(this.props.onClick){
				this.setState({touch:false});
			}
		}
		onMouseEnter(e){
			this.setState({mouseOver:true});
		}
		onMouseLeave(e){
			this.setState({mouseOver:false});
		}
		onEnter(e){
			if(e.key == "Enter" && this.props.onClickValue){			
				this.props.onClickValue("key","enter")
			}					
		}
		componentDidMount(){			
			//this.el.addEventListener("enter",this.onEnter)
			this.el.addEventListener("mousedown",this.onClick)
		}
		componentWillUnmount(){			
			this.el.removeEventListener("mousedown",this.onClick)
		}
		onClick(e){		
			if(this.props.onClick){
				if(!this.sentClick) {
					this.props.onClick(e)					
					this.sentClick = true
					setTimeout(()=>{this.sentClick=false},1000)
				}
			}
		}
		render(){
			const trStyle={
				outline:this.state.touch?`${GlobalStyles.outlineWidth} ${GlobalStyles.outlineStyle} ${GlobalStyles.outlineColor}`:'none',
				outlineOffset:GlobalStyles.outlineOffset,
				...(this.props.odd?{backgroundColor:'#fafafa'}:{backgroundColor:'#ffffff'}),
				...(this.state.mouseOver?{backgroundColor:'#eeeeee'}:null),
				...this.props.style
			};			
			return $("tr",{ref:ref=>this.el=ref,style:trStyle,onMouseEnter:this.onMouseEnter,onKeyDown:this.onEnter,onMouseLeave:this.onMouseLeave,onTouchStart:this.onTouchStart,onTouchEnd:this.onTouchEnd},this.props.children);
		}	
	}
	class Interactive extends StatefulComponent{		
		getInitialState(){return {mouseOver:false,mouseEnter:false}}
		onMouseOver(e){
			this.setState({mouseOver:true});
		}
		onMouseOut(e){
			this.setState({mouseOver:false});
		}
		onMouseEnter(e){
			this.setState({mouseEnter:true});
		}
		onMouseLeave(e){
			this.setState({mouseEnter:false});
		}
		render(){ 
			return this.props.children({
				onMouseOver:this.onMouseOver,
				onMouseOut:this.onMouseOut,
				onMouseEnter:this.onMouseEnter,
				onMouseLeave:this.onMouseLeave,
				mouseOver:this.state.mouseOver,
				mouseEnter:this.state.mouseEnter
			});
		}
	}
	class InputElementBase extends StatefulComponent{		
		getInitialState(){return {visibility:""}}
		setFocus(focus){
			if(!focus) return
			this.getInput().focus()			
		}
		onKeyDown(e){
			if(!this.inp) return
			if(e.key == "Escape"){
				if(this.prevval != undefined) {
					const inp = this.getInput()
					inp.value = this.prevval
					if(this.props.onChange) this.props.onChange({target:{headers:{"X-r-action":"change"},value:inp.value}})
				}
				this.prevval = undefined				
				this.getInput().parentElement.focus()
			}			
			if(this.props.onKeyDown && !this.props.onKeyDown(e)) return			
			/*if(e.keyCode == 13) {
				if(this.inp2) this.inp2.blur()
				else this.inp.blur()
			}*/
		}
		doIfNotFocused(what){
			const inp = this.getInput()
			const aEl = documentManager.activeElement()			
			if(inp != aEl) {
				this.setFocus(true)
				what(inp)
				return true
			}
			return false
		}
		isVkEvent(event){
			return event.detail && typeof event.detail == "object"?event.detail.vk:false			
		}
		getInput(){ return this.inp2||this.inp}
		onEnter(event){
			//log(`Enter ;`)
			if((this.isVkEvent(event) || this.props.vkOnly) || !this.doIfNotFocused((inp)=>{				
				this.prevval = inp.value
				inp.selectionEnd = inp.value.length
				inp.selectionStart = inp.value.length
			}))	{
				const markerButton = this.props.mButtonEnter
				let cEvent
				if(markerButton){
					const inp = this.getInput()
					cEvent = eventManager.create("cEnter",{bubbles:true,detail:markerButton})
					if(this.props.onBlur) this.props.onBlur()
					else if(this.props.onChange) this.props.onChange({target:{headers:{"X-r-action":"change"},value:inp.value}})
					else {}
				}
				else{
					cEvent = eventManager.create("cTab",{bubbles:true})
				}
				this.cont.dispatchEvent(cEvent)				
			}
			event.stopPropagation()
		}
		onDelete(event){
			//log(`Delete`)
			event.stopPropagation()
			this.s = null
			if(this.props.noDel) return
			if(!this.doIfNotFocused((inp)=>{				
				this.prevval = inp.value
				if(this.isVkEvent(event)||this.props.vkOnly){					
					inp.value = inp.value+event.detail.key
				}
				else 
					inp.value = ""
				this.onChange({target:{headers:{"X-r-action":"change"},value:inp.value}})
				//const cEvent = eventManager.create("input",{bubbles:true})				
				//inp.dispatchEvent(cEvent)				

			})){				
				if(this.isVkEvent(event)||this.props.vkOnly){	
					const inp = this.getInput()
					const value1 = inp.value.substring(0, inp.selectionStart)
					const value2 = inp.value.substring(inp.selectionEnd)
					this.s = inp.selectionStart+1
					inp.value = value1+event.detail.key+value2					
					this.onChange({target:{headers:{"X-r-action":"change"},value:inp.value}})
					//const cEvent = eventManager.create("input",{bubbles:true})							
					//inp.dispatchEvent(cEvent)
        }
			}									
		}
		onErase(event){
			
			const inp = this.getInput()	
			inp.value = ""			
			if(this.props.onChange) this.props.onChange({target:{headers:{"X-r-action":"change"},value:inp.value}})				
			if(this.props.onBlur) this.props.onBlur()
			//else if(this.props.onChange) this.props.onChange({target:{headers:{"X-r-action":"change"},value:inp.value}})
			//const cEvent = eventManager.create("input",{bubbles:true})							
			//inp.dispatchEvent(cEvent)	
		}
		onBackspace(event){
			//log(`Backspace`)
			event.stopPropagation()
			this.s = null
			if(this.props.noDel) return
			if(!this.doIfNotFocused((inp)=>{				
				this.prevval = inp.value
				inp.value = inp.value.slice(0,-1)
            if(this.props.onChange) this.props.onChange({target:{headers:{"X-r-action":"change"},value:inp.value}})
				//const cEvent = eventManager.create("input",{bubbles:true})				
				//inp.dispatchEvent(cEvent)
			})){
				if(this.isVkEvent(event)||this.props.vkOnly){		
					const inp = this.getInput()
					const value1 = inp.value.substring(0, inp.selectionStart-1)				
					const value2 = inp.value.substring(inp.selectionEnd)
					this.s = inp.selectionStart - 1>=0?inp.selectionStart -1:0
					inp.value = value1+value2
					if(this.props.onChange) this.props.onChange({target:{headers:{"X-r-action":"change"},value:inp.value}})
					//const cEvent = eventManager.create("input",{bubbles:true})							
					//inp.dispatchEvent(cEvent)
				}
			}
		}
		onPaste(event){
			//log(`Paste`)
			this.doIfNotFocused((inp)=>{				
				this.prevval = inp.value
				inp.value = event.detail
				if(this.props.onChange) this.props.onChange({target:{headers:{"X-r-action":"change"},value:inp.value}})
				//const cEvent = eventManager.create("input",{bubbles:true})
				//inp.dispatchEvent(cEvent)
    	  })				
			event.stopPropagation()
		}
		onCopy(event){
			//log(`Copy`)
			this.doIfNotFocused((inp)=>{				
				this.prevval = inp.value
				inp.setSelectionRange(0,inp.value.length)
				documentManager.execCopy()
			})				
			event.stopPropagation()
		}
		componentDidMount(){
			//this.setFocus(this.props.focus)
			const inp = this.getInput()			
			inp.addEventListener('enter',this.onEnter)
			inp.addEventListener('delete',this.onDelete)
			inp.addEventListener('erase',this.onErase)
			inp.addEventListener('backspace',this.onBackspace)
			inp.addEventListener('cpaste',this.onPaste)
			inp.addEventListener('ccopy',this.onCopy)
		}
		componentWillUnmount(){
			const inp = this.getInput()			
			inp.removeEventListener('enter',this.onEnter)
			inp.removeEventListener('delete',this.onDelete)
			inp.removeEventListener('erase',this.onErase)
			inp.removeEventListener('backspace',this.onBackspace)
			inp.removeEventListener('cpaste',this.onPaste)
			inp.removeEventListener('ccopy',this.onCopy)
			if(this.dragBinding) this.dragBinding.releaseDD()
		}
		componentDidUpdate(){			
			if(this.props.cursorPos){
				const pos = this.props.cursorPos()
				const inp = this.getInput()
				if(pos.ss) inp.selectionStart = pos.ss
				if(pos.se) inp.selectionEnd = pos.se
			}
		}	
		onChange(e){
			const inp = this.getInput()
			if(this.s!==null&&this.s!==undefined) {inp.selectionEnd =this.s;inp.selectionStart = this.s}
			if(this.inp&&getComputedStyle(this.inp).textTransform=="uppercase"){
				const newVal = e.target.value.toUpperCase();
				e.target.value = newVal;
			}
			if(this.props.onChange) this.props.onChange({target:{headers:{"X-r-action":"change"},value:e.target.value}})
		}
		onBlur(e){						
			if(e.relatedTarget && (
				e.relatedTarget.classList.contains("vkElement") ||
				e.relatedTarget.classList.contains("vkContainer") ||
				e.relatedTarget.classList.contains("vkKeyboard")
			)) return
			if(this.props.onBlur) {
				const inp = this.getInput()
				if(this.props.onChange) this.props.onChange({target:{headers:{"X-r-action":"change"},value:inp.value}})
				this.props.onBlur()
			}
		}
	    onMouseDown(e){			
			if(!this.props.div) return
			if(!this.props.onReorder) return
			this.dragBinding = dragDropModule.dragStartDD(e,this.inp,this.onMouseUpCall)
			if(this.dragBinding)
				this.setState({visibility:"hidden"})
			//e.preventDefault()
		}
		onMouseUpCall(newPos){
			if(!this.props.div) return
			if(!this.props.onReorder) return			
			this.setState({visibility:""})
			this.props.onReorder("reorder",newPos.toString())
		}
		render(){						
			const inpContStyle={
				display:"flex",
				height:"auto",
				lineHeight:"1",
				margin:"0.124em 0em",				
				verticalAlign:"middle",
				width:"100%",
				border:`${GlobalStyles.borderWidth} ${GlobalStyles.borderStyle}`,
				borderColor:this.props.mouseOver?"black":"rgb(182, 182, 182)",
				backgroundColor:(this.props.onChange||this.props.onBlur)?"white":"#eeeeee",
				boxSizing:"border-box",
				...this.props.style
			};
			const inp2ContStyle={
				flex:"1 1 0%",
				height:"auto",
				minHeight:"100%",
				overflow:"hidden",
				display:"flex"
			};
			const inputStyle={
				textOverflow:"ellipsis",
				margin:"0rem",
				verticalAlign:"top",
				color:"rgb(33,33,33)",
				border:"none",
				height:this.props.div?"auto":"100%",
				padding:"0.2172em 0.3125em 0.2172em 0.3125em",
				width:"100%",
				zIndex:"0",
				boxSizing:"border-box",
				MozAppearence:"none",
				whiteSpace:this.props.div?"normal":"nowrap",
				overflow:"hidden",
				fontSize:"inherit",
				textTransform:"inherit",
				backgroundColor:"inherit",
				outline:"none",
				textAlign:"inherit",
				display:this.props.div?"inline-block":"",
				fontFamily:"inherit",
				visibility:this.state.visibility,
				...this.props.inputStyle				
			};		
			const placeholder = this.props.placeholder?this.props.placeholder:"";
			const inputType = this.props.inputType;//this.props.inputType?this.props.inputType:"input"
			const type = this.props.type?this.props.type:"text"
			const auto = this.props.autocomplete?this.props.autocomplete:null
			const vkOnly = this.props.vkOnly?"vk":null
			let readOnly = (this.props.onChange||this.props.onBlur)?null:"true";
			readOnly = !readOnly&&vkOnly?"true":readOnly
			const rows= this.props.rows?this.props.rows:"2";
			const content = this.props.content;
			const actions = {onMouseOver:this.props.onMouseOver,onMouseOut:this.props.onMouseOut};
			const overRideInputStyle = this.props.div?{display:"flex",flexWrap:"wrap",padding:"0.24em 0.1em",width:"auto"}:{}	
			const dataType = this.props.dataType
			const className = this.props.className
			return $("div",{style:inpContStyle,ref:(ref)=>this.cont=ref,...actions},[
					this.props.shadowElement?this.props.shadowElement():null,
					$("div",{key:"xx",style:inp2ContStyle},[
						$(inputType,{
							key:"1",
							ref:(ref)=>this.inp=ref,
							type,rows,readOnly,placeholder,auto,
							"data-type":dataType,
							className,
							name:vkOnly,
							content,		
							style:{...inputStyle,...overRideInputStyle},							
							onChange:this.onChange,onBlur:this.onBlur,onKeyDown:this.onKeyDown,value:!this.props.div?this.props.value:"",
							onMouseDown:this.onMouseDown,
							onTouchStart:this.onMouseDown
							},this.props.div?[this.props.inputChildren,
								$("input",{
									style:{...inputStyle,alignSelf:"flex-start",flex:"1 1 20%",padding:"0px"},
									ref:ref=>this.inp2=ref,
									key:"input",
									className,
									onChange:this.onChange,
									onBlur:this.onBlur,
									readOnly,
									name:vkOnly,
									onKeyDown:this.onKeyDown,
									"data-type":dataType,
									value:this.props.value})
							]:(content?content:null)),							
						this.props.popupElement?this.props.popupElement():null
					]),
					this.props.buttonElement?this.props.buttonElement():null
				]);					
		}
	}
	const InputElement = (props) => $(Interactive,{},(actions)=>$(InputElementBase,{...props,ref:props._ref,inputType:props.div?"div":"input",...actions}))	
	const TextAreaElement = (props) => $(Interactive,{},(actions)=>$(InputElementBase,{...props,onKeyDown:()=>false,ref:props._ref,inputType:"textarea",
		inputStyle:{
			whiteSpace:"pre-wrap",
			...props.inputStyle
		},
		...actions}))
	const LabeledTextElement = (props) => $(InputElementBase,{
		...props,
		onKeyDown:()=>false,
		inputType:"div",
		inputStyle:{
			...props.inputStyle,
			display:"inline-block"
		},
		style:{
			...props.style,
			backgroundColor:"transparent",
			borderColor:"transparent",
			lineHeight:"normal"
		},
		content:props.value
		})
	class MultilineTextElement extends StatefulComponent{			
		getInitialState(){return {maxItems:0}}
		getMaxItems(){			
		    const maxLines = parseInt(this.props.maxLines?this.props.maxLines:9999)
			let line = 0
			let bottomValue = 0
			const maxItems = Array.from(this.el.children).filter(c=>{
                const cBottom = Math.floor(c.getBoundingClientRect().bottom)				
				if(cBottom > bottomValue) {line++; bottomValue=cBottom}
				if(line>maxLines) return false
				return true
			})
			return maxItems.length
		}
		check(){
			const maxItems = this.getMaxItems()	
			if(maxItems!=this.state.maxItems) this.setState({maxItems})
		}
		componentDidMount(){			
		    checkActivateCalls.add(this.check)
		}
		componentWillUnmount(){
			checkActivateCalls.remove(this.check)
		}
		render(){
			const values = this.props.value?this.props.value.split(' '):""
			const textStyle=(show)=>({
				display:"inline-block",
				marginRight:"0.5em",
				minHeight:"1em",
				visibility:!show?"hidden":""
			})
			const children = values.map((text,index)=>$('span',{key:index,style:textStyle(index<this.state.maxItems)},(index+1==this.state.maxItems && values.length>index+1)?text+"...":text))
			
			return $('div',{style:this.props.styles,ref:ref=>this.el=ref},children)
		}
	}
	class DropDownElement extends StatefulComponent{		
		getInitialState(){return {popupMinWidth:0,left:0,top:0}}
		getPopupPos(){
			if(!this.inp||!this.inp.cont) return {};
			const rect = this.inp.cont.getBoundingClientRect()
			let res = {}
			if(this.pop){
				const popRect = this.pop.getBoundingClientRect()
				const windowRect = getWindowRect()								
				const rightEdge = rect.right + popRect.width
				const leftEdge = rect.left - popRect.width
				const bottomEdge = rect.bottom + popRect.height
				const topEdge = rect.top - popRect.height;
				let top = 0
				let left = 0
				if(bottomEdge<=windowRect.bottom){					//bottom
					left = rect.left//rect.left - popRect.left
					top = rect.bottom					
				}
				else if(topEdge>windowRect.top){	//top
					top = rect.top - popRect.height
					left = rect.left//rect.left - popRect.left;							
				}
				else if(leftEdge>windowRect.left){	//left
					left = rect.left - popRect.width;
					top = rect.top - popRect.height/2;
					if(top+popRect.height>windowRect.bottom) top=windowRect.bottom - popRect.height
					if(top<0) top=0
				}
				else if(rightEdge<=windowRect.right){
					left = rect.right
					top = rect.top - popRect.height/2;		
					if(top+popRect.height>windowRect.bottom) top=windowRect.bottom - popRect.height
					if(top<0) top=0
				}
				//top+=getPageYOffset()
				
				if(this.state.top!=top||this.state.left!=left)
						res = {left,top}
			}
			return res;
		}
		onChange(e){
			if(this.props.onChange)
				this.props.onChange({target:{headers:{"X-r-action":"change"},value:e.target.value}});
		}
		onClick(e){
			if(this.props.onClick)
				this.props.onClick(e);
			//e.stopPropagation
		}
		onKeyDown(e){
			if(this.props.onKeyDown){
				this.props.onKeyDown(e)
				return false
			}
			let call=""
			let opt = null
			switch(e.key){				
				case "ArrowDown":
					e.stopPropagation();
					if(e.altKey == true){
						this.onClick()
						return
					}						
				case "ArrowUp":
					e.stopPropagation();				
				case "Enter":
					call = e.key;
					e.preventDefault();
					break;
				case "Backspace":
					if(e.target.value.length == 0)
						call = e.key;					
					break;
				case "Escape":					
					call = e.key;
					e.preventDefault()
					break;	
				case "Delete":
					call = e.key;
					const actEl = documentManager.activeElement()
					if(actEl){
						const button = actEl.querySelector(".button")
						opt = button?button.dataset.srcKey:null
					}
					break
			}
			if(call.length>0 && this.props.onClickValue)
				this.props.onClickValue("key",call,opt);			
			return false;
		}
		getPopupWidth(){
			if(!this.inp||!this.inp.cont) return {};
			const minWidth = this.inp.cont.getBoundingClientRect().width;
			if(Math.round(this.state.popupMinWidth) != Math.round(minWidth)) return {popupMinWidth:minWidth};
			return {};
		}
		mixState(){
			const nW = this.getPopupWidth()
			const nR = this.getPopupPos()
			const state = {...nW,...nR}
			//log(state)
			if(Object.keys(state).length>0) this.setState(state)
		}
		componentDidMount(){			
			if(this.props.open)
				checkActivateCalls.add(this.mixState)
		}
		componentWillUnmount(){
			if(this.props.open)
				checkActivateCalls.remove(this.mixState)
		}
		componentDidUpdate(prevProps){
			if(prevProps.open && !this.props.open)
				checkActivateCalls.remove(this.mixState)
			if(!prevProps.open && this.props.open)
				checkActivateCalls.add(this.mixState)
		}	
		render(){
			//const topPosStyle = this.state.bottom?{top:'',marginTop:-this.state.bottom+"px"}:{top:this.state.top?this.state.top+getPageYOffset()+"px":''}
			const popupStyle={
				position:"fixed",
				border: `${GlobalStyles.borderWidth} ${GlobalStyles.borderStyle} black`,
				minWidth: this.props.noAutoWidth?"":this.state.popupMinWidth + "px",
				overflow: "auto",				
				maxHeight: "10em",				
				backgroundColor: "white",
				zIndex: "10003",
				boxSizing:"border-box",
				overflowX:"hidden",
				//marginLeft:"",
				lineHeight:"normal",
				left:`calc(${this.state.left?this.state.left+"px":"0px"})`,
				top:`calc(${this.state.top?this.state.top+"px":"0px"})`,
				//left:this.state.left?this.state.left+"px":"",
				//top:this.state.top?this.state.top + getPageYOffset() + "px":"",
				...this.props.popupStyle
			};
			
			const buttonImageStyle={				
				verticalAlign:"middle",
				display:"inline",
				height:"100%",
				width:"100%",
				transform:this.props.open?"rotate(180deg)":"rotate(0deg)",
				transition:"all 200ms ease",
				boxSizing:"border-box",
				...this.props.buttonImageStyle
			};
			const svg ='<svg version="1.1" xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" x="0px" y="0px" width="16px" height="16px" viewBox="0 0 306 306" xml:space="preserve"><polygon points="270.3,58.65 153,175.95 35.7,58.65 0,94.35 153,247.35 306,94.35"/></svg>'
			const svgData=svgSrc(svg);
			const urlData = this.props.url?this.props.url:svgData;
			const className = this.props.focusMarker?`marker-${this.props.focusMarker}`:""		
			const buttonImage = $("img",{key:"buttonImg",src:urlData,style:buttonImageStyle},null);						
			const placeholder = this.props.placeholder?this.props.placeholder:"";
			const buttonElement = () => [$(ButtonInputElement,{key:"buttonEl",onClick:this.onClick},buttonImage)];
			const value = this.props.value
			const inputChildren = this.props.div? this.props.children.slice(0,parseInt(this.props.div)): null
			const popupElement = () => [this.props.open?$("div",{key:"popup",style:popupStyle,ref:ref=>this.pop=ref},this.props.div?this.props.children.slice(parseInt(this.props.div)):this.props.children):null];
			
			return $(InputElement,{...this.props,className,inputChildren,value,_ref:(ref)=>this.inp=ref,buttonElement,popupElement,onChange:this.onChange,onBlur:this.props.onBlur,onKeyDown:this.onKeyDown});							
		}
	}
	const ButtonInputElement = (props) => $(Interactive,{},(actions)=>{
		const openButtonWrapperStyle= {				
			flex:"1 1 0%",
			height:"auto",
			minHeight:"100%",
			overflow:"hidden",
			backgroundColor:"transparent",
			flex:"0 1 auto",
		};
		const openButtonStyle={
			minHeight:"",
			width:"1.5em",
			height:"100%",
			padding:"0.2em",
			lineHeight:"1",
			backgroundColor:"inherit",				
		};
		
		return $("div",{key:"inputButton",style:openButtonWrapperStyle},
			$(ButtonElement,{...props,...actions,style:openButtonStyle})
		);
	})
	class ControlWrapperElement extends StatefulComponent{		
		getInitialState(){ return {focused:false}}
		onFocus(e){
			const res = focusModule.switchTo(this)			
			if(!res) return
			if(this.el){
				const cEvent = eventManager.create("cFocus",{bubbles:true,detail:this.path})
				e.preventDefault();
				this.el.dispatchEvent(cEvent)
				//e.stopPropagation();
			}
			this.setState({focused:true})
		}
		onBlur(e){			
			if(e&&e.relatedTarget && e.relatedTarget.classList.contains("vkElement")) return
			const res = focusModule.switchOff(this, e&&e.relatedTarget)
			if(res) this.setState({focused:false})
		}
		componentDidMount(){
			if(this.el) {				
				this.el.addEventListener("focus",this.onFocus,true)
				this.el.addEventListener("blur",this.onBlur,true)
			}
			this.binding = focusModule.reg(this)			
			if(this.props.ctx)
				this.path = ctxToPath(this.props.ctx)
		}
		componentWillUnmount(){
			if(this.el) {				
				this.el.removeEventListener("focus",this.onFocus)
				this.el.removeEventListener("blur",this.onBlur)
			}
			this.binding.unreg()			
		}
		onClick(e){
			e.stopPropagation()
		}
		onRef(path){			
			return (ref)=> this.el=ref
		}		
		render(){
			const className = "focusWrapper"//this.props.focusMarker?`marker-${this.props.focusMarker}`:""			
			const {style,children} = this.props
			const focusedStyle  = this.state.focused
			const propsOnPath = (p0,p1) => /*p0 == p1 && p1.length>0 || */this.state.focused?{outlineStyle:"dashed"}:{outlineStyle:"none"}
			const sticky = this.props.sticky?"sticky":null
			return $(Consumer,{},path=>
				$("div",{style:{
					width:"100%",				
					padding:"0.4em 0.3125em",
					boxSizing:"border-box",
					outlineWidth:"1px",
					outlineColor:"red",				
					...propsOnPath(path,this.path),
					outlineOffset:"-1px",
					...style
				},tabIndex:"1",
				className,
				onClick:this.onClick,
				"data-sticky":sticky,
				ref:this.onRef(path)},children)
			)
		}
	}
	
	const LabelElement = ({style,onClick,label})=>$("label",{onClick,style:{
		color:"rgb(33,33,33)",
		cursor:onClick?"pointer":"auto",
		textTransform:"none",
		...style
	}},label?label:null)

	class FocusableElement extends StatefulComponent{		
		onFocus(e){
			clearTimeout(this.timeout);						
			if(!this.focus) this.reportChange("focus");			
			this.focus=true;			
		}
		reportChange(state){					
			if(this.props.onChange)
				this.props.onClickValue("focusChange",state)			
		}
		delaySend(){
			if(!this.focus)
				this.reportChange("blur");			
		}
		onBlur(e){					
			clearTimeout(this.timeout);
			this.timeout=setTimeout(this.delaySend,400);
			this.focus=false;
		}
		componentDidMount(){
			if(!this.el) return;
			this.el.addEventListener("focus",this.onFocus,true);
			this.el.addEventListener("blur",this.onBlur,true);
			if(this.props.onChange&&this.props.focus)
				this.el.focus();			
		}	
		componentWillUnmount(){
			if(!this.el) return;
			clearTimeout(this.timeout);
			this.timeout=null;
			this.el.removeEventListener("focus",this.onFocus);
			this.el.removeEventListener("blur",this.onBlur);
		}
		render(){
			const style={
				display:"inline-block",
				outline:"none",
				...this.props.style
			};			
			return $("div",{ref:ref=>this.el=ref,style:style,tabIndex:"0"},this.props.children);
		}
	}
	class PopupElement extends StatefulComponent{		
		getInitialState(){ return {top:0,left:0}}
		calcPosition(){
			if(!this.el) return;			
			const sibling = this.el.previousElementSibling;			
			if(!sibling) return;
			const rect = sibling.getBoundingClientRect();
			const popRect = this.el.getBoundingClientRect();					
			const windowRect = {top:0,left:0,right:documentManager.body().clientWidth,bottom:getWindowRect().height}
			const rightEdge = rect.right + popRect.width
			const leftEdge = rect.left - popRect.width
			const bottomEdge = rect.bottom + popRect.height
			const topEdge = rect.top - popRect.height;
			let top = 0
			let left = 0
			if(bottomEdge<=windowRect.bottom){					//bottom
				left = rect.left//rect.left - popRect.left
				if(left+ popRect.width> windowRect.right)
					left = windowRect.right - popRect.width
				top = rect.bottom					
			}
			else if(topEdge>windowRect.top){	//top
				top = rect.top - popRect.height				
				left = rect.left//rect.left - popRect.left;							
				if(left+ popRect.width> windowRect.right)
					left = windowRect.right - popRect.width
			}
			else if(leftEdge>windowRect.left){	//left
				left = rect.left - popRect.width;
				top = rect.top - popRect.height/2;					
			}
			else if(rightEdge<=windowRect.right){
				left = rect.right
				top = rect.top - popRect.height/2;					
			}			
			//top -= getPageYOffset()
			if(this.state.top!=top || this.state.left!=left)
				this.setState({top,left});			
		}
		componentDidMount(){
			if(!this.props.position) return;
			checkActivateCalls.add(this.calcPosition)			
		}
		componentWillUnmount(){
			checkActivateCalls.remove(this.calcPosition)
		}
		render(){			
			return $("div",{ref:ref=>this.el=ref,style:{				
				position:"fixed",
				zIndex:"6",
				border:`${GlobalStyles.borderWidth} ${GlobalStyles.borderStyle} #eee`,
				backgroundColor:"white",
				top:this.state.top+"px",
				left:this.state.left+"px",
				...this.props.style
			}},this.props.children);
		}		
	}
	const Checkbox = (props) => $(Interactive,{},(actions)=>$(CheckboxBase,{...props,...actions}))
	class CheckboxBase extends StatefulComponent{		
		getInitialState(){ return {focused:false}}
		onFocus(){
			focusModule.switchTo(this)
			this.setState({focused:true})
		}
		onBlur(){
			this.setState({focused:false})
		}
		onDelete(event){
			if(!event.detail) return
			this.onClick()
			event.stopPropagation()
		}
		componentDidMount(){
			if(this.el) {
				this.el.addEventListener("focus",this.onFocus,true)
				this.el.addEventListener("mousedown",this.onClick)
				this.el.addEventListener("blur",this.onBlur)
				this.el.addEventListener("delete",this.onDelete)
			}
			this.binding = focusModule.reg(this)
		}
		componentWillUnmount(){
			if(this.el) {				
				this.el.removeEventListener("focus",this.onFocus)
				this.el.removeEventListener("mousedown",this.onClick)
				this.el.removeEventListener("blur",this.onBlur)
				this.el.removeEventListener("delete",this.onDelete)
			}
			if(this.binding) this.binding.unreg()
		}
		onClick(e){
			if(this.props.onChange) 
				this.props.onChange({target:{headers:{"X-r-action":"change"},value:(this.props.value?"":"checked")}})			
			e&&e.stopPropagation()
		}
		render(){
			const props = this.props			
			const style={
				flexGrow:"0",				
				position:"relative",
				maxWidth:"100%",
				padding:"0.4em 0.3125em",				
				flexShrink:"1",
				boxSizing:"border-box",
				lineHeight:"1",
				outline:this.state.focused?"1px dashed red":"none",
				...props.altLabel?{margin:"0.124em 0em",padding:"0em"}:null,
				...props.style
			};
			const innerStyle={
				border:"none",
				display:"inline-block",
				lineHeight:"100%",
				margin:"0rem",				
				outline:"none",				
				whiteSpace:"nowrap",
				width:props.label?"calc(100% - 1em)":"auto",
				cursor:"pointer",
				bottom:"0rem",
				...props.innerStyle
			};
			const checkBoxStyle={
				border:`${GlobalStyles.borderWidth} ${GlobalStyles.borderStyle}`,
				color:"#212121",
				display:"inline-block",
				height:"1.625em",
				lineHeight:"100%",
				margin:"0em 0.02em 0em 0em",
				padding:"0rem",
				position:"relative",
				verticalAlign:"middle",
				width:"1.625em",
				boxSizing:"border-box",
				borderColor:props.mouseOver?"black":"rgb(182, 182, 182)",
				backgroundColor:props.onChange?"white":"#eeeeee",
				...props.altLabel?{height:"1.655em",width:"1.655em"}:null,
				...props.checkBoxStyle
			};

			const labelStyle={
				maxWidth:"calc(100% - 2.165em)",
				padding:"0rem 0.3125em",
				verticalAlign:"middle",
				cursor:"pointer",
				display:"inline-block",
				lineHeight:"1.3",
				overflow:"hidden",
				textOverflow:"ellipsis",
				whiteSpace:"nowrap",
				boxSizing:"border-box",
				...props.labelStyle
			};
			const imageStyle = {				
				bottom:"0rem",
				height:"90%",
				width:"100%",
			};	
			
			const svg = '<svg version="1.1" xmlns="http://www.w3.org/2000/svg" width="16px" viewBox="0 0 128.411 128.411"><polygon points="127.526,15.294 45.665,78.216 0.863,42.861 0,59.255 44.479,113.117 128.411,31.666"/></svg>';
			const svgData=svgSrc(svg);
			const defaultCheckImage = props.value&&props.value.length>0?$("img",{style:imageStyle,src:svgData,key:"checkImage"},null):null
			const labelEl = props.label?$("label",{style:labelStyle,key:"2"},props.label):null;
			const checkImage = props.checkImage?props.checkImage:defaultCheckImage;
			const {onMouseOver,onMouseOut} = props		
			return $("div",{style,tabIndex:"1",ref:ref=>this.el=ref},
				$("span",{onMouseOver,onMouseOut,style:innerStyle,key:"1"},[
					$("span",{style:checkBoxStyle,key:"1"},checkImage),
					labelEl
				])
			);
		}
	}
	
	const RadioButtonElement = (props) => {		
		const isLabeled = props.label&&props.label.length>0;			
		const innerStyle={
			...!isLabeled?{width:"auto"}:null,				
			...props.innerStyle
		};
		const checkBoxStyle={				
			height:"1em",								
			width:"1em",
			boxSizing:"border-box",				
			textAlign:"center",				
			borderRadius:"50%",
			verticalAlign:"baseline",
			...props.checkBoxStyle
		};			
		const imageStyle = {								
			height:"0.5em",
			width:"0.5em",
			display:"inline-block",
			backgroundColor:(props.value&&props.value.length>0)?"black":"transparent",
			borderRadius:"70%",
			verticalAlign:"top",
			marginTop:"0.1882em",				
		};
		const checkImage = $("div",{style:imageStyle,key:"checkImage"},null);
		
		return $(Checkbox,{...props,innerStyle,checkImage,checkBoxStyle,});			
	};	
	const ConnectionState = (props) => {
		const {style,iconStyle,on} = props;
		const fillColor = style&&style.color?style.color:"black";
		const contStyle={
			borderRadius:"1em",
			border:`${GlobalStyles.borderWidth} ${GlobalStyles.borderStyle} ${fillColor}`,
			backgroundColor:on?"green":"red",		
			display:'inline-block',
			width:"1em",
			height:"1em",
			padding:"0.2em",
			boxSizing:"border-box",
			verticalAlign:"top",
			marginLeft:"0.2em",
			marginRight:"0.2em",
			alignSelf:"center",
			...style
		};
		const newIconStyle={
			//position:'relative',
			//top:'-0.05em',
			//left:'-0.025em',
			verticalAlign:"top",
			//width:"0.5em",
			//lineHeight:"1",			
			...iconStyle
		};			
			
		const imageSvg='<svg version="1.1" xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" x="0px" y="0px" viewBox="0 0 285.269 285.269" style="enable-background:new 0 0 285.269 285.269;" xml:space="preserve"> <path style="fill:'+fillColor+';" d="M272.867,198.634h-38.246c-0.333,0-0.659,0.083-0.986,0.108c-1.298-5.808-6.486-10.108-12.679-10.108 h-68.369c-7.168,0-13.318,5.589-13.318,12.757v19.243H61.553C44.154,220.634,30,206.66,30,189.262 c0-17.398,14.154-31.464,31.545-31.464l130.218,0.112c33.941,0,61.554-27.697,61.554-61.637s-27.613-61.638-61.554-61.638h-44.494 V14.67c0-7.168-5.483-13.035-12.651-13.035h-68.37c-6.193,0-11.381,4.3-12.679,10.108c-0.326-0.025-0.653-0.108-0.985-0.108H14.336 c-7.168,0-13.067,5.982-13.067,13.15v48.978c0,7.168,5.899,12.872,13.067,12.872h38.247c0.333,0,0.659-0.083,0.985-0.107 c1.298,5.808,6.486,10.107,12.679,10.107h68.37c7.168,0,12.651-5.589,12.651-12.757V64.634h44.494 c17.398,0,31.554,14.262,31.554,31.661c0,17.398-14.155,31.606-31.546,31.606l-130.218-0.04C27.612,127.862,0,155.308,0,189.248 s27.612,61.386,61.553,61.386h77.716v19.965c0,7.168,6.15,13.035,13.318,13.035h68.369c6.193,0,11.381-4.3,12.679-10.108 c0.327,0.025,0.653,0.108,0.986,0.108h38.246c7.168,0,12.401-5.982,12.401-13.15v-48.977 C285.269,204.338,280.035,198.634,272.867,198.634z M43.269,71.634h-24v-15h24V71.634z M43.269,41.634h-24v-15h24V41.634z M267.269,258.634h-24v-15h24V258.634z M267.269,228.634h-24v-15h24V228.634z"/></svg>';
		const imageSvgData = svgSrc(imageSvg);		
		const src = props.imageSvgData || imageSvgData
		return $("div",{style:contStyle,onClick:props.onClick},
				$("img",{key:"1",style:newIconStyle,src},null)				
		);
	}
	class FileUploadElement extends StatefulComponent{		
		getInitialState(){ return {value:"",reading:false}}		
		onClick(e){
			if(this.fInp)
				this.fInp.click();
		}
		onChange(e){
			if(this.state.reading) return;
			const reader= miscUtil.fileReader()
			const file = e.target.files[0];
			reader.onload=(event)=>{				
				if(this.props.onReadySendBlob){
					const blob = event.target.result;
					this.props.onReadySendBlob(this.fInp.value,blob);
				}				
				this.setState({reading:false});
			}
			reader.onprogress=()=>this.setState({reading:true});
			reader.onerror=()=>this.setState({reading:false});
			
			reader.readAsArrayBuffer(file);
			
		}				
		render(){			
			const style={				
				backgroundColor:(this.props.onReadySendBlob&&!this.state.reading)?"white":"#eeeeee",
				...this.props.style
			};			
			const buttonImageStyle={				
				verticalAlign:"middle",
				display:"inline",
				height:"auto",				
				boxSizing:"border-box"
			};
			const svg ='<svg version="1.1" xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" x="0px" y="0px" width="16px" height="16px" viewBox="0 0 510 510" style="enable-background:new 0 0 510 510;" xml:space="preserve"><path d="M204,51H51C22.95,51,0,73.95,0,102v306c0,28.05,22.95,51,51,51h408c28.05,0,51-22.95,51-51V153c0-28.05-22.95-51-51-51 H255L204,51z"/></svg>';
			const svgData=svgSrc(svg);
			const urlData = this.props.url?this.props.url:svgData;
			const buttonImage = $("img",{key:"buttonImg",src:urlData,style:buttonImageStyle},null);
			const placeholder = this.props.placeholder?this.props.placeholder:"";
			const shadowElement = () => [$("input",{key:"0",ref:(ref)=>this.fInp=ref,onChange:this.onChange,type:"file",style:{visibility:"hidden",position:"absolute",height:"1px",width:"1px"}},null)];
			const buttonElement = () => [$(ButtonInputElement,{key:"2",onClick:this.onClick},buttonImage)];
			
			return $(InputElement,{...this.props,style,shadowElement,buttonElement,onChange:()=>{},onClick:()=>{}});
		}
	}
	
	const ChangePassword = prop => {
        const [attributesA,attributesB] = pairOfInputAttributes(prop,{"X-r-auth":"change"})
		const defButtonStyle = {alignSelf:"flex-end",marginBottom:"0.524em"}
		const disabledButtonStyle = {backgroundColor:"lightGrey",...defButtonStyle}
		const buttonStyle = attributesA.value && attributesA.value === attributesB.value?{backgroundColor:"#c0ced8",...defButtonStyle}:disabledButtonStyle
		const buttonOverStyle = attributesA.value && attributesA.value === attributesB.value?{backgroundColor:"#d4e2ec",...defButtonStyle}:disabledButtonStyle		
		const onClick = attributesA.value && attributesA.value === attributesB.value? prop.onBlur:()=>{}
        const passwordCaption = prop.passwordCaption?prop.passwordCaption:"New Password";
		const passwordRepeatCaption = prop.passwordRepeatCaption?prop.passwordRepeatCaption:"Again";
		const buttonCaption = prop.buttonCaption?prop.buttonCaption:"Submit";
        return $("form",{onSubmit:(e)=>e.preventDefault()},
			$("div",{key:"1",style:{display:"flex"}},[
				$(ControlWrapperElement,{key:"1",style:{flex:"1 1 0%"}},
					$(LabelElement,{label:passwordCaption},null),
					$(InputElement,{...attributesA,focus:prop.focus,type:"password"},null)			
				),
				$(ControlWrapperElement,{key:"2",style:{flex:"1 1 0%"}},
					$(LabelElement,{label:passwordRepeatCaption},null),
					$(InputElement,{...attributesB,focus:false,type:"password"},null)			
				),            
				$(ButtonElement, {key:"3",onClick, style:buttonStyle,overStyle:buttonOverStyle}, buttonCaption)
			])
		)
    }
    const SignIn = prop => {
        const [attributesA,attributesB] = pairOfInputAttributes(prop,{"X-r-auth":"check"})
		const buttonStyle = {backgroundColor:"#c0ced8",...prop.buttonStyle}
		const buttonOverStyle = {backgroundColor:"#d4e2ec",...prop.buttonOverStyle}
		const usernameCaption = prop.usernameCaption?prop.usernameCaption:"Username";
		const passwordCaption = prop.passwordCaption?prop.passwordCaption:"Password";
		const buttonCaption = prop.buttonCaption?prop.buttonCaption:"LOGIN";
		const styleA = {
			...attributesA.style
			//textTransform:"uppercase"
		}
		const styleB = {
			...attributesB.style,
			textTransform:"none"
		}
        const vkOnly = prop.vkOnly
		const dataType = "extText"
        return $("div",{style:{margin:"1em 0em",...prop.style}},[
			$(ControlWrapperElement,{key:"1"},
				$(LabelElement,{label:usernameCaption},null),
				$(InputElement,{...attributesA,vkOnly,style:styleA,focus:prop.focus,dataType},null)			
			),
			$(ControlWrapperElement,{key:"2"},
				$(LabelElement,{label:passwordCaption},null),
				$(InputElement,{...attributesB,vkOnly,style:styleB,onKeyDown:()=>false,focus:false,type:"password",autocomplete:"new-password",dataType, mButtonEnter:"login"},null)
       ),
			$("div",{key:"3",style:{textAlign:"right",paddingRight:"0.3125em"}},
				$(ButtonElement,{onClick:prop.onBlur,style:buttonStyle,overStyle:buttonOverStyle,className:"marker-login"},buttonCaption)
			)
		])		
	}	
	
	const CalenderCell = (props) => $(Interactive,{},(actions)=>{		
		const onClick = () =>{
			const monthAdj = props.m=="p"?"-1":props.m=="n"?"1":"0"
			const value = "month:"+monthAdj+";day:"+props.curday.toString();
			if(props.onClickValue) props.onClickValue("change",value);
		}
		const isSel = props.curday == props.curSel;
		const style ={
			width:"12.46201429%",
			margin:"0 0 0 2.12765%",											
			textAlign:"center",
			border:`${GlobalStyles.borderWidth} ${GlobalStyles.borderStyle}`,
			borderColor:!props.m?"#d4e2ec":"transparent",
			backgroundColor:isSel?"transparent":(actions.mouseOver?"#c0ced8":"transparent")				
		};
		const cellStyle={
			cursor:"pointer",
			padding:"0.3125em 0",
			backgroundColor:isSel?"#ff3d00":"transparent"
		};
		const aCellStyle={
			color:isSel?"white":"#212121",											
			textAlign:"center",
			textDecoration:"none",				
		};		
		const {onMouseOver,onMouseOut} = actions
		return $("div",{onClick,style,onMouseOver,onMouseOut},
			$("div",{style:cellStyle},
				$("a",{style:aCellStyle},props.curday)
			)
		);
	})
	const CalendarYM = (props) => $(Interactive,{},(actions) => {
		const style={
			width:"12.46201429%",
			margin:"0 0 0 2.12765%",						
			textAlign:"center",
			backgroundColor:actions.mouseOver?"#c0ced8":"transparent",
			color:actions.mouseOver?"#212112":"white",
			...props.style
		};
		const {onMouseOver,onMouseOut} = actions
		return $("div",{onClick:props.onClick,style,onMouseOver,onMouseOut},props.children);		
	})
	const CalenderSetNow = (props) => $(Interactive,{},(actions)=>{
		const style={
			border:`${GlobalStyles.borderWidth} ${GlobalStyles.borderStyle} #d4e2ec`,
			color:"#212121",
			cursor:"pointer",
			display:"inline-block",
			padding:".3125em 2.3125em",
			backgroundColor:actions.mouseOver?"#c0ced8":"transparent",
			...props.style
		};
		const {onMouseOver,onMouseOut} = actions
		return $("div",{style,onClick:props.onClick,onMouseOver,onMouseOut},props.children);
	})
	const CalenderTimeButton = (props) => $(Interactive,{},(actions)=>{
		const style = {
			backgroundColor:actions.mouseOver?"#c0ced8":"transparent",
			border:`${GlobalStyles.borderWidth} ${GlobalStyles.borderStyle} #d4e2ec`,
			cursor:"pointer",
			padding:"0.25em 0",
			width:"2em",
			fontSize:"1em",
			outline:"none",
			...props.style
		};
		const {onMouseOver,onMouseOut} = actions
		return $("button",{style,onClick:props.onClick,onMouseOver,onMouseOut},props.children);
	})
	const DateTimePickerYMSel = ({month,year,onClickValue,monthNames}) => {
		const defaultMonthNames=["January","February","March","April","May","June","July","August","September","October","November","December"];
		const aMonthNames = monthNames&&monthNames.length==12?monthNames:defaultMonthNames;
		const headerStyle={
			width:"100%",
			backgroundColor:"#005a7a",
			color:"white",
			margin:"0px",
			display:"flex"
		};
		const aItem = function(c,s){
			const aStyle={
				cursor:"pointer",
				display:"block",
				textDecoration:"none",						
				padding:"0.3125em",
				...s
			};
			return $("a",{style:aStyle},c);
		};				
		const ymStyle = {
			width:"41.64134286%",
			whiteSpace:"nowrap"
		};
		const changeYear = (adj)=>()=>{
			if(onClickValue) onClickValue("change","year:"+adj.toString())
		}
		const changeMonth = (adj)=>()=>{
			if(onClickValue) onClickValue("change","month:"+adj.toString())
		}
		
		const selMonth  = parseInt(month)?parseInt(month):0;
		return $("div",{style:headerStyle},[
			$(CalendarYM,{onClick:changeYear(-1),key:"1",style:{margin:"0px"}},aItem("-")),
			$(CalendarYM,{onClick:changeMonth(-1),key:"2"},aItem("〈")),
			$(CalendarYM,{key:"3",style:ymStyle},aItem(aMonthNames[selMonth]+" "+year,{padding:"0.325em 0 0.325em 0",cursor:"default"})),
			$(CalendarYM,{onClick:changeMonth(1),key:"4"},aItem("〉")),
			$(CalendarYM,{onClick:changeYear(1),key:"5"},aItem("+"))					
		]);
		
	}
	const DateTimePickerDaySel = ({month,year,curSel,onClickValue,dayNames}) => {
		const defaultDayNames  = ["Mon","Tue","Wed","Thu","Fri","Sat","Sun"];
		const aDayNames = dayNames&&dayNames.length>0?dayNames:defaultDayNames;
		const weekDaysStyle={
			margin:"0 0 .3125em",
			width:"100%",
			display:"flex",
			fontSize:"0.75em"
		};
		const dayOfWeekStyle={
			width:"10.5%",
			margin:"0 0 0 2.12765%",
			padding:"0.3125em 0 0.3125em 0",					
			textAlign:"center"
		};
		const dayStyle={
			color:"#212121"
		};
		const cal_makeDaysArr=function(month, year) {
			function cal_daysInMonth(month, year) {
				return 32 - new Date(year, month, 32).getDate();
			}
			const daysArray = [];
			let dayOfWeek = new Date(year, month, 1).getDay();
			const prevMDays = cal_daysInMonth(month ? month-1 : 11, year);
			const currMDays = cal_daysInMonth(month, year);
			
			if (!dayOfWeek) dayOfWeek = 7;	// First week is from previous month
			
			for(let i = 1; i < dayOfWeek; i++)
				daysArray.push(prevMDays - dayOfWeek + i + 1);

			for(let i = 1; i <= currMDays; i++)
				daysArray.push(i);

			for(let i = 1; i <= 42-dayOfWeek-currMDays+1; i++)
				daysArray.push(i);	
				
			return daysArray;
		}
		const rowsOfDays = (dayArray,cDay) => {
			const weeknum = dayArray.length/7;
			let daynum  = 0;
			const cal = cDay;						
			const rows=[];
			let outsideMonth = true
			let insideMonth = false
			let w;
			for(w = 0;w < weeknum;w++){
				rows.push($("tr",{key:""+w},
				(()=>{
					let weekNumber;										
					const curday = dayArray[daynum];
					if(curday <=7 && outsideMonth) {
						outsideMonth = false
						insideMonth = true
					}
					else					
					if(curday <=7 && insideMonth) 
						outsideMonth = true
					if(outsideMonth && !insideMonth){						
						weekNumber = new Date(cal.year, cal.month - 1, curday, 0, 0, 0, 0).getISOWeek();						
					}
					else if(outsideMonth && insideMonth){						
						weekNumber = new Date(cal.year, cal.month + 1, curday, 0, 0, 0, 0).getISOWeek();						
					} else {
						weekNumber = new Date(cal.year, cal.month, curday, 0, 0, 0, 0).getISOWeek();						
					}									
					const weekNumStyle={
						borderRight:"0.04em solid #212121",
						padding:"0em 0em 0,3125em",
						width:"10.5%",
						verticalAlign:"top"
					};
					const weekNumCellStyle={							
						textAlign:"center",
						padding:"0.3125em",
						margin:"0 0 0 2.12765%"							
					};
					const calRowStyle={
						padding:"0em 0em .3125em .3125em",
						margin:"0em",
						width:"100%",						
					};
					return [
						$("td",{key:w+"1",style:weekNumStyle},
							$("div",{style:weekNumCellStyle},
								$("div",{},weekNumber)
							)
						),
						$("td",{key:w+"2",style:calRowStyle},
							$("div",{style:{...calRowStyle,padding:"0px",display:"flex"}},
							(()=>{
								const cells=[];									
								for(let d = 0; d < 7; d++) {
									const curday = dayArray[daynum];
									if (daynum < 7 && curday > 20)
										cells.push($(CalenderCell,{key:d,curday,m:"p",onClickValue}));
									else if (daynum > 27 && curday < 20)
										cells.push($(CalenderCell,{key:d,curday,m:"n",onClickValue}));
									else
										cells.push($(CalenderCell,{key:d,curday,curSel,onClickValue}));
									daynum++;
								}
								return cells;
							})())
						)	
					];
				})()	
				));					
			}		
			const tableStyle={
				width:"100%",
				color:"#212121",
				borderSpacing:"0em"
			}
			return $("table",{key:"rowsOfDays",style:tableStyle},
				$("tbody",{},rows)
			);
		};
		return $("div",{},[
			$("div",{key:"daysRow",style:weekDaysStyle},[
				$("div",{key:"1",style:dayOfWeekStyle},$("div",{}," ")),
				aDayNames.map((day,i)=>
					$("div",{key:"d"+i,style:dayOfWeekStyle},
						$("div",{style:dayStyle},day)
					)
				)
			]),
			rowsOfDays(cal_makeDaysArr(month,year),{month:parseInt(month),year:parseInt(year)})
		]);
	}
	const DateTimePickerTSelWrapper = ({children}) => {
		return $("table",{key:"todayTimeSelWrapper",style:{width:"100%"}},
			$("tbody",{},
				$("tr",{},
					$("td",{colSpan:"8"},
						$("div",{style:{textAlign:"center"}},children)
					)
				)
			)
		);
	};
	const DateTimePickerTimeSel = ({hours,mins,onClickValue}) => {		
		const adjHours = hours.length==1?'0'+hours:hours;
		const adjMins = mins.length==1?'0'+mins:mins;
		const tableStyle = {
			width:"100%",
			marginBottom:"1.5em",
			marginTop:"1em",
			borderCollapse:"collapse",
			color:"#212121"
		};
		const changeHour = (adj)=>()=>{
			if(onClickValue) onClickValue("change","hour:"+adj.toString());
		}
		const changeMin = (adj)=>()=>{
			if(onClickValue) onClickValue("change","min:"+adj.toString());
		}
		return $("table",{key:"timeSelect",style:tableStyle},
			$("tbody",{},[
				$("tr",{key:1},[
					$("td",{key:1,style:{textAlign:"right"}},
						$(CalenderTimeButton,{onClick:changeHour(1)},"+")
					),
					$("td",{key:2,style:{textAlign:"center"}}),
					$("td",{key:3,style:{textAlign:"left"}},
						$(CalenderTimeButton,{onClick:changeMin(1)},"+")
					)							
				]),
				$("tr",{key:2},[
					$("td",{key:1,style:{textAlign:"right"}},adjHours),
					$("td",{key:2,style:{textAlign:"center"}},":"),
					$("td",{key:3,style:{textAlign:"left"}},adjMins),
				]),
				$("tr",{key:3},[
					$("td",{key:1,style:{textAlign:"right"}},
						$(CalenderTimeButton,{onClick:changeHour(-1)},"-")
					),
					$("td",{key:2,style:{textAlign:"center"}}),
					$("td",{key:3,style:{textAlign:"left"}},
						$(CalenderTimeButton,{onClick:changeMin(-1)},"-")
					)	
				])
			])
		);				
	};
	const DateTimePickerNowSel = ({onClick,value}) => $(CalenderSetNow,{key:"setNow",onClick:onClick},value);
	class DateTimePicker extends StatefulComponent{
		setSelection(obj, stpos, endpos){
			if (obj.createTextRange) { // IE
				const rng = obj.createTextRange();
				rng.moveStart('character', stpos);
				rng.moveEnd('character', endpos - obj.value.length);				
				rng.select();
			}
			else if (obj.setSelectionRange) { // FF
				obj.setSelectionRange(stpos, endpos);
			}
		}
		getParts(value,selectionStart,selectionEnd){
			const arr = value.split(/[-\s:]/)			
			const dat = [];
			arr.forEach(v=>{				
				const start = value.indexOf(v,dat[dat.length-1]?dat[dat.length-1].end:0)
				const end = start + v.length
				const selected = selectionStart>=start&&selectionEnd<=end?true:false
				dat.push({start,end,selected})
			})
			return dat;
		}
		getCursorPos(){
			return {ss:this.ss,se:this.se}
		}
		onKeyDown(e){			
			const inp = e.target
			const val = inp.value
			const dat = this.getParts(val,inp.selectionStart,inp.selectionEnd)
			const selD = dat.find(d=>d.selected==true)
			let func = ""
			const funcMap = ["day","month","year","hour","min"];			
			switch(e.keyCode){
				case 38:	//arrow up					
					this.setSelection(inp,selD.start,selD.end)
					e.preventDefault()
					e.stopPropagation()
					func = funcMap[dat.indexOf(selD)]
					this.ss = inp.selectionStart
					this.se = inp.selectionEnd
					this.sendToServer(func,1)
					//log(`send: ${func}:1`)					
					return	false			
				case 40:	//arrow down
					//log("send dec")
					this.setSelection(inp,selD.start,selD.end)
					e.preventDefault()
					e.stopPropagation()
					func = funcMap[dat.indexOf(selD)]
					this.ss = inp.selectionStart
					this.se = inp.selectionEnd
					this.sendToServer(func,-1)
					//log(`send: ${func}:-1`)
					return false
				case 27:	//esc
					//log("esc")
					this.setSelection(inp,selD.end,selD.end)
					return false				
				default:
					this.ss = null
					this.se = null
			}
			return false;			
		}
		sendToServer(func,adj){
			if(this.props.onClickValue) this.props.onClickValue("change",func+":"+adj.toString());
		}
		render(){
			const props = this.props
			const calWrapper=function(children){
				const wrapperStyle={
					padding:".3125em",
					backgroundColor:"white",
					minWidth:"18.75em",
					boxShadow:GlobalStyles.boxShadow
				};
				const gridStyle={
					margin:"0px",
					padding:"0px"
				};
				return $("div",{style:wrapperStyle},
					$("div",{style:gridStyle},
						children
					));
			};	
			const popupStyle={
				width:"auto",
				maxHeight:"auto",
				...props.popupStyle
			};			
			const buttonImageStyle={				
				transform:"none",
				...props.buttonImageStyle
			};		
			
			const inputStyle = {textAlign:"right"}
			
			const svg = '<svg version="1.1" xmlns="http://www.w3.org/2000/svg" x="0" y="0" viewBox="0 0 512 512" style="enable-background:new 0 0 512 512;" xml:space="preserve">'
				  +'<path style="fill:#FFFFFF;" d="M481.082,123.718V72.825c0-11.757-9.531-21.287-21.287-21.287H36         c-11.756,0-21.287,9.53-21.287,21.287v50.893L481.082,123.718L481.082,123.718z"/>'
				  +'<g><path d="M481.082,138.431H14.713C6.587,138.431,0,131.843,0,123.718V72.825c0-19.85,16.151-36,36-36h423.793   c19.851,0,36,16.151,36,36v50.894C495.795,131.844,489.208,138.431,481.082,138.431z M29.426,109.005h436.942v-36.18   c0-3.625-2.949-6.574-6.574-6.574H36c-3.625,0-6.574,2.949-6.574,6.574V109.005z"/>'
				  +'<path d="M144.238,282.415H74.93c-8.126,0-14.713-6.589-14.713-14.713v-61.765   c0-8.125,6.587-14.713,14.713-14.713h69.308c8.126,0,14.713,6.589,14.713,14.713c0,8.125-6.587,14.713-14.713,14.713H89.643v32.338   h54.595c8.126,0,14.713,6.589,14.713,14.713S152.364,282.415,144.238,282.415z"/></g>'
				  +'<g><path d="M282.552,282.415h-69.308c-8.126,0-14.713-6.589-14.713-14.713v-61.765   c0-8.125,6.587-14.713,14.713-14.713h69.308c8.126,0,14.713,6.589,14.713,14.713v61.765   C297.265,275.826,290.678,282.415,282.552,282.415z M227.957,252.988h39.882V220.65h-39.882V252.988z"/>'
				  +'<path d="M144.238,406.06H74.93c-8.126,0-14.713-6.589-14.713-14.713v-61.765   c0-8.125,6.587-14.713,14.713-14.713h69.308c8.126,0,14.713,6.589,14.713,14.713s-6.587,14.713-14.713,14.713H89.643v32.338h54.595   c8.126,0,14.713,6.589,14.713,14.713S152.364,406.06,144.238,406.06z"/></g>'
				  +'<path d="M282.552,406.06h-69.308c-8.126,0-14.713-6.589-14.713-14.713v-61.765  c0-8.125,6.587-14.713,14.713-14.713h69.308c8.126,0,14.713,6.589,14.713,14.713v61.765  C297.265,399.471,290.678,406.06,282.552,406.06z M227.957,376.633h39.882v-32.338h-39.882V376.633z"/>'
				  +'<g><path d="M420.864,282.415h-69.308c-8.126,0-14.713-6.589-14.713-14.713v-61.765   c0-8.125,6.587-14.713,14.713-14.713h69.308c8.126,0,14.713,6.589,14.713,14.713v61.765   C435.577,275.826,428.99,282.415,420.864,282.415z M366.269,252.988h39.882V220.65h-39.882V252.988L366.269,252.988z"/>'
				  +'<path d="M99.532,92.878c-8.126,0-14.713-6.589-14.713-14.713V22.06c0-8.125,6.587-14.713,14.713-14.713   s14.713,6.589,14.713,14.713v56.106C114.245,86.291,107.658,92.878,99.532,92.878z"/>'
				  +'<path d="M247.897,92.878c-8.126,0-14.713-6.589-14.713-14.713V22.06c0-8.125,6.587-14.713,14.713-14.713   s14.713,6.589,14.713,14.713v56.106C262.61,86.291,256.023,92.878,247.897,92.878z"/>'
				  +'<path d="M396.263,92.878c-8.126,0-14.713-6.589-14.713-14.713V22.06c0-8.125,6.587-14.713,14.713-14.713   s14.713,6.589,14.713,14.713v56.106C410.976,86.291,404.389,92.878,396.263,92.878z"/>'
				  +'<path d="M389.88,504.653c-67.338,0-122.12-54.782-122.12-122.12s54.782-122.12,122.12-122.12   c36.752,0,71.2,16.321,94.512,44.78c5.15,6.285,4.229,15.556-2.058,20.706c-6.285,5.148-15.556,4.229-20.706-2.058   c-17.7-21.608-43.851-33.999-71.747-33.999c-51.111,0-92.693,41.582-92.693,92.693s41.582,92.693,92.693,92.693   s92.693-41.582,92.693-92.693c0-8.125,6.587-14.713,14.713-14.713c8.126,0,14.713,6.589,14.713,14.713   C512,449.87,457.218,504.653,389.88,504.653z"/>'
				  +'<path d="M228.475,490.606H36c-19.85,0-36-16.151-36-36V72.825c0-19.85,16.151-36,36-36h423.793   c19.851,0,36,16.151,36,36v164.701c0,8.125-6.587,14.713-14.713,14.713c-8.126,0-14.713-6.589-14.713-14.713V72.825   c0-3.625-2.949-6.574-6.574-6.574H36c-3.625,0-6.574,2.949-6.574,6.574v381.781c0,3.625,2.949,6.574,6.574,6.574h192.474   c8.126,0,14.713,6.589,14.713,14.713C243.187,484.018,236.601,490.606,228.475,490.606z"/></g>'
				  +'<polyline style="fill:#FFFFFF;" points="429.606,382.533 389.88,382.533 389.88,342.808 "/>'
				  +'<path d="M429.606,397.247H389.88c-8.126,0-14.713-6.589-14.713-14.713v-39.726  c0-8.125,6.587-14.713,14.713-14.713s14.713,6.589,14.713,14.713v25.012h25.012c8.126,0,14.713,6.589,14.713,14.713  S437.732,397.247,429.606,397.247z"/>'
				  +'</svg>';
			const svgData=svgSrc(svg);	  
			const urlData = props.url?props.url:svgData;
			const noAutoWidth = true
			return $(DropDownElement,{...props,cursorPos:this.getCursorPos,noAutoWidth,inputStyle,popupStyle,onKeyDown:this.onKeyDown,buttonImageStyle,url:urlData,children:calWrapper(props.children)});			
		}
	}	
	Date.prototype.getISOWeek = function(utc){
		var y = utc ? this.getUTCFullYear(): this.getFullYear();
		var m = utc ? this.getUTCMonth() + 1: this.getMonth() + 1;
		var d = utc ? this.getUTCDate() : this.getDate();
		var w;
		// If month jan. or feb.
		if (m < 3) {
		  var a = y - 1;
		  var b = (a / 4 | 0) - (a / 100 | 0) + (a / 400 | 0);
		  var c = ( (a - 1) / 4 | 0) - ( (a - 1) / 100 | 0) + ( (a - 1) / 400 | 0);
		  var s = b - c;
		  var e = 0;
		  var f = d - 1 + 31 * (m - 1);
		}
		// If month mar. through dec.
		else {
		  var a = y;
		  var b = (a / 4 | 0) - ( a / 100 | 0) + (a / 400 | 0);
		  var c = ( (a - 1) / 4 | 0) - ( (a - 1) / 100 | 0) + ( (a - 1) / 400 | 0);
		  var s = b - c;
		  var e = s + 1;
		  var f = d + ( (153 * (m - 3) + 2) / 5 | 0) + 58 + s;
		}
		var g = (a + b) % 7;
		// ISO Weekday (0 is monday, 1 is tuesday etc.)
		var d = (f + g - e) % 7;
		var n = f + 3 - d;
		if (n < 0)
		  w = 53 - ( (g - s) / 5 | 0);
		else if (n > 364 + s)
		  w = 1;
		else
		  w = (n / 7 | 0) + 1;
		return w;
	}
	const InternalClock = (()=>{
		let timeString = ""
		const callbacks = [];
		let timeout = null
		let time = 0;
		let bgTicks = 5;
		const prefix = (num) =>{if(num.length<2) return `0${num}`; else return `${num}`}
		const formatTime = () => {						
			const date = new Date(time)			
			return `${prefix(date.getUTCDate().toString())}-${prefix((date.getUTCMonth()+1).toString())}-${date.getUTCFullYear().toString()} ${prefix(date.getUTCHours().toString())}:${prefix(date.getUTCMinutes().toString())}:${prefix(date.getUTCSeconds().toString())}`;
		}
		const tick = () => {
			if(time){
				timeString = formatTime();
				time += 1000;
			}
			callbacks.forEach(o=>{
				if(o.updateInterval>=5*60){o.updateServer();o.updateInterval=0}
				o.updateInterval += 1
				o.clockTicks(timeString)
			})			
			timeout = setTimeout(tick,1000)			
			if(callbacks.length == 0 && bgTicks<=0) stop();
			else if(callbacks.length == 0 && bgTicks>0) bgTicks -=1;
		}	
		const get = () => timeString
		const start = ()=>{bgTicks = 5;tick();}
		const update = (updateTime) => {if(updateTime) time = updateTime}
		const stop = ()=>{clearTimeout(timeout); timeout=null}
		const reg = (obj) => {callbacks.push(obj); if(callbacks.length==1 && timeout==null) start(); return ()=>{const index = callbacks.indexOf(obj); if(index>=0) delete callbacks[index];};}
		return {reg,update,get}
	})()
	let dateElementPrevCutBy = 0;
	const OneSvg = ({children})=> $("svg",{},$("text",{style:{dominantBaseline:"hanging"}},children))		
		
	class DateTimeClockElement extends StatefulComponent{		
		getInitialState(){ return {cutBy:0,timeString:""}}			
		clockTicks(timeString){
			this.setState({timeString})
		}
		isOverflow(){
			const childRect = this.shadowEl.getBoundingClientRect();			
			const parentRect = this.contEl.getBoundingClientRect();
			return childRect.width > parentRect.width
		}
		setCutBy(){
			const cutBy = !this.state.cutBy?1:0;
			this.setState({cutBy})
			dateElementPrevCutBy = cutBy
		}
		recalc(){			
			if(!this.contEl || !this.shadowEl) return;
			const isOverflow = this.isOverflow()
			if( isOverflow && this.state.cutBy == 0) this.setCutBy()
			else if(!isOverflow && this.state.cutBy == 1) this.setCutBy()
		}
		updateServer(){
			this.props.onClick()
			//log("call update")
		}
		componentDidMount(){
      		this.resizeL = resizeListener.reg(this.recalc)
			this.recalc()
			const clockTicks = this.clockTicks;
			const updateInterval = 5*60
			const updateServer = this.updateServer
			this.unreg = InternalClock.reg({clockTicks,updateInterval,updateServer})			
		}
		componentWillReceiveProps(nextProps){
			//log("came update")
			InternalClock.update(parseInt(nextProps.time)*1000)
		}
		componentDidUpdate(_, prevState){
			if(prevState.timeString.length == 0 && this.state.timeString.length>0) this.recalc();
		}
		componentWillUnmount(){
			this.resizeL.unreg()
      		this.unreg()
		}
		splitTime(time){
			const dateArr = time.split(' ')
			return dateArr[1]
		}
		render(){
			const fullTime = InternalClock.get()
			let partialTime ="";
			const cutBy  = !this.state.cutBy&&dateElementPrevCutBy!=this.state.cutBy?dateElementPrevCutBy:this.state.cutBy;
			dateElementPrevCutBy = cutBy;
			switch(cutBy){				
				case 1: partialTime = this.splitTime(fullTime);break;
				default:partialTime = this.props.short?this.splitTime(fullTime):fullTime;break;
			}
			
			const style={				
				display:"inline-block",
				//verticalAlign:"middle",
				//alignSelf:"center",
				margin:"0 0.2em",
				minWidth:"0",
				whiteSpace:"nowrap",
				position:"relative",
				flex:"1 1 0%",
				...this.props.style	
			}
			const shadowStyle = {				
				visibility:"hidden"
			}
			const textWStyle = {
				display:"inline-block",
				verticalAlign:"middle"
			}
			const textStyle = {
				position:"absolute",
				right:"0em"					
			}
			//const localDate = new Date(serverTime)
			//const lastChar = partialTime[partialTime.length-1]
			return $("div",{style,ref:ref=>this.contEl=ref},
				$("div",{style:textWStyle},[			
					$("span",{style:shadowStyle,ref:ref=>this.shadowEl=ref,key:"shadow"},fullTime),
					$("span",{style:textStyle,key:"date"},partialTime)
				])
			)
		}
	}
	const AnchorElement = ({style,href}) =>$("a",{style,href},"get")	
	class HeightLimitElement extends StatefulComponent{		
		getInitialState(){ return {max:false}}		
		findUnder(el,rect){			
			const sibling = el.nextSibling
			if(!sibling) {
				const parentEl = el.parentNode
				if(isReactRoot(parentEl)) return false				
				return this.findUnder(parentEl,rect)
			}
			const sRect = sibling.getBoundingClientRect()			
			if(((sRect.left>=rect.left && sRect.left<=rect.right)||(sRect.right<=rect.right&&sRect.right>=rect.left))&&sRect.top>rect.bottom)
				return true
			else
				return this.findUnder(sibling,rect)
		}
		recalc(){
			if(!this.el) return;				
			const found = this.findUnder(this.el,this.rect)
			//log("return :"+found)
			if(this.state.max != !found)
				this.setState({max:!found})				
			
		}
		componentDidMount(){
			this.rect = this.el.getBoundingClientRect();	
			checkActivateCalls.add(this.recalc)			
		}
		componentWillUnmount(){			
			checkActivateCalls.remove(this.recalc)			
		}
		render(){			
			const style = {
				maxHeight:this.state.max?"none":this.props.limit,
				...this.props.style
			}
			//log("state"+this.state.max)
			//log(style)
			return $("div",{style, ref:ref=>this.el=ref},this.props.children)
		}
	}	
	class FocusAnnouncerElement extends StatefulComponent{		
		onFocus(e){
			const focusKey = e.detail?e.detail:""
			this.report(focusKey)
			e.stopPropagation()
		}
		report(focusKey){
			if(this.timeout) {clearTimeout(this.timeout);this.timeout = null}
			this.timeout = setTimeout(()=>{
				if(!this.timeout) return
				if(this.props.onClickValue) this.props.onClickValue("focusChange",focusKey)
			}, 150)			
		}		
		componentWillUnmount(){			
			this.el.removeEventListener("cFocus",this.onFocus)
			this.el.removeEventListener("focus",this.onFocus)			
		}
		componentDidMount(){			
			this.el.addEventListener("cFocus",this.onFocus)
			this.el.addEventListener("focus",this.onFocus)			
		}
		render(){
			return $(Provider,{value:this.props.path},
				$('div',{ref:ref=>this.el=ref,style:{outline:"none"},tabIndex:"1",className:"focusAnnouncer"},this.props.children)
			)
		}
	}
	class DragDropHandlerElement extends StatefulComponent{
		report(action,fromSrcId,toSrcId){			
			if((!Array.isArray(this.props.filterActions)||this.props.filterActions.includes(action))&&this.props.onDragDrop)
				this.props.onDragDrop("reorder",JSON.stringify({action,fromSrcId,toSrcId}))
		}
		componentDidMount(){
			this.dragBinding = dragDropModule.regReporter(this.report)
		}
		componentWillUnmount(){
			if(this.dragBinding) this.dragBinding.release()
		}
		render(){
			return $('span',{className:"dragDropHandler"})
		}
	}
	class ConfirmationOverlayElement extends StatefulComponent{		
		getInitialState(){ return {dims:null}}
		getRootDims(){
			if(!this.root){
				this.root = getReactRoot(this.el)
				this.root = this.root.parentNode?this.root.parentNode:this.root
			}
			const dims = this.root.getBoundingClientRect()
			return dims
		}
		recalc(){
			const rdims = this.getRootDims()
			if(!this.state.dims) return this.setState({dims:rdims})			
			const sdims = this.state.dims
			if(sdims.top != rdims.top || sdims.left!=rdims.left || sdims.bottom!=rdims.bottom||sdims.right!=rdims.right)
				this.setState({dims:rdims})
		}
		componentDidMount(){
			if(this.props.show) checkActivateCalls.add(this.recalc)			
		}
		componentDidUpdate(prevProps){
			if(this.props.show && !prevProps.show) checkActivateCalls.add(this.recalc)				
			if(!this.props.show && prevProps.show) checkActivateCalls.remove(this.recalc)		
		}
		componentWillUnmount(){
			if(this.props.show) checkActivateCalls.remove(this.recalc)
		}
		render(){
			const getSide = (dims,side)=>{
				if(!dims) return "0px"
				return dims[side] + "px"
			}
			const style={
				position:"fixed",
				display:(!this.state.dims||!this.props.show)?"none":"",
				top:getSide(this.state.dims,"top"),
				left:getSide(this.state.dims,"left"),
				width:getSide(this.state.dims,"width"),
				height:getSide(this.state.dims,"height"),
				zIndex:"6666",
				color:"wheat",
				textAlign:"center",
				backgroundColor:"rgba(0,0,0,0.4)"
			};
			return $('div',{style,ref:ref=>this.el=ref,className:"confirmOverlay"},this.props.children)			
		}
	}
	
	class DragDropDivElement extends StatefulComponent{
		componentDidMount(){
			this.dragBinding = dragDropModule.dragReg({node:this.el,dragData:this.props.dragData})
			addEventListener("mouseup",this.onMouseUp)
		}
		componentDidUpdate(){
			this.dragBinding.update({node:this.el,dragData:this.props.dragData})
		}
		componentWillUnmount(){
			this.dragBinding.release()
			removeEventListener("mouseup",this.onMouseUp)
		}
		onMouseDown(e){
			if(!this.props.draggable) return
			this.dragBinding.dragStart(e,this.el,"div",this.props.dragStyle)
		}
		onMouseUp(e){
			const elements = documentManager.elementsFromPoint(e.clientX,e.clientY)
			if(!elements.includes(this.el)) return			
			if(!this.props.droppable) return
			this.dragBinding.dragDrop(this.el)
		}	
		render(){
			const style = {
				...this.props.style
			}
			const actions = {
				onMouseDown:this.onMouseDown,				
				onTouchStart:this.onMouseDown,
				onTouchEnd:this.onMouseUp
			}
			const ref = (ref)=>this.el=ref
			return $("div",{style,ref,...actions},this.props.children)
		}
	}

	class ColorCreator extends StatefulComponent{
		onChange(e){
			if(this.timeout) return
			const value = e.target.value
			this.timeout = setTimeout(()=>{				
				if(this.props.onChange)
					this.props.onChange({target:{headers:{"X-r-action":"change"},value:e.target.value}})
				this.timeout = null
			},500)
			
		}
		onBlur(e){
			if(this.props.onBlur)
				this.props.onBlur(e)
		}
		componentDidMount(){			
			//this.el.addEventListener("input",this.onChange,false)
			//this.drawCanvasImage();//camvas.imcludeImage(...)
		}
		componentWillUnmount(){
			this.timeout = null
			//this.el.removeEventListener("input",this.onChange,false)
		}
		drawCanvasImage(){
			if(this.canvasEl) {
				const canvas = this.canvasEl;
				canvas.width = 296;
				canvas.height = 126;
				const ctx = canvas.getContext('2d');
				const image = new Image();
				image.onload = function() {
					ctx.drawImage(image, 0, 0);
				};
				image.src = "data:image/png;base64,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";
				
				canvas.onclick = function(e) {
					const bcr = canvas.getBoundingClientRect();
					const absX = e.pageX;
					const absY = e.pageY;
					const relX = absX - bcr.left;
					const relY = absY - bcr.top
					var coord = "x=" + relX + ", y=" + relY;
					var p = ctx.getImageData(relX, relY, 1, 1).data;
					//var hex = "#" + ("000000" + rgbToHex(p[0], p[1], p[2])).slice(-6);
					log(coord, p);
					//this.props.onChange()
				}
			}
		}
		render(){
			const contStyle={
				width:"100%",
				boxSizing:"border-box",
				position:"relative",
			};
			const popupStyle={
				position:"absolute",
				border: "0.02rem solid #ccc",
				width: '296px',
				height: '126px',
				overflow: "hidden",				
				zIndex: "5",
				top:'100%',
			};
			const inputStyle={
				width:'10em',
				border:"none",
				...this.props.style
			};
			const canvasStyle ={
				width:'100%',
				height:'100%',
			}    			
			//return React.createElement('input',{key:"1",style:inputStyle,onChange:this.onChange,onBlur:this.onBlur,value:this.props.value},null);
			return $("input",{type:"color",key:1,style:inputStyle,onChange:this.onChange, onInput:this.onChange,value:this.props.value, ref:ref=>this.el=ref})
		}
	}
	class ColorPicker extends StatefulComponent{		
		getInitialState(){return {left:null,top:null}}		
		recalc(){
			if(!this.el) return
			const rect = this.el.getBoundingClientRect()
			const newTop = rect.bottom+getPageYOffset()
			if(Math.round(this.state.left)!=Math.round(rect.left)||Math.round(this.state.top)!=Math.round(newTop))
				this.setState({left:rect.left,top:newTop})
		}
		componentDidMount(){
			this.recalc()
		}
		componentDidUpdate(){
			this.recalc()
		}
		render(){
			const {onChange,style,children,isOpen, value} = this.props
			return React.createElement('div',{
				className:"colorPicker",
				style: {
						width:"100%",
						boxSizing:"border-box",
						//position:"relative",
					}},[
						React.createElement('div', {
								key:'1',
								ref:ref=>this.el=ref,
								style: {
									minWidth:'6em',
									height:'2em',
									textShadow:'0.125em 0.125em 0.24em rgba(0, 0, 0, 0.4)',
									color:'white',
									padding:'0rem 0rem 0rem 0.2rem',
									lineHeight:'2em',
									verticalAlign:'middle',
									...style
								},
								onClick: ev => onChange({target:{headers:{"X-r-action":"change"},value:""} })
						}, value),
						isOpen?React.createElement('div', {
								key:'2',
								style: {
									position:"absolute",
									display:'flex',
									flexWrap:'wrap',
									maxWidth:'25em',
									left:this.state.left?this.state.left+"px":"",
									top:this.state.top?this.state.top+"px":"",
									//top:'100%',							
									border:'0.06em solid grey',
									zIndex: "669",
									justifyContent:"space-around"
								}
						},children):null
					]
			)
		}
	}
	const beepMidi = `data:audio/mpeg;base64,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
AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
AAAAAAAAAAAAAP/7ksDVgAAAASwAAAAAAAAlgAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAD/+5LA/4AAAAEsAAAAAAAAJYAAAAAAAAAAAAAA
AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA//uSwP+AAAAB
LAAAAAAAACWAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
AAAAAAAAAAAAAP/7ksD/gAAAASwAAAAAAAAlgAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAD/+5LA/4AAAAEsAAAAAAAAJYAAAAAAAAAAAAAA
AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA//uSwP+AAAAB
LAAAAAAAACWAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
AAAAAAAAAAAAAP/7ksD/gAAAASwAAAAAAAAlgAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAD/+5LA/4AAAAEsAAAAAAAAJYAAAAAAAAAAAAAA
AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAIAAgACAAIAAg
ACAAIAAgACAAIAAgACAAIAAgACAAIAAgACAAIAAgACAAIAAgACAAIAAgACAAIAAgACAAIAAgACAA
IAAgACAAIAAgACAAIAAgACAAIAAgACAAIAAgACAAIAAgACAAIAAgACAAIAAgACAA//uSwP+AAAAB
LAAAAAAAACWAAAAAIAAgACAAIAAgACAAIAAgACAAIAAgACAAIAAgACAAIAAgACAAIAAgACAAIAAg
ACAAIAAgACAAIAAgACAAIAAgACAAIAAgACAAIAAgACAAIAAgACAAIAAgACAAIAAgACAAIAAgACAA
IAAgACAAIAAgACAAIAAgACAAIAAgACAAIAAgACAAIAAgACAAIAAgACAAIAAgACAAIAAgACAAIAAg
ACAAIAAgACAAIAAgACAAIAAgACAAIAAgACAAIAAgACAAIAAgACAAIAAgACAAIAAgACAAIAAgACAA
IAAgACAAIAAgACAAIAAgACAAIAAgACAAIAAgACAAIAAgACAAIAAgACAAIAAgACAAIAAgACAAIAAg
ACAAIAAgACAAIAAgACAAIAAgACAAIAAgACAAIAAgACAAIAAgACAAIAAgACAAIAAgACAAIAAgACAA
IAAgACAAIAAgACAAIAAgACAAIAAgACAAIAAgACAAIAAgACAAIAAgACAAIAAgACAAIAAgACAAIAAg
ACAAIAAgACAAAA==`
	const ColorItem = ({value,onChange,style}) => React.createElement('div',{
		style: {
			width:'6em',
			height:'2em',
			textShadow:'2px 2px 4px rgba(0, 0, 0, 0.4)',
			color:'white',
			padding:'0rem 0rem 0rem 0.2rem',
			lineHeight:'2em',
			verticalAlign:'middle',
			backgroundColor: value ? value : 'black',
			flex:"1 0 6em",
			overflow:"hidden",
			whiteSpace:"nowrap",
			textOverflow:"ellipsis",
			...style
		},
		onClick: ev => onChange({target:{headers:{"X-r-action":"change"},value} })
	},React.createElement('span',{}, value))	
	
	class SoundProducerElement extends StatefulComponent{
		produce(){
			const audio = miscUtil.audio
			if(!audio) return
			try{
				this.audio = audio(beepMidi)
				this.audio.play()
			}
			catch(e){log(e)}
		}
		/*
		produce(){
			const audioContext = miscUtil.audioContext

			if(!audioContext) return
			if(this.audioContext) return
			this.audioCtx = audioContext()
			this.oscillator = this.audioCtx.createOscillator()
			this.oscillator.type = this.props.type||'square'
			const freq = (this.props.freq?parseInt(this.props.freq):null)||440
			this.oscillator.frequency.setValueAtTime(freq, this.audioCtx.currentTime)
			this.oscillator.connect(this.audioCtx.destination)
			this.oscillator.start()
			const period = (this.props.period?parseInt(this.props.period):null)||300
			setTimeout(()=>{
				this.stop()
			},period)
		}
		stop(){
			this.oscillator&&this.oscillator.stop()
			this.audioCtx&&this.audioCtx.close().then(()=>this.audioCtx = null) 
		}*/
		componentDidUpdate(){
			this.audio&&this.stop()
			this.produce()
		}
		stop(){
			this.audio&&this.audio.stop()
			this.audio = null
		}
		componentDidMount(){
			this.produce()
		}
		componentWillUnmount(){
			this.stop()
		}
		render(){return null}
	}	
	class InteractiveAreaElement extends StatefulComponent{					    			
		check(){
			const wHeight = windowManager.getWindowRect().height			
			const rect =  this.el.getBoundingClientRect()
			let height = wHeight - rect.top
			height=(height<0?0:height)*0.95
			let width = (rect.width<0?0:rect.width)*0.95
			if((this.pHeight!=height||this.pWidth!=width) && this.props.onWResize) {
				this.pHeight = height
				this.pWidth = width
				const pxEm = this.remRef.getBoundingClientRect().height
				this.props.onWResize("change",`${width},${height},${pxEm}`)
			}			
		}
		componentDidMount(){		
			if(!this.props.onWResize) return
			this.check()
			this.resizeL = resizeListener.reg(this.check)
		}
		componentDidUpdate(prevProps,prevState){
			this.check()			
		}
		componentWillUnmount(){
			if(this.resizeL) this.resizeL.unreg()
		}
		render(){
			const filterActions = ["dragDrop"]
			const scaleStyle = {				
				fontSize:this.state.scale+"em"
			}
			return $("div",{style:{display:"inline-block"},ref:ref=>this.el=ref},[
				$("div",{key:"remref",style:{position:"absolute",zIndex:"-1",height:"1em"},ref:ref=>this.remRef=ref}),
				$(DragDropHandlerElement,{key:"dhandler",onDragDrop:this.props.onDragDrop,filterActions}),
				$("div",{key:"children"},this.props.children)
			])		
		}
	}
	class ZoomOnPopupElement extends StatefulComponent{		
		getInitialState(){
			return {width:0,height:0}
		}
		onMouseDown(e){						
			if(!this.el) return
			/*if(!this.props.zoomed){
				const elements = documentManager.elementsFromPoint(e.clientX,e.clientY)
				el = elements.find(e=>e==this.el)
			}*/
			if(e.target==this.el||e.target == this.el.firstElementChild){
				this.props.onClickValue && this.props.onClickValue("change",this.maxZoomK().toString())
				e.stopPropagation()
			}			
		}
		resize(){
			const rect = this.el.getBoundingClientRect()
			if(this.state.width!= rect.width || this.state.height !=rect.height)
				this.setState({width:rect.width,height:rect.height})
		}
		componentDidMount(){
			addEventListener("mousedown",this.onMouseDown,true)
			
		}
		maxZoomK(){
			if(this.props.zoomed) return 1
			let child
			if(child = this.el.firstElementChild){				
				const rect = child.getBoundingClientRect()
				const wRect= windowManager.getWindowRect()
				if(rect.height<wRect.height && rect.width<wRect.width){
					return ((wRect.width-rect.width>wRect.height-rect.height)?wRect.height/rect.height:wRect.width/rect.width)*0.9					
				}			
			}
			return 1
		}
		componentWillUnmount(){
			removeEventListener("mousedown",this.onMouseDown)
			
		}
		render(){
			const zoomedStyle = this.props.zoomed?{position:"fixed",top:"0px",left:"0px",display:"flex",justifyContent:"center",height:"100%",width:"100%",cursor:"zoom-out"}:{position:"relative"}			
			const style = {				
				...zoomedStyle,
				...this.props.style				
			}
			const nonZoomed = !this.props.zoomed?{position:"absolute", zIndex:"9998",width:"100%",height:"100%",cursor:"zoom-in"}:{}
			const className = "ZoomPopup"
			return $("div",{className,ref:ref=>this.el=ref, style},[			
				$("div",{key:1,style:nonZoomed,onMouseDown:this.MouseDown}),
				$("div",{key:2,style:{alignSelf:"center"}},this.props.children)
			])
		}
	}

	class BatteryState extends StatefulComponent{		
		getInitialState(){return {batteryLevel:0,isCharging:false}}
		onLevelChange(){
			if(!this.battery) return;
			this.setState({batteryLevel:this.battery.level});
		}
		onChargingChange(){
			if(!this.battery) return;
			this.setState({isCharging:this.battery.charging});
		}
		onBatteryInit(battery){
			this.battery = battery;
			this.battery.addEventListener("chargingchange",this.onChargingChange);
			this.battery.addEventListener("levelchange",this.onLevelChange);
			this.setState({batteryLevel:this.battery.level,isCharging:this.battery.charging});
		}
		componentDidMount(){			
			miscUtil.getBattery&&miscUtil.getBattery(this.onBatteryInit);
		}		
		componentWillUnmount(){
			if(!this.battery) return;
			this.battery.removeEventListener("charginchange",this.onChargingChange);
			this.battery.removeEventListener("levelchange",this.onLevelChange);
		}
		render(){
			const style={
				display:"flex",				
				marginLeft:"0.2em",
				marginRight:"0.2em",
				alignSelf:"center",
				...this.props.style
			};
			const svgStyle = {				
				height:"1em"				
			};
			const textStyle={
				fontSize:"0.5em",
				alignSelf:"center"
				//verticalAlign:"middle",
			};
			const svgImgStyle = {
				enableBackground:"new 0 0 60 60",
				verticalAlign:"top",
				height:"100%"
			}
			
			const statusColor = this.state.batteryLevel>0.2?"green":"red";
			const batteryLevel = Math.round(this.state.batteryLevel*100);
			const el=React.createElement("div",{style},[
					React.createElement("span",{key:"2",style:textStyle},batteryLevel + "%"),
					React.createElement("div",{key:"1",style:svgStyle},
						React.createElement("svg",{
							key:"1",
							xmlns:"http://www.w3.org/2000/svg", 
							xmlnsXlink:"http://www.w3.org/1999/xlink",
							version:"1.1",
							x:"0px",
							y:"0px",
							viewBox:"0 0 60 60",
							style:svgImgStyle,
							xmlSpace:"preserve"},[
								React.createElement("path",{key:"1",fill:"white",stroke:"white",d:"M42.536,4 H36V0H24 v4h-6.536 C15.554,4,14,5.554,14,7.464 v49.07 2C14,58.446,15.554,60,17.464,60 h25.071   C44.446,60,46,58.446,46,56.536 V7.464 C46,5.554,44.446,4,42.536,4z M44,56.536 C44,57.344,43.343,58,42.536,58 H17.464   C16.657,58,16,57.344,16,56.536V7.464C16,6.656,16.657,6,17.464,6H24h12h6.536C43.343,6,44,6.656,44,7.464V56.536z"},null),
								React.createElement("rect",{
									key:"_2",
									fill:"white",
									x:"15.4",
									y:5.2 +"",
									width:"28.8",
									height:(52.6 - this.state.batteryLevel*52.6)+""
								},null),
								React.createElement("rect",{
									key:"_1",
									fill:statusColor,
									x:"15.4",
									y:5.2 + (52.6 - this.state.batteryLevel*52.6)+"",
									width:"28.8",
									height:(this.state.batteryLevel*52.6)+""
								},null),
								React.createElement("path",{key:"2",fill:(this.state.isCharging?"rgb(33, 150, 243)":"transparent"),d:"M37,29h-3V17.108c0.013-0.26-0.069-0.515-0.236-0.72c-0.381-0.467-1.264-0.463-1.642,0.004   c-0.026,0.032-0.05,0.066-0.072,0.103L22.15,32.474c-0.191,0.309-0.2,0.696-0.023,1.013C22.303,33.804,22.637,34,23,34h4   l0.002,12.929h0.001c0.001,0.235,0.077,0.479,0.215,0.657C27.407,47.833,27.747,48,28.058,48c0.305,0,0.636-0.16,0.825-0.398   c0.04-0.05,0.074-0.103,0.104-0.159l8.899-16.979c0.163-0.31,0.151-0.682-0.03-0.981S37.35,29,37,29z"},null),
							]
						)
					)					
				]);
			return miscUtil.getBattery?el:null;
		}
	}
	const PingReceiver = function(){
		let pingerTimeout=null;
		let callbacks=[];
		function ping(data){			
			if(pingerTimeout){clearTimeout(pingerTimeout); pingerTimeout = null;}
			if(!callbacks.length) return;
			pingerTimeout=setTimeout(function(){callbacks.forEach((o)=>o(false,null));},2500);
			callbacks.forEach((o)=>o(true,null));
		};		
		function reg(o){
			callbacks.push(o)
			log(`reg`)
			const unreg = function(){
				const index = callbacks.indexOf(o)
				if(index>=0) callbacks.splice(index,1)				
				log(`unreg`)
			}
			return {unreg}
		}		
		return {ping,reg};
	}();	
	let prevWifiLevel = null
	let wifiData = null
	class DeviceConnectionState extends StatefulComponent{		
		getInitialState(){return {on:true,wifiLevel:null,waiting:null,data:null,showWifiInfo:false}}
		signal(on,data){			
			if(this.state.on!=on)
				this.setState({on});
			if(this.state.data!=data)
				this.setState({data})
		}
		wifiCallback(wifiLevel,plain){
			prevWifiLevel = wifiLevel
			wifiData = plain
			if(this.state.wifiLevel != wifiLevel){				
				this.setState({wifiLevel})
				const lvl = this.wifiLevel(wifiLevel)
				if(lvl!==null) this.props.onClickValue("change",lvl.toString())
			}
		}
		yellowSignal(on){
			if(this.state.waiting!=on)
				this.setState({waiting:on})
		}
		componentDidMount(){	
			if(!this.el) return		
			this.ctx = rootCtx(this.props.ctx)
//			const isSibling = Branches.isSibling(this.ctx)
//			if(isSibling) return
			if(PingReceiver) this.pingR = PingReceiver.reg(this.signal)
			this.toggleOverlay(!this.state.on);			
			this.wifi = miscUtil.scannerProxy.regWifi(this.wifiCallback)
			this.wifi2 = miscUtil.winWifi.regWifi(this.wifiCallback)			
			/*if(this.props.onContext && requestState.reg){
				const branchKey = this.props.onContext()
				this.yellow = requestState.reg({branchKey,callback:this.yellowSignal})
			}*/
		}
		componentWillUnmount(){			
			if(this.pingR) this.pingR.unreg();
			if(this.wifi) this.wifi.unreg();
			if(this.wifi2) this.wifi2.unreg();
			if(this.yellow) this.yellow.unreg();
		}		
		toggleOverlay(on){
			if(!this.props.overlay) return;
			if(this.props.msg||this.state.waiting) 
				overlayManager.delayToggle(this.props.msg||this.state.waiting)
			else
				overlayManager.toggle(on)			
		}
		componentDidUpdate(prevProps,prevState){
			if(!this.el) return
			const isSibling = Branches.isSibling(this.ctx)
			if(isSibling) return	
			if(PingReceiver && !this.pingR) this.pingR = PingReceiver.reg(this.signal)
			if(prevState.on != this.state.on){
				log(`toggle ${this.state.on}`)
				this.toggleOverlay(!this.state.on);
			}
		}
		onMouseOver(){			
			this.setState({showWifiInfo:true});
		}
		onMouseOut(){
			if(this.state.showWifiInfo)
				this.setState({showWifiInfo:false});
		}
		wifiLevel(newLevel){
			const _lvl = newLevel?newLevel:this.state.wifiLevel
			return prevWifiLevel&&!_lvl?prevWifiLevel:_lvl
		}
		render(){
			const wifiLevel = this.wifiLevel()||(this.props.wifiLevel?this.props.wifiLevel:null)
			const wifiStyle = wifiLevel!==null?{padding:"0.11em 0em"}:{}
			const wifiIconStyle = wifiLevel!==null?{width:"0.7em"}:{}
			const waitingStyle = (this.props.msg || this.state.waiting)?{backgroundColor:"yellow",color:"rgb(114, 114, 114)"}:{}
			const style={
				color:"white",
				textAlign:"center",
				...waitingStyle,
				...wifiStyle,
				...this.props.style
			};
			const iconStyle={
				...wifiIconStyle
			};
			if(this.props.style) Object.assign(style,this.props.style);
			if(this.props.iconStyle) Object.assign(iconStyle,this.props.iconStyle);
			let imageSvgData = null;
			if(wifiLevel !== null){ //0 - 4
				const wL = parseInt(wifiLevel)
				const getColor = (cond) => cond?"white":"transparent"
				const l4C = getColor(wL>=4)
				const l3C = getColor(wL>=3)
				const l2C = getColor(wL>=2)
				const l1C = getColor(wL>=1)
				const wifiSvg = `
				<svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" version="1.1" x="0px" y="0px" viewBox="0 0 147.586 147.586" style="enable-background:new 0 0 147.586 147.586;" xml:space="preserve">
					<path style="stroke:white;fill: ${l2C};" d="M48.712,94.208c-2.929,2.929-2.929,7.678,0,10.606c2.93,2.929,7.678,2.929,10.607,0   c7.98-7.98,20.967-7.98,28.947,0c1.465,1.464,3.385,2.197,5.304,2.197s3.839-0.732,5.304-2.197c2.929-2.929,2.929-7.678,0-10.606   C85.044,80.378,62.542,80.378,48.712,94.208z"></path>
					<path style="stroke:white;fill: ${l3C};" d="M26.73,72.225c-2.929,2.929-2.929,7.678,0,10.606s7.677,2.93,10.607,0   c20.102-20.102,52.811-20.102,72.912,0c1.465,1.464,3.385,2.197,5.304,2.197s3.839-0.732,5.304-2.197   c2.929-2.929,2.929-7.678,0-10.606C94.906,46.275,52.681,46.275,26.73,72.225z"></path>
					<path style="stroke:white;fill: ${l4C};" d="M145.39,47.692c-39.479-39.479-103.715-39.479-143.193,0c-2.929,2.929-2.929,7.678,0,10.606   c2.93,2.929,7.678,2.929,10.607,0c16.29-16.291,37.95-25.262,60.989-25.262s44.699,8.972,60.989,25.262   c1.465,1.464,3.385,2.197,5.304,2.197s3.839-0.732,5.304-2.197C148.319,55.37,148.319,50.621,145.39,47.692z"></path>
					<circle style="stroke:white;fill: ${l1C};" cx="73.793" cy="121.272" r="8.231"></circle>
				</svg>`;
				imageSvgData = svgSrc(wifiSvg)				
			}	
			const wifiDataEl = this.state.showWifiInfo&&wifiData?React.createElement("pre",{style:{
				position:"absolute",
				marginTop:"2.7em",
				width:"40em",
				fontSize:"12px",
				zIndex:"1000",
				backgroundColor:"blue",
				right:"0.24em",
				textAlign:"left",
				color:"white"
			},key:"2"},wifiData):null;
			const on = (this.props.on === false)? false: this.state.on
			return React.createElement("div",{ref:ref=>this.el=ref,style:{display:"flex"},onMouseEnter:this.onMouseOver,onMouseLeave:this.onMouseOut},[
				React.createElement(ConnectionState,{key:"1",onClick:this.props.onClick,on,style:style,iconStyle:iconStyle,imageSvgData}, null),
				wifiDataEl,
				React.createElement("span",{style:{fontSize:"10px",alignSelf:"center"},key:"3"},this.state.data)
			])
		}
	}
	const DragWrapperElement = (props)=> {					
		const dragData = props.dragData
		const dragStyle = {border:"1px solid grey",backgroundColor:"grey"}
		return $(DragDropDivElement,{draggable:true,droppable:true,dragStyle,dragData},[
			$("div",{key:"1",style:props.style},props.caption),
			$("div",{key:"2",style:{display:"flex",flexWrap:"wrap",justifyContent:"center"}},props.children)
		])		
	}
	const download = (data) =>{
		const anchor = documentManager.createElement("a")
		const location = windowManager.location
		const path = data//location.pathname.replace(/(.*)\/[^/]*/, "$1/"+data);
		
		//anchor.href = location.protocol+"//"+location.host+path
		anchor.href = path
		anchor.download = data.split('/').reverse()[0]
		documentManager.add(anchor)
		anchor.click()
		documentManager.remove(anchor)
	}
	
    class NoShowUntilElement extends StatefulComponent{
		componentWillUnmount(){
			if(this.interval) clearInterval(this.interval)			
		}
		ratio(){
			const mmH = this.mmRef.getBoundingClientRect().height
			const remH = this.remRef.getBoundingClientRect().height
			if(mmH<=0||remH<=0) return null
			return mmH/remH
		}
		componentDidMount(){	
			this.ctx = rootCtx(this.props.ctx)
			const isSibling = Branches.isSibling(this.ctx)
			if(isSibling) return
			this.props.onClickValue("change",this.ratio().toString())	
			this.interval = setInterval(()=>{
				if(this.props.show) return 
				const r = this.ratio()
				if(!r) return 
				this.props.onClickValue("change",r.toString())			
			}, 1000)
		}		
		render(){			
			return [		    				
				$("div",{key:"mm",ref:ref=>this.mmRef = ref,style:{height:"1mm",position:"absolute",zIndex:"-1"}}),
				$("div",{key:"em",ref:ref=>this.remRef = ref,style:{height:"1em",position:"absolute",zIndex:"-1"}}),
				$("div",{key:"children"},this.props.show?this.props.children:null)			
			]
		}
	}
	class CanvasMaxHeightElement extends StatefulComponent{
		getInitialState(){
			return {height:null}			
		}
		setHeight(height){
			if(this.state.height != height) this.setState({height})
		}
		check(){
			const bRect = this.root.getBoundingClientRect()			
			const rect = this.el.getBoundingClientRect()			
			if(this.footer){
				const fRect = this.footer.getBoundingClientRect()							
				this.setHeight(Math.max(fRect.top - rect.top, this.origHeight - rect.top))
				
			}
			else {
				this.setHeight(this.origHeight - rect.top)
			}
		}		
		setRootRect(){
			this.rootRect = this.root.getBoundingClientRect()
			this.origHeight = this.rootRect.height
		}
		componentDidMount(){
			this.root = miscReact.getReactRoot(this.el)
			this.setRootRect()
			this.footer = this.root.querySelector(".mainFooter")			
			checkActivateCalls.add(this.check)
		}			
		componentWillUnmount(){
			checkActivateCalls.remove(this.check)
		}
		render(){		
			const style = {
				height:!this.state.height?"auto":this.state.height+"px"
			}
			const drawChildren = this.state.height?this.props.children:null
			
			return $("div",{style,ref:ref=>this.el=ref},drawChildren)
		}
	}
	const sendVal = ctx =>(action,value,opt) =>{
		const act = action.length>0?action:"change"
		const optHeader = opt?{"X-r-opt":opt}:{}
		requestState.send(ctx,({headers:{"X-r-action":act,...optHeader},value}));
	}
	const sendBlob = ctx => (name,value) => {requestState.send(ctx,({headers:{"X-r-action":name},value}));}	
	const onWResize = ({sendVal}) 
	const onClickValue = ({sendVal});
	const onDragDrop = ({sendVal});
	const onReorder = ({sendVal});
	const onReadySendBlob = ({sendBlob});	
	const transforms= {
		tp:{
            DocElement,FlexContainer,FlexElement,ButtonElement, TabSet, GrContainer, FlexGroup,
            InputElement,AnchorElement,HeightLimitElement,ImageElement,SoundProducerElement,
			DropDownElement,ControlWrapperElement,LabeledTextElement,MultilineTextElement,
			LabelElement,ChipElement,ChipDeleteElement,FocusableElement,PopupElement,Checkbox,
            RadioButtonElement,FileUploadElement,TextAreaElement,
			DateTimePicker,DateTimePickerYMSel,DateTimePickerDaySel,DateTimePickerTSelWrapper,DateTimePickerTimeSel,DateTimePickerNowSel,
			DateTimeClockElement,
            MenuBarElement,MenuDropdownElement,FolderMenuElement,ExecutableMenuElement,MenuBurger,
            TableElement,THeadElement,TBodyElement,THElement,TRElement,TDElement,
            ConnectionState,
			SignIn,ChangePassword,
			ErrorElement,
			FocusAnnouncerElement,
			ConfirmationOverlayElement,
			DragDropHandlerElement,
			DragDropDivElement,			
			ColorCreator,ColorItem,ColorPicker,
			InteractiveAreaElement,ZoomOnPopupElement,
			BatteryState,DeviceConnectionState,
			DragWrapperElement,
			CanvasMaxHeightElement,
			NoShowUntilElement			
    },
		onClickValue,		
		onReadySendBlob,
		onDragDrop,
		onReorder,
		onWResize,
		onContext:({ctx:ctx=>()=>rootCtx(ctx).branchKey})
	};
	const receivers = {
		download,
		ping:PingReceiver.ping,		
		branches:Branches.store,
		...errors.receivers
	}	
	const checkActivate = checkActivateCalls.check	
	return ({transforms,receivers,checkActivate});
}
