const AutoDllPlugin = require('autodll-webpack-plugin')
const HardSourceWebpackPlugin = require('hard-source-webpack-plugin')

const MiniCssExtractPlugin = require('mini-css-extract-plugin')
const isDev = process.env.NODE_ENV === 'development'
const isProd = !isDev
const loaderRules = [
  {
    test: /\.(sass|scss)$/,
    exclude: /node_modules/,
    use: isProd ? [
        {
            loader: 'file-loader',
            options: { outputPath:  "/", name: '[name].css'}
        },
        'sass-loader'
    ] : [
        {
            loader: MiniCssExtractPlugin.loader,
            options: {
                hmr: isDev,
                reloadAll: true
            },
        },
        'css-loader',
        'sass-loader'
    ]
  }
]

const config = require("./webpack.include.js").config("test",__dirname,loaderRules)
module.exports = env=>{
    const conf = config(["react-app","sse","vdom-flist-app"],env)
    return {
        ...conf,
        entry: {
            ...conf.entry,
            styles: "./src/test/style.scss",
        },
        plugins: [
            ...conf.plugins,
            new MiniCssExtractPlugin({filename: '[name].css'}),
            /*
            new AutoDllPlugin({
                filename: '[name].js',
                entry: {
                  vendor: [
                    'react',
                    'react-dom',
                  ]
                }
            }),*/
            new HardSourceWebpackPlugin(),
            new HardSourceWebpackPlugin.ExcludeModulePlugin([
                {
                    // HardSource works with mini-css-extract-plugin but due to how
                    // mini-css emits assets, assets are not emitted on repeated builds with
                    // mini-css and hard-source together. Ignoring the mini-css loader
                    // modules, but not the other css loader modules, excludes the modules
                    // that mini-css needs rebuilt to output assets every time.
                    test: /mini-css-extract-plugin[\\/]dist[\\/]loader/,
                },
            ]),
        ],
        optimization: {
            minimize: false,
        }
    }
}
