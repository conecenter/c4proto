
// use `npm outdated`

const path = require('path')
const HtmlWebpackPlugin = require('html-webpack-plugin')

function config(kind,name) {
    return {
        entry: "./src/"+kind+"/"+name+".js",
        output: {
            path: "build/"+kind,
            filename: name + ".js"
        },
        module: { rules: [
            {
                test: /\/src\/(main|extra)\/.*\.jsx?$/,
                loader: "babel-loader",
                options: {
                    presets: [
                        ['es2015', {"modules": false}]
                    ],
                    plugins: ["transform-object-rest-spread","undeclared-variables-check","transform-es3-property-literals"],
                    //plugins: ["transform-es2015-modules-commonjs","transform-es2015-literals"]
                    cacheDirectory: true
                }
            },
            {
                test: /\/src\/test\/.*\.jsx?$/,
                exclude: /(node_modules|bower_components)/,
                loader: "babel-loader",
                options: {
                    presets: [
                        ['es2015', {"modules": false}]
                    ],
                    plugins: ["transform-object-rest-spread"],
                    //plugins: ["transform-es2015-modules-commonjs","transform-es2015-literals"]
                    cacheDirectory: true
                }
            }
        ]},
        plugins: [new HtmlWebpackPlugin({
            filename: name + ".html",
            title: name,
            hash: true,
            favicon: "./src/test/favicon.png"
        })],
        devtool: "source-map", //"cheap-source-map"
        resolve: {
            alias: {
                c4p: path.resolve(__dirname, "src")
            }
        }
    }
}

module.exports.config = config