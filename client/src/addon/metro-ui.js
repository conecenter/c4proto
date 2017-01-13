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
        var selStyle={
            outline:this.state.touch?'0.1rem solid blue':'none',
			outlineOffset:'-0.1rem',
        }        
        
		if(this.props.style)
			Object.assign(selStyle,this.props.style);
		if(this.state.mouseOver)
			Object.assign(selStyle,this.props.overStyle);
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
			marginBottom:'0.1rem',
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
		if(this.props.fkey){
			var event=new KeyboardEvent("keypress",{key:this.props.fkey})
			window.dispatchEvent(event);
		}
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
			verticalAlign:'top',
			outline:this.state.touch?'0.1rem solid blue':'none',
			color:'inherit',
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
            border:'0.01rem solid',
            backgroundColor:'#eeeeee',
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
            border:'0.01rem solid',
            backgroundColor:'#eeeeee',
            minWidth:'1.1em',
            //paddingBottom:'0.1rem',
        };
        var aTableLastStyle={
            marginBottom:'-0.275rem',
            position:'relative',
            left:'0.57rem',

        };
		var specialTdStyle=Object.assign({},tdStyle,this.props.specialKeyStyle);
		var specialTdAccentStyle=Object.assign({},tdStyle,this.props.specialKeyAccentStyle);
		var specialAKeyCellStyle=Object.assign({},aKeyCellStyle,this.props.specialKeyStyle);
		var specialAKeyCellAccentStyle=Object.assign({},aKeyCellStyle,this.props.specialKeyAccentStyle);
		const backSpaceFillColor=this.props.alphaNumeric?(specialAKeyCellAccentStyle.color?specialAKeyCellAccentStyle.color:"#000"):(specialTdAccentStyle.color?specialTdAccentStyle.color:"#000");
		const enterFillColor=this.props.alphaNumeric?(aKeyCellStyle.color?aKeyCellStyle.color:"#000"):(tdStyle.color?tdStyle.color:"#000");
		const upFillColor=this.props.alphaNumeric?(aKeyCellStyle.color?aKeyCellStyle.color:"#000"):(tdStyle.color?tdStyle.color:"#000");
		const downFillColor=this.props.alphaNumeric?(aKeyCellStyle.color?aKeyCellStyle.color:"#000"):(tdStyle.color?tdStyle.color:"#000");
		const backSpaceSvg = '<svg version="1.1" xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" width="24" height="24" viewBox="0 0 24 24"><g fill="'+backSpaceFillColor+'" transform="scale(0.0234375 0.0234375)"><path d="M896 470v84h-604l152 154-60 60-256-256 256-256 60 60-152 154h604z" /></g></svg>';
		const enterSvg = '<svg version="1.1" xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" width="24" height="24" viewBox="0 0 24 24"><g fill="'+enterFillColor+'" transform="scale(0.0234375 0.0234375)"><path d="M810 298h86v256h-648l154 154-60 60-256-256 256-256 60 60-154 154h562v-172z" /></g></svg>';
		const upSvg = '<svg version="1.1" xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" width="24" height="24" viewBox="0 0 24 24"><g fill="'+upFillColor+'" transform="scale(0.0234375 0.0234375)"><path d="M316 658l-60-60 256-256 256 256-60 60-196-196z" /></g></svg>';
		const downSvg = '<svg version="1.1" xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" width="24" height="24" viewBox="0 0 24 24"><g fill="'+downFillColor+'" transform="scale(0.0234375 0.0234375)"><path d="M316 334l196 196 196-196 60 60-256 256-256-256z" /></g></svg>';
		const backSpaceSvgData="data:image/svg+xml;base64,"+window.btoa(backSpaceSvg);
		const enterSvgData="data:image/svg+xml;base64,"+window.btoa(enterSvg);
		const upSvgData="data:image/svg+xml;base64,"+window.btoa(upSvg);
		const downSvgData="data:image/svg+xml;base64,"+window.btoa(downSvg);
		const backSpaceEl = React.createElement("img",{src:backSpaceSvgData,style:{width:"100%",height:"100%"}},null);
		const enterEl = React.createElement("img",{src:enterSvgData,style:{width:"100%",height:"100%"}},null);
		const upEl = React.createElement("img",{src:upSvgData,style:{width:"100%",height:"100%"}},null);
		const downEl = React.createElement("img",{src:downSvgData,style:{width:"100%",height:"100%"}},null);
        var result;
        if(!this.props.alphaNumeric)
            result=React.createElement("table",{style:tableStyle,key:"1"},
				React.createElement("tbody",{key:"1"},[
				   React.createElement("tr",{key:"0"},[
					   React.createElement(VKTd,{colSpan:"2",style:specialTdAccentStyle,key:"1",fkey:"<-"},backSpaceEl),
					   React.createElement("td",{key:"2"},''),
					   React.createElement(VKTd,{colSpan:"2",style:specialTdAccentStyle,key:"3",onClick:this.switchMode},'ABC...'),
				   ]),
				   React.createElement("tr",{key:"1"},[
					   React.createElement(VKTd,{style:specialTdStyle,key:"1",fkey:"F1"},'F1'),
					   React.createElement(VKTd,{style:specialTdStyle,key:"2",fkey:"F2"},'F2'),
					   React.createElement(VKTd,{style:specialTdStyle,key:"3",fkey:"F3"},'F3'),
					   React.createElement(VKTd,{style:specialTdStyle,key:"4",fkey:"F4"},'F4'),
					   React.createElement(VKTd,{style:specialTdStyle,key:"5",fkey:"F5"},'F5'),					   
				   ]),
				   React.createElement("tr",{key:"2"},[
					   React.createElement(VKTd,{style:specialTdStyle,key:"1",fkey:"F6"},'F6'),
					   React.createElement(VKTd,{style:specialTdStyle,key:"2",fkey:"F7"},'F7'),
					   React.createElement(VKTd,{style:specialTdStyle,key:"3",fkey:"F8"},'F8'),
					   React.createElement(VKTd,{style:specialTdStyle,key:"4",fkey:"F9"},'F9'),
					   React.createElement(VKTd,{style:specialTdStyle,key:"5",fkey:"F10"},'F10'),					   
				   ]),
				   React.createElement("tr",{key:"3"},[
					   React.createElement(VKTd,{style:tdStyle,key:"1",fkey:"7"},'7'),
					   React.createElement(VKTd,{style:tdStyle,key:"2",fkey:"8"},'8'),
					   React.createElement(VKTd,{style:tdStyle,key:"3",fkey:"9"},'9'),
					   React.createElement(VKTd,{colSpan:'2',style:tdStyle,key:"4",fkey:"^"},upEl),
				   ]),
				   React.createElement("tr",{key:"4"},[
					   React.createElement(VKTd,{style:tdStyle,key:"1",fkey:"4"},'4'),
					   React.createElement(VKTd,{style:tdStyle,key:"2",fkey:"5"},'5'),
					   React.createElement(VKTd,{style:tdStyle,key:"3",fkey:"6"},'6'),
					   React.createElement(VKTd,{colSpan:'2',style:tdStyle,key:"4",fkey:"v"},downEl),
				   ]),
				   React.createElement("tr",{key:"5"},[
					   React.createElement(VKTd,{style:tdStyle,key:"1",fkey:"1"},'1'),
					   React.createElement(VKTd,{style:tdStyle,key:"2",fkey:"2"},'2'),
					   React.createElement(VKTd,{style:tdStyle,key:"3",fkey:"3"},'3'),
					   React.createElement(VKTd,{colSpan:'2',rowSpan:'2',style:tdStyle,key:"4",fkey:"enter"},enterEl),
				   ]),
				   React.createElement("tr",{key:"6"},[
					   React.createElement(VKTd,{colSpan:'3',style:tdStyle,key:"1",fkey:"0"},'0'),
				   ]),
			   ])
			);
        else
            result= React.createElement("div",{key:"1"},[
                React.createElement("table",{style:aTableStyle,key:"1"},
					React.createElement("tbody",{key:"1"},[
						React.createElement("tr",{key:"1"},[
							React.createElement(VKTd,{style:specialAKeyCellStyle,key:"1",fkey:"F1"},'F1'),
							React.createElement(VKTd,{style:specialAKeyCellStyle,key:"2",fkey:"F2"},'F2'),
							React.createElement(VKTd,{style:specialAKeyCellStyle,key:"3",fkey:"F3"},'F3'),
							React.createElement(VKTd,{style:specialAKeyCellStyle,key:"4",fkey:"F4"},'F4'),
						   // React.createElement(VKTd,{style:aKeyCellStyle},'F5'),
							React.createElement(VKTd,{style:specialAKeyCellStyle,key:"5",fkey:"F6"},'F6'),
							React.createElement(VKTd,{style:specialAKeyCellStyle,key:"6",fkey:"F7"},'F7'),
							React.createElement(VKTd,{style:specialAKeyCellStyle,key:"7",fkey:"F8"},'F8'),
							React.createElement(VKTd,{style:specialAKeyCellStyle,key:"8",fkey:"F9"},'F9'),
							React.createElement(VKTd,{style:specialAKeyCellStyle,key:"9",fkey:"F10"},'F10'),
							//React.createElement(VKTd,{onClick:function(){},style:Object.assign({},aKeyCellStyle,{width:'0rem',visibility:'hidden'})},''),
							React.createElement(VKTd,{onClick:this.switchMode,style:specialAKeyCellAccentStyle,key:"10"},'123...'),
						])
					])
				),
                React.createElement("table",{style:aTableStyle,key:"2"},
					React.createElement("tbody",{key:"1"},[
						React.createElement("tr",{key:"1"},[
							React.createElement(VKTd,{style:aKeyCellStyle,key:"1",fkey:"1"},'1'),
							React.createElement(VKTd,{style:aKeyCellStyle,key:"2",fkey:"2"},'2'),
							React.createElement(VKTd,{style:aKeyCellStyle,key:"3",fkey:"3"},'3'),
							React.createElement(VKTd,{style:aKeyCellStyle,key:"4",fkey:"4"},'4'),
							React.createElement(VKTd,{style:aKeyCellStyle,key:"5",fkey:"5"},'5'),
							React.createElement(VKTd,{style:aKeyCellStyle,key:"6",fkey:"6"},'6'),
							React.createElement(VKTd,{style:aKeyCellStyle,key:"7",fkey:"7"},'7'),
							React.createElement(VKTd,{style:aKeyCellStyle,key:"8",fkey:"8"},'8'),
							React.createElement(VKTd,{style:aKeyCellStyle,key:"9",fkey:"9"},'9'),
							React.createElement(VKTd,{style:aKeyCellStyle,key:"10",fkey:"0"},'0'),
						]),
					])
				),
                React.createElement("table",{style:aTableStyle,key:"3"},
					React.createElement("tbody",{key:"1"},[
						React.createElement("tr",{key:"1"},[
							React.createElement(VKTd,{style:aKeyCellStyle,key:"1",fkey:"Q"},'Q'),
							React.createElement(VKTd,{style:aKeyCellStyle,key:"2",fkey:"W"},'W'),
							React.createElement(VKTd,{style:aKeyCellStyle,key:"3",fkey:"E"},'E'),
							React.createElement(VKTd,{style:aKeyCellStyle,key:"4",fkey:"R"},'R'),
							React.createElement(VKTd,{style:aKeyCellStyle,key:"5",fkey:"T"},'T'),
							React.createElement(VKTd,{style:aKeyCellStyle,key:"6",fkey:"Y"},'Y'),
							React.createElement(VKTd,{style:aKeyCellStyle,key:"7",fkey:"U"},'U'),
							React.createElement(VKTd,{style:aKeyCellStyle,key:"8",fkey:"I"},'I'),
							React.createElement(VKTd,{style:aKeyCellStyle,key:"9",fkey:"O"},'O'),
							React.createElement(VKTd,{style:aKeyCellStyle,key:"10",fkey:"P"},'P'),
							React.createElement(VKTd,{style:specialAKeyCellAccentStyle,key:"11",fkey:"<-"},backSpaceEl),
						]),
					])
				),
                React.createElement("table",{style:Object.assign({},aTableStyle,{position:'relative',left:'0.18rem'}),key:"4"},
					React.createElement("tbody",{key:"1"},[
						React.createElement("tr",{key:"1"},[
							React.createElement(VKTd,{style:aKeyCellStyle,key:"1",fkey:"A"},'A'),
							React.createElement(VKTd,{style:aKeyCellStyle,key:"2",fkey:"S"},'S'),
							React.createElement(VKTd,{style:aKeyCellStyle,key:"3",fkey:"D"},'D'),
							React.createElement(VKTd,{style:aKeyCellStyle,key:"4",fkey:"F"},'F'),
							React.createElement(VKTd,{style:aKeyCellStyle,key:"5",fkey:"G"},'G'),
							React.createElement(VKTd,{style:aKeyCellStyle,key:"6",fkey:"H"},'H'),
							React.createElement(VKTd,{style:aKeyCellStyle,key:"7",fkey:"J"},'J'),
							React.createElement(VKTd,{style:aKeyCellStyle,key:"8",fkey:"K"},'K'),
							React.createElement(VKTd,{style:aKeyCellStyle,key:"9",fkey:"L"},'L'),
							React.createElement(VKTd,{style:aKeyCellStyle,rowSpan:"2",key:"10",fkey:"enter"},enterEl),
						]),
						React.createElement("tr",{key:"2"},[
							React.createElement("td",{style:Object.assign({},aKeyCellStyle,{backgroundColor:'transparent',border:'none'}),colSpan:"9",key:"1"},[
								React.createElement("table",{style:Object.assign({},aTableStyle,aTableLastStyle),key:"1"},
									React.createElement("tbody",{key:"1"},[
										React.createElement("tr",{key:"1"},[
											React.createElement(VKTd,{style:aKeyCellStyle,key:"1",fkey:"Z"},'Z'),
											React.createElement(VKTd,{style:aKeyCellStyle,key:"2",fkey:"X"},'X'),
											React.createElement(VKTd,{style:aKeyCellStyle,key:"3",fkey:"C"},'C'),
											React.createElement(VKTd,{style:aKeyCellStyle,key:"4",fkey:"V"},'V'),
											React.createElement(VKTd,{style:aKeyCellStyle,key:"5",fkey:"B"},'B'),
											React.createElement(VKTd,{style:aKeyCellStyle,key:"6",fkey:"N"},'N'),
											React.createElement(VKTd,{style:aKeyCellStyle,key:"7",fkey:"M"},'M'),
											React.createElement(VKTd,{style:Object.assign({},aKeyCellStyle,{minWidth:'2rem'}),key:"8",fkey:"^"},upEl),
										]),
										React.createElement("tr",{key:"2"},[
											React.createElement(VKTd,{style:Object.assign({},aKeyCellStyle,{visibility:"hidden"}),colSpan:"1",key:"1"},''),
											React.createElement(VKTd,{style:aKeyCellStyle,colSpan:"5",key:"2",fkey:"SPACE"},'SPACE'),
											React.createElement(VKTd,{style:Object.assign({},aKeyCellStyle,{visibility:"hidden"}),colSpan:"1",key:"3"},''),
											React.createElement(VKTd,{style:Object.assign({},aKeyCellStyle,{minWidth:'2rem'}),key:"4",fkey:"v"},downEl),
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
		//const gData=(data!=undefined&&parseInt(data)>=0?data:null);
		const gData=(data?data:null);
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
			flexGrow:'1',
		};
        Object.assign(style,this.props.style);
		const buttonText=this.props.buttonText?this.props.buttonText:"Save";
		const idleText = (this.props.idleText?this.props.idleText:"");
		const waitText = (this.props.waitText?this.props.waitText:"");
		const statusText = (this.state.data==null?idleText:waitText);
        return React.createElement(TDElement,{key:"wEl",odd:this.props.odd,style},[
			React.createElement(ControlledComparator,{key:"1",onChange:this.onChange,data:this.state.data},null),
			React.createElement('div',{key:"2",style:{display:'flex',flexWrap:'noWrap'}},[				
				React.createElement("input",{type:"text",readOnly:"readonly",key:"3",style:inpStyle,value:statusText},null),
				(this.state.data!=null?
				React.createElement(GotoButton,{key:"2",onClick:this.onClick,style:this.props.buttonStyle,overStyle:this.props.buttonOverStyle},buttonText):null),
			]),
        ]);
    },
});

const ControlledComparator = React.createClass({
	componentDidUpdate:function(prevP,prevS){
		if(this.props.onChange&&prevP.data!==this.props.data){			
			const e={target:{value:this.props.data}};
			console.log("change w");
			this.props.onChange(e);
		}
	},
	render:function(){		
		//const value = this.props.data!=null?this.props.data:"";
		return React.createElement('span',{key:"1"},null);
	},
});
const MetroUi={tp:{
	DocElement,FlexContainer,FlexElement,GotoButton,CommonButton, TabSet, GrContainer, FlexGroup, StatusElement, VirtualKeyboard, TerminalElement,MJobCell,
	TableElement,THeadElement,TBodyElement,THElement,TRElement,TDElement,
	
}}
export default MetroUi