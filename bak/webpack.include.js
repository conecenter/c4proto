

const path = require('path')

const config = (kind,outDir,loaderRules) => (names,env) => ({
      entry: Object.assign({},...names.map(name=>({ [name]: "./src/"+kind+"/"+name+".js" }))),
      output: {
        path: outDir+"/build/"+kind,
        filename: "[name].js",
      },  
      module: {
        rules: !env || !env.fast ? [
          {
            enforce: "pre",  
            test: /[\\\/]src[\\\/].*(main|extra|test)[\\\/].*\.jsx?$/,           
            exclude: /node_modules/,
            loader: 'eslint-loader',
            options: {}
          },
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
        ] : loaderRules
      },
      devtool: 'source-map',
      plugins: [],
      resolve: {
        modules: [outDir+"/node_modules"],
        alias: {
          c4p: path.resolve(__dirname, "src")
        }
      }
})

module.exports.config = config