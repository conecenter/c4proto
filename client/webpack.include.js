
// use `npm outdated`

const path = require('path')
const MiniCssExtractPlugin = require('mini-css-extract-plugin')
const isDev = process.env.NODE_ENV === 'development'
const isProd = !isDev

const styleLoader = extra => {
  const loaders = [{
      loader: MiniCssExtractPlugin.loader,
      options: {
          hmr: isDev,
          reloadAll: true
      },
  }, 'css-loader']
  if (extra) {
      loaders.push(extra)
  }
  return loaders
}

const config = (kind,outDir,loaderRules) => (names,env) => ({
      entry: Object.assign({styles:"./src/styles/style.scss"},...names.map(name=>({ [name]: "./src/"+kind+"/"+name+".js" }))),
      output: {
        path: outDir+"/build/"+kind,
        filename: "[name].js",
      },
      plugins: [new MiniCssExtractPlugin({filename: '[name].css'})],
      module: {
        rules: !env || !env.fast ? [
          {
            enforce: "pre",  
            test: /[\\\/]src[\\\/].*(main|extra|test)[\\\/].*\.*(jsx|js)?$/,           
            exclude: /node_modules/,
            loader: 'eslint-loader',
            options: {}
          },
          {
            test: /[\\\/]src[\\\/].*(main|extra|test)[\\\/].*\.*(jsx|js)?$/,
            exclude: /node_modules/,
            loader: 'babel-loader',
            options:{
              presets: [["@babel/preset-env",
                    {targets: "> 0.25%, not dead"},
              ],["@babel/preset-react"]],
              cacheDirectory: true,
            }
          },
          {
            test: /\.s[ac]ss$/,
            exclude: /node_modules/,
            use: isProd ? [
                {
                    loader: 'file-loader',
                    options: { outputPath:  "/", name: '[name].css'}
                  },
                  'sass-loader'
              ] : styleLoader("sass-loader")
          },
          ...loaderRules
        ] : loaderRules
      },
      devtool: 'source-map',
      resolve: {
        modules: [outDir+"/node_modules"],
        alias: {
          c4p: path.resolve(__dirname, "src")
        }
      }
})

module.exports.config = config