
var HtmlWebpackPlugin = require('html-webpack-plugin')

function config(kind,name) {
    return {
        entry: "./src/"+kind+"/"+name+".js",
        output: {
            path: "build/"+kind,
            filename: name + ".js"
        },
        module: { rules: [
            {
                test: /\.jsx?$/,
                exclude: /(node_modules|bower_components)/,
                use: "babel-loader",
                options: {
                    presets: ['es2015', {"modules": false}],
                    plugins: ["transform-object-rest-spread","undeclared-variables-check"]
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
