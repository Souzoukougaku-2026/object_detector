## jointパッケージについて

Mobileアプリのリポジトリと合併する予定のモジュールたちをjointにまとめました

### MLWorker

バックグラウンドで物体検出の処理を回すための仕組みです。あくまで、jetpack
composeのWorkerという仕組みを利用して、ある作業を行える、という仕組みのみを実装したものです。

使うためには、

1. MLWorkerファイルを移植する
2. Foreground ServiceとしてManifestに情報を追記する
3. doWorkに行いたい処理を書く

の順で進めていきます。

### ObjectDetector

Bitmapを受け取って、実際に物体検出の処理を実行するモジュールです。
それ以外の責任はここにはないです。
