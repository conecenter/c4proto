import React from 'react'

const FlexContainer = React.createClass({
    getInitialState:function(){
        return {};
    },
    render:function(){
        var style={
            display:'flex',
            flexWrap:this.props.wrap?'wrap':'nowrap',
        };
        if(this.props.style) Object.assign(style,this.props.style);
        return React.createElement("div",{style:style},this.props.children)
    }
});
const FlexElement = React.createClass({
    render:function(){
        var style={
            flexGrow:this.props.expand?'1':'0',
            flexShrink:'1',
            minWidth:'0px',
            flexBasis:this.props.minWidth?this.props.minWidth:'auto',
            maxWidth:this.props.maxWidth?this.props.maxWidth:'auto',
        };
        if(this.props.style) Object.assign(style,this.props.style);
        return React.createElement("div",{style:style},this.props.children)
    }
});
const button = React.createClass({    
    onClick:function(e){
        if(this.props.onClick){            
			this.props.onClick(e);
        }
    },
	onTouchStart:function(e){
		if(this.props.onTouchStart)
			this.props.onTouchStart(e);
	},
	onTouchEnd:function(e){
		if(this.props.onTouchEnd)
			this.props.onTouchEnd(e);
	},
    mouseOut:function(){
        if(this.props.onMouseOut)
            this.props.onMouseOut();
    },
    mouseOver:function(){
        if(this.props.onMouseOver)
            this.props.onMouseOver();
    },
	
    render:function(){
        var style={            
            border:'none',
            cursor:'pointer',
            paddingInlineStart:'6px',
            paddingInlineEnd:'6px',
            padding:'0 1rem',
            minHeight:'2rem',
            minWidth:'1rem',
			fontSize:'1rem',
        };
        if(this.props.style) Object.assign(style,this.props.style);
        return React.createElement('button',{style:style,onClick:this.onClick,onMouseOut:this.mouseOut,onMouseOver:this.mouseOver,onTouchStart:this.onTouchStart,onTouchEnd:this.onTouchEnd},this.props.children);
    }
});
const CommonButton=React.createClass({
    getInitialState:function(){
        return {mouseOver:false};
    },
    mouseOver:function(){
        this.setState({mouseOver:true});
    },
    mouseOut:function(){
        this.setState({mouseOver:false});
    },
	onClick:function(e){
		if(this.props.onClick)
			this.props.onClick(e);
	},
    render:function(){
        var newStyle={
            backgroundColor:this.state.mouseOver?'#ffffff':'#eeeeee',
        };
        return React.createElement(button,{style:newStyle,onMouseOver:this.mouseOver,onMouseOut:this.mouseOut,onClick:this.onClick},this.props.children);
    }
});
const GotoButton=React.createClass({
    getInitialState:function(){
        return {mouseOver:false,touch:false};
    },
    mouseOver:function(){
        this.setState({mouseOver:true});
    },
    mouseOut:function(){
        this.setState({mouseOver:false});
    },
	onTouchStart:function(e){
		this.setState({touch:true});
	},
	onTouchEnd:function(e){
		this.setState({touch:false});
	},
    render:function(){

        var newStyle={
            backgroundColor:this.state.mouseOver?'#fff3e0':'#fafafa',
        };
        var selStyle={
            backgroundColor:this.props.accent?'#fff3e0':'#fafafa',
			outline:this.state.touch?'0.1rem solid blue':'none',
			outlineOffset:'-0.1rem',
        }
        if(!this.props.accent)
            Object.assign(selStyle,newStyle);
		if(this.props.style)
			Object.assign(selStyle,this.props.style);
        return React.createElement(button,{style:selStyle,onMouseOver:this.mouseOver,onMouseOut:this.mouseOut,onClick:this.props.onClick,onTouchStart:this.onTouchStart,onTouchEnd:this.onTouchEnd},this.props.children);
    }
});
const TabSet=React.createClass({
    render:function(){
        var style={
            borderBottom:'0.05rem solid',         
            overflow:'hidden',
            display:'flex',
            marginTop:'0rem',
        };
        Object.assign(style,this.props.style);
        return React.createElement("div",{style:style},this.props.children);
    }
});
const DocElement=React.createClass({
	componentDidMount:function(){		
		if(this.props.fontSize)
			document.documentElement.style.fontSize=this.props.fontSize;
		if(this.props.fontFamily)
			document.documentElement.style.fontFamily=this.props.fontFamily;
	},
	render:function(){		
		return React.createElement("div");
	}	
});
const GrContainer= React.createClass({
    render:function(){
        var style={
            boxSizing:'border-box',           
            fontSize:'0.875rem',
            lineHeight:'1.1rem',
            margin:'0px auto',
            paddingTop:'0.3125rem',
        };
        return React.createElement("div",{style:style},this.props.children);
    }
});
const FlexGroup=React.createClass({
    render:function(){
        var style={
            backgroundColor:'white',
            border:'1px #b6b6b6 dashed',
            margin:'5px',
            padding:'1.25rem 1.825rem 1.25rem 2.5rem',
        };
        return React.createElement("div",{style:style},this.props.children);
    }
});
const Chip = React.createClass({
    render:function(){
        var newStyle={
            fontWeight:'bold',
            fontSize:'1.4rem',
            color:'white',
            textAlign:'center',
            borderRadius:'0.58rem',
            border:'1px solid '+(this.props.on?'#ffa500':'#eeeeee'),
            backgroundColor:(this.props.on?'#ffa500':'#eeeeee'),
            cursor:'default',
            width:'3.8rem',
            display:'block',
        };

        return React.createElement('input',{style:newStyle,readOnly:'readonly',value:this.props.children},null);
    }
});
const StatusElement=React.createClass({
    getInitialState:function(){
        return {lit:false};
    },
    signal:function(on){
        if(on) this.setState({lit:true});
        else this.setState({lit:false});
    },
    componentDidMount:function(){
	    if(window.CustomMeasurer) CustomMeasurer.regCallback(this.props.fkey.toLowerCase(),this.signal);
    },
    componentWillUnmount:function(){
	    if(window.CustomMeasurer) CustomMeasurer.unregCallback(this.props.fkey.toLowerCase());
    },    
    render:function(){
        var newStyle={
	        marginTop:'.6125rem',
        };
	
        return React.createElement(Chip,{style:newStyle,on:this.state.lit},this.props.fkey);
    }
});
const VKTd = React.createClass({
	getInitialState:function(){
		return {touch:false};
	},
    onClick:function(ev){
        if(this.props.onClick){
            this.props.onClick(ev);
            return;
        }
        var event=new KeyboardEvent("keypress",{key:this.props.children})
        window.dispatchEvent(event);
    },
	onTouchStart:function(e){
		this.setState({touch:true});
	},
	onTouchEnd:function(e){
		this.setState({touch:false});
	},
    render:function(){
        var bStyle={
            height:'100%',
            width:'100%',
            border:'none',
            fontStyle:'inherit',
            fontSize:'0.7em',
            backgroundColor:'inherit',
			outline:this.state.touch?'0.1rem solid blue':'none',
        };
        return React.createElement("td",{style:this.props.style,
                            colSpan:this.props.colSpan,rowSpan:this.props.rowSpan,onClick:this.onClick},
                            React.createElement("button",{style:bStyle,onTouchStart:this.onTouchStart,onTouchEnd:this.onTouchEnd},this.props.children));
        },
});
const VirtualKeyboard = React.createClass({
   /* getInitialState:function(){
        return {numeric:true};
    },*/
    switchMode:function(e){
        //if(this.state.numeric) this.setState({numeric:false});
        //else this.setState({numeric:true});
		if(this.props.onClick)
			this.props.onClick(e);
    },
    render:function(){
        var tableStyle={
            fontSize:'2.2rem',
            borderSpacing:'0.5rem',
            marginTop:'-0.5rem',
            marginLeft:'auto',
            marginRight:'auto',
        };
        var tdStyle={
            //padding:'0 .3125rem',
            textAlign:'center',
            verticalAlign:'middle',
            border:'1px solid',
            backgroundColor:'pink',
            height:'2.2rem',
        };
        var aTableStyle={
            fontSize:'1.55rem',
            borderSpacing:'0.3rem',
            marginTop:'-0.3rem',
            marginLeft:'auto',
            marginRight:'auto',
        };        
        var aKeyRowStyle={
            //marginBottom:'.3125rem',
            //display:'flex',
        };
        var aKeyCellStyle={
            //padding:'0 .3125rem',
            textAlign:'center',
            //margin:'0 0.5rem',
            verticalAlign:'middle',
            height:'1.4rem',
            border:'1px solid',
            backgroundColor:'pink',
            minWidth:'1.1em',
            paddingBottom:'0.1rem',
        };
        var aTableLastStyle={
            marginBottom:'-0.275rem',
            position:'relative',
            left:'0.57rem',

        };
		var specialTdStyle=Object.assign({},tdStyle,this.props.specialKeyStyle);
		var specialAKeyCellStyle=Object.assign({},aKeyCellStyle,this.props.specialKeyStyle);
       
        var result;
        if(!this.props.alphaNumeric)
            result=React.createElement("table",{style:tableStyle,key:"1"},
				React.createElement("tbody",{key:"1"},[
				   React.createElement("tr",{key:"0"},[
					   React.createElement(VKTd,{colSpan:"2",style:specialTdStyle,key:"1"},'<-'),
					   React.createElement("td",{key:"2"},''),
					   React.createElement(VKTd,{colSpan:"2",style:specialTdStyle,key:"3",onClick:this.switchMode},'ABC...'),
				   ]),
				   React.createElement("tr",{key:"1"},[
					   React.createElement(VKTd,{style:specialTdStyle,key:"1"},'F1'),
					   React.createElement(VKTd,{style:specialTdStyle,key:"2"},'F2'),
					   React.createElement(VKTd,{style:specialTdStyle,key:"3"},'F3'),
					   React.createElement(VKTd,{style:specialTdStyle,key:"4"},'F4'),
					   React.createElement(VKTd,{style:specialTdStyle,key:"5"},'F5'),					   
				   ]),
				   React.createElement("tr",{key:"2"},[
					   React.createElement(VKTd,{style:specialTdStyle,key:"1"},'F6'),
					   React.createElement(VKTd,{style:specialTdStyle,key:"2"},'F7'),
					   React.createElement(VKTd,{style:specialTdStyle,key:"3"},'F8'),
					   React.createElement(VKTd,{style:specialTdStyle,key:"4"},'F9'),
					   React.createElement(VKTd,{style:specialTdStyle,key:"5"},'F10'),					   
				   ]),
				   React.createElement("tr",{key:"3"},[
					   React.createElement(VKTd,{style:tdStyle,key:"1"},'7'),
					   React.createElement(VKTd,{style:tdStyle,key:"2"},'8'),
					   React.createElement(VKTd,{style:tdStyle,key:"3"},'9'),
					   React.createElement(VKTd,{colSpan:'2',style:tdStyle,key:"4"},'^'),
				   ]),
				   React.createElement("tr",{key:"4"},[
					   React.createElement(VKTd,{style:tdStyle,key:"1"},'4'),
					   React.createElement(VKTd,{style:tdStyle,key:"2"},'5'),
					   React.createElement(VKTd,{style:tdStyle,key:"3"},'6'),
					   React.createElement(VKTd,{colSpan:'2',style:tdStyle,key:"4"},'v'),
				   ]),
				   React.createElement("tr",{key:"5"},[
					   React.createElement(VKTd,{style:tdStyle,key:"1"},'1'),
					   React.createElement(VKTd,{style:tdStyle,key:"2"},'2'),
					   React.createElement(VKTd,{style:tdStyle,key:"3"},'3'),
					   React.createElement(VKTd,{colSpan:'2',rowSpan:'2',style:tdStyle,key:"4"},'enter'),
				   ]),
				   React.createElement("tr",{key:"6"},[
					   React.createElement(VKTd,{colSpan:'3',style:tdStyle,key:"1"},'0'),
				   ]),
			   ])
			);
        else
            result= React.createElement("div",{key:"1"},[
                React.createElement("table",{style:aTableStyle,key:"1"},
					React.createElement("tbody",{key:"1"},[
						React.createElement("tr",{key:"1"},[
							React.createElement(VKTd,{style:specialAKeyCellStyle,key:"1"},'F1'),
							React.createElement(VKTd,{style:specialAKeyCellStyle,key:"2"},'F2'),
							React.createElement(VKTd,{style:specialAKeyCellStyle,key:"3"},'F3'),
							React.createElement(VKTd,{style:specialAKeyCellStyle,key:"4"},'F4'),
						   // React.createElement(VKTd,{style:aKeyCellStyle},'F5'),
							React.createElement(VKTd,{style:specialAKeyCellStyle,key:"5"},'F6'),
							React.createElement(VKTd,{style:specialAKeyCellStyle,key:"6"},'F7'),
							React.createElement(VKTd,{style:specialAKeyCellStyle,key:"7"},'F8'),
							React.createElement(VKTd,{style:specialAKeyCellStyle,key:"8"},'F9'),
							React.createElement(VKTd,{style:specialAKeyCellStyle,key:"9"},'F10'),
							//React.createElement(VKTd,{onClick:function(){},style:Object.assign({},aKeyCellStyle,{width:'0rem',visibility:'hidden'})},''),
							React.createElement(VKTd,{onClick:this.switchMode,style:specialAKeyCellStyle,key:"10"},'123...'),
						])
					])
				),
                React.createElement("table",{style:aTableStyle,key:"2"},
					React.createElement("tbody",{key:"1"},[
						React.createElement("tr",{key:"1"},[
							React.createElement(VKTd,{style:aKeyCellStyle,key:"1"},'1'),
							React.createElement(VKTd,{style:aKeyCellStyle,key:"2"},'2'),
							React.createElement(VKTd,{style:aKeyCellStyle,key:"3"},'3'),
							React.createElement(VKTd,{style:aKeyCellStyle,key:"4"},'4'),
							React.createElement(VKTd,{style:aKeyCellStyle,key:"5"},'5'),
							React.createElement(VKTd,{style:aKeyCellStyle,key:"6"},'6'),
							React.createElement(VKTd,{style:aKeyCellStyle,key:"7"},'7'),
							React.createElement(VKTd,{style:aKeyCellStyle,key:"8"},'8'),
							React.createElement(VKTd,{style:aKeyCellStyle,key:"9"},'9'),
							React.createElement(VKTd,{style:aKeyCellStyle,key:"10"},'0'),
						]),
					])
				),
                React.createElement("table",{style:aTableStyle,key:"3"},
					React.createElement("tbody",{key:"1"},[
						React.createElement("tr",{key:"1"},[
							React.createElement(VKTd,{style:aKeyCellStyle,key:"1"},'Q'),
							React.createElement(VKTd,{style:aKeyCellStyle,key:"2"},'W'),
							React.createElement(VKTd,{style:aKeyCellStyle,key:"3"},'E'),
							React.createElement(VKTd,{style:aKeyCellStyle,key:"4"},'R'),
							React.createElement(VKTd,{style:aKeyCellStyle,key:"5"},'T'),
							React.createElement(VKTd,{style:aKeyCellStyle,key:"6"},'Y'),
							React.createElement(VKTd,{style:aKeyCellStyle,key:"7"},'U'),
							React.createElement(VKTd,{style:aKeyCellStyle,key:"8"},'I'),
							React.createElement(VKTd,{style:aKeyCellStyle,key:"9"},'O'),
							React.createElement(VKTd,{style:aKeyCellStyle,key:"10"},'P'),
							React.createElement(VKTd,{style:specialAKeyCellStyle,key:"11"},'<-'),
						]),
					])
				),
                React.createElement("table",{style:Object.assign({},aTableStyle,{position:'relative',left:'0.18rem'}),key:"4"},
					React.createElement("tbody",{key:"1"},[
						React.createElement("tr",{key:"1"},[
							React.createElement(VKTd,{style:aKeyCellStyle,key:"1"},'A'),
							React.createElement(VKTd,{style:aKeyCellStyle,key:"2"},'S'),
							React.createElement(VKTd,{style:aKeyCellStyle,key:"3"},'D'),
							React.createElement(VKTd,{style:aKeyCellStyle,key:"4"},'F'),
							React.createElement(VKTd,{style:aKeyCellStyle,key:"5"},'G'),
							React.createElement(VKTd,{style:aKeyCellStyle,key:"6"},'H'),
							React.createElement(VKTd,{style:aKeyCellStyle,key:"7"},'J'),
							React.createElement(VKTd,{style:aKeyCellStyle,key:"8"},'K'),
							React.createElement(VKTd,{style:aKeyCellStyle,key:"9"},'L'),
							React.createElement(VKTd,{style:aKeyCellStyle,rowSpan:"2",key:"10"},'enter'),
						]),
						React.createElement("tr",{key:"2"},[
							React.createElement("td",{style:Object.assign({},aKeyCellStyle,{backgroundColor:'transparent',border:'none'}),colSpan:"9",key:"1"},[
								React.createElement("table",{style:Object.assign({},aTableStyle,aTableLastStyle),key:"1"},
									React.createElement("tbody",{key:"1"},[
										React.createElement("tr",{key:"1"},[
											React.createElement(VKTd,{style:aKeyCellStyle,key:"1"},'Z'),
											React.createElement(VKTd,{style:aKeyCellStyle,key:"2"},'X'),
											React.createElement(VKTd,{style:aKeyCellStyle,key:"3"},'C'),
											React.createElement(VKTd,{style:aKeyCellStyle,key:"4"},'V'),
											React.createElement(VKTd,{style:aKeyCellStyle,key:"5"},'B'),
											React.createElement(VKTd,{style:aKeyCellStyle,key:"6"},'N'),
											React.createElement(VKTd,{style:aKeyCellStyle,key:"7"},'M'),
											React.createElement(VKTd,{style:Object.assign({},aKeyCellStyle,{minWidth:'2rem'}),key:"8"},'^'),
										]),
										React.createElement("tr",{key:"2"},[
											React.createElement(VKTd,{style:Object.assign({},aKeyCellStyle,{visibility:"hidden"}),colSpan:"1",key:"1"},''),
											React.createElement(VKTd,{style:aKeyCellStyle,colSpan:"5",key:"2"},'SPACE'),
											React.createElement(VKTd,{style:Object.assign({},aKeyCellStyle,{visibility:"hidden"}),colSpan:"1",key:"3"},''),
											React.createElement(VKTd,{style:Object.assign({},aKeyCellStyle,{minWidth:'2rem'}),key:"4"},'v'),
										]),
									])
								),
							]),
						]),
					])
				),

            ]);
        return result;
    },
});
const TerminalElement=React.createClass({   
    componentDidMount:function(){
        if(window.CustomTerminal)
			CustomTerminal.init(this.props.host,this.props.port,this.props.username,this.props.password);
    },
    componentWillUnmount:function(){
		if(window.CustomTerminal)
			CustomTerminal.destroy();       
    },
    render:function(){
        var style={
            fontSize:'0.8rem',
        };
		if(this.props.style)
			Object.assign(style,this.props.style);
        return React.createElement("div",{id:'terminal',style:style},null);
    },
});

