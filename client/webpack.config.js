
const HtmlWebpackPlugin = require('html-webpack-plugin')
const config = require("./webpack.include.js").config(HtmlWebpackPlugin,"test",__dirname,[])
module.exports = env=>[config("react-app")(env), config("sse")(env)]
