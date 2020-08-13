
const HtmlWebpackPlugin = require('html-webpack-plugin')
const config = require("./webpack.include.js").config("test",__dirname,[])
module.exports = env=>{
    const names = ["react-app","sse"]
    const conf = config(names,env)
    return ({
        ...conf,
        plugins: [
            ...conf.plugins,
            ...names.map(name=>new HtmlWebpackPlugin({
              filename: name + ".html",
              title: name,
              hash: true,
              favicon: "./src/test/favicon.png",
            }))
        ],
    })
}