const TableElement = React.createClass({
	render:function(){
		var tableStyle={
            borderCollapse:'separate',
            borderSpacing:'0px',
            width:'100%',
        };
		if(this.props.style)
			Object.assign(tableStyle.this.props.style);
		return React.createElement("table",{style:tableStyle},this.props.children);
	}	
});
const THeadElement = React.createClass({
	render:function(){
		var theadStyle={};
		if(this.props.style)
			Object.assign(theadStyle.this.props.style);
		return React.createElement("thead",{style:theadStyle},this.props.children);
	}	
});
const TBodyElement = React.createClass({
	render:function(){
		var tbodyStyle={};
		if(this.props.style)
			Object.assign(tbodyStyle.this.props.style);
		return React.createElement("tbody",{style:tbodyStyle},this.props.children);
	}	
});
const THElement = React.createClass({
	render:function(){
		var bColor='#b6b6b6';
		var thStyle={
            backgroundColor:'#eeeeee',
            borderBottom:'1px solid '+bColor,
            borderLeft:'none',
            borderRight:'1px solid '+bColor,
            borderTop:'1px solid '+bColor,
            fontWeight:'bold',
            padding:'1px 2px 1px 2px',
            verticalAlign:'middle',
        };        
		if(this.props.style)
			Object.assign(thStyle,this.props.style);
		return React.createElement("th",{style:thStyle},this.props.children);
	}	
});
const TDElement = React.createClass({
	render:function(){
		var tdStyle={};
		var bColor='#b6b6b6';
		var thStyle={
            backgroundColor:'#eeeeee',
            borderBottom:'1px solid '+bColor,
            borderLeft:'none',
            borderRight:'1px solid '+bColor,
            borderTop:'1px solid '+bColor,
            fontWeight:'bold',
            padding:'0.5rem 1rem',
            verticalAlign:'middle',
			fontSize:'1.7rem',
        };
        var tdStyleOdd=Object.assign({},thStyle,{
            borderBottom:'none',
            fontWeight:'normal',			
            backgroundColor:'#fafafa',
        });
        var tdStyleEven=Object.assign({},thStyle,{
            borderBottom:'none',
            fontWeight:'normal',
            backgroundColor:'#ffffff',
        });
		if(this.props.odd)
			Object.assign(tdStyle,tdStyleOdd);
		else
			Object.assign(tdStyle,tdStyleEven);
		if(this.props.style)
			Object.assign(tdStyle,this.props.style);
		return React.createElement("td",{style:tdStyle},this.props.children);
	}	
});
const TRElement = React.createClass({
	getInitialState:function(){
		return {touch:false};
	},
	onClick:function(e){
		if(this.props.onClick)
			this.props.onClick(e)
	},
	onTouchStart:function(e){
		if(this.props.onClick){
			this.setState({touch:true});
		}
	},
	onTouchEnd:function(e){
		if(this.props.onClick){
			this.setState({touch:false});
		}
	},
	render:function(){
		var trStyle={
			outline:this.state.touch?'0.1rem solid blue':'none',
			outlineOffset:'-0.1rem',
		};
		if(this.props.style)
			Object.assign(trStyle.this.props.style);
		return React.createElement("tr",{style:trStyle,onClick:this.onClick,onTouchStart:this.onTouchStart,onTouchEnd:this.onTouchEnd},this.props.children);
	}	
});
const MJobCell = React.createClass({
    getInitialState:function(){
        return {data:null};
    },
	signal:function(data){
		const gData=(data!=undefined&&parseInt(data)>=0?data:null);
		this.setState({data:gData});
	},
    componentDidMount:function(){
		if(window.CustomMeasurer)
			CustomMeasurer.regCallback(this.props.fkey,this.signal);        
    },
	componentWillUnmount:function(){
		if(window.CustomMeasurer)
			CustomMeasurer.unregCallback(this.props.fkey);
	},
	onChange:function(e){
		if(this.props.onChange)
			this.props.onChange(e);
	},
	onClick:function(e){
		if(this.props.onClick)
			this.props.onClick(e);
	},
    render:function(){
        var style={
            minWidth:'2rem',
        };
		const inpStyle={
			border:'none',
			fontSize:'1.7rem',
			width:'100%',
			backgroundColor:'inherit',
			padding:'0px',
			margin:'0px',
			flexBasis:'7rem',
		};
        Object.assign(style,this.props.style);
        return React.createElement(TDElement,{key:"wEl",odd:this.props.odd,style},
			React.createElement('div',{style:{display:'flex',flexWrap:'noWrap'}},[
				React.createElement(ControlledInput,{key:"1",style:inpStyle,onChange:this.onChange,data:this.state.data},null),
				(this.state.data!=null?
				React.createElement(CommonButton,{key:"2",onClick:this.onClick},"Save"):null),
			])
        );
    },
});
const ControlledInput = React.createClass({
	componentDidUpdate:function(prevP,prevS){
		if(this.props.onChange&&prevP.data!==this.props.data){			
			const e={target:this.input};
			console.log(this.input);
			this.props.onChange(e);
		}
	},
	render:function(){		
		const value = this.props.data!=null?this.props.data:"";
		return React.createElement('input',{ref:(ref)=>{this.input=ref},key:"1",readOnly:'readonly',style:this.props.style,value},null);
	},
});
const MetroUi={tp:{
	DocElement,FlexContainer,FlexElement,GotoButton,CommonButton, TabSet, GrContainer, FlexGroup, StatusElement, VirtualKeyboard, TerminalElement,MJobCell,
	TableElement,THeadElement,TBodyElement,THElement,TRElement,TDElement,
	
}}
export default MetroUi