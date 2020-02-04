
// use `npm outdated`

const path = require('path')

const config = (HtmlWebpackPlugin,kind,outDir,loaderRules) =>{
    return name=>env=>({
      entry: "./src/"+kind+"/"+name+".js",
      output: {        
        path: outDir+"/build/"+kind,
        filename: name + ".js",
      },  
      module: {
        rules: [
          !env || !env.fast ? {
            enforce: "pre",  
            test: /[\\\/]src[\\\/].*(main|extra|test)[\\\/].*\.jsx?$/,           
            exclude: /node_modules/,
            loader: 'eslint-loader',
            options: {}
          } : {},
          {
            test: /[\\\/]src[\\\/].*(main|extra|test)[\\\/].*\.jsx?$/,
            exclude: /node_modules/,
            loader: 'babel-loader',
            options:{
              presets: [["@babel/preset-env",
                    {targets: "> 0.25%, not dead"},
              ],["@babel/preset-react"]],
              cacheDirectory: true,
            }
          },
          ...loaderRules
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