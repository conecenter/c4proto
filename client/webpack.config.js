
var HtmlWebpackPlugin = require('html-webpack-plugin')

function config(kind,name) {
    return {
        entry: "./src/"+kind+"/"+name+".js",
        output: {
            path: "build/"+kind,
            filename: name + ".js"
        },
        module: { loaders: [
            {
                test: /\.jsx?$/,
                exclude: /(node_modules|bower_components)/,
                loader: "babel",
                query: {
                    presets: ['es2015']
                    //plugins: ["transform-es2015-modules-commonjs","transform-es2015-literals"]
                }
            }
        ]},
        plugins: [new HtmlWebpackPlugin({
            filename: name + ".html",
            title: name,
            hash: true,
            favicon: "./src/main/favicon.png"
        })]
    }
}

module.exports = [
    config("test","react-app"),
    config("test","metro-app"),
    //config("test","btn"),
    config("test","sse")/*,
    config("test","hello")*/
]
