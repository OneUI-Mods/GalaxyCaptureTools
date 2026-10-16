# Galaxy Capture Tools

Android / Galaxyの既存の画面録画を拡張する実験的なVectorモジュールです。

- 動画の開始時の縦横サイズを固定し、端末回転時に映像を90度回して黒帯をなくす。
- Galaxy録画から、Android標準のアプリ選択画面を利用する。
- アプリ録画を選択時のメインウィンドウへ固定する追加設定。
- Material 3、日本語・英語・韓国語・簡体字・繁体字、個別ON/OFFとクラッシュ保護。

開発中です。対応確認・配布前の実機試験はまだ完了していません。

対象はSM-S948Q / Android 16 / One UI 8.5、ビルドBP4A.251205.006.S948QOPS1AZHL、Samsung Capture 6.1.76.9。Vectorの対象はシステムフレームワーク、SystemUI、Samsungキャプチャ。初回は端末の再起動が必要です。設定は次の録画開始から反映します。

AOSPの[アプリ単位の共有・録画](https://developer.android.com/about/versions/14/features/app-screen-sharing)を基準にしています。対象選択のキャンセルは録画の中止として扱います。

回転以外の理由でウィンドウの縦横比が変わった場合は、内容を切り落とさないため余白が残る場合があります。保護画面の録画制限は維持します。固定対象は選択時のメインウィンドウで、任意のダイアログを単独選択する機能はありません。

MIT License。依存ライブラリの表記はアプリ内と`app/src/main/assets/legal/`に含みます。
