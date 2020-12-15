const HtmlWebpackPlugin = require('html-webpack-plugin')
const path = require('path');
const config = require("./webpack.include.js").config("test",__dirname,[])
module.exports = env=>{
    const conf = config(["react-app","sse"],env)
    conf.plugins.HtmlWebpackPlugin = new HtmlWebpackPlugin({
        filename: path.resolve("index.html"),
        title: "yess",
        hash: true,
        favicon: "./src/test/favicon.png"
    })
   // console.log(conf);
    return {
        ...conf,
        plugins: [
            ...conf.plugins,
            /*
            new AutoDllPlugin({
                filename: '[name].js',
                entry: {
                  vendor: [
                    'react',
                    'react-dom',
                    "immutability-helper",
                    "react-sortable-hoc",
                    "@material-ui/core",
                    "@material-ui/icons",
                  ]
                }
            }),*/
        ],
        optimization: {
            minimize: false,
        }
    }
}
