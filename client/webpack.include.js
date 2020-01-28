const path = require('path');

const config = (HtmlWebpackPlugin,kind) =>{
	return name=>({
	  entry: "./src/"+kind+"/"+name+".js",
	  output: {
		//path: __dirname + '/dist',
		//publicPath: '/',
		path: "build/"+kind,
		filename: name + ".js",
		//filename: 'bundle.js',
	  },  
	  module: {
		rules: [
		  {
			test: /[\\\/]src[\\\/](main|extra)[\\\/].*\.jsx?$/,		   
			exclude: /node_modules/,
			loader: 'eslint-loader',
			options:{
				 emitError: true,
			}
		  },
		  {		
			test: /[\\\/]src[\\\/](main|extra)[\\\/].*\.jsx?$/,
			exclude: /node_modules/,
			loader: 'babel-loader',
			options:{
				presets: [["@babel/preset-env",
					{targets: "> 0.25%, not dead"},
				],["@babel/preset-react"]],
				
				cacheDirectory: true,
			}
		  },
		]
	  },
	  devtool: 'source-map',
	  plugins: [
		new HtmlWebpackPlugin({
		  filename: name + ".html",
		  title: name,
		  hash: true,
		  favicon: "./src/test/favicon.png",
		}),
	  ],
	  resolve: {
            alias: {
                c4p: path.resolve(__dirname, "src")
            }
        }
	})	
}

module.exports.config = config