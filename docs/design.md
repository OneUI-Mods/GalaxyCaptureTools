# Galaxy Capture Tools 開発記録

対象: Galaxy SM-S948Q / Android 16 / One UI 8.5。Smart Capture 6.1.76.9。
既存のGalaxy Adaptive Barとは別アプリ・別リポジトリで開発する。

## 要求

1. 動画の開始時の幅・高さを固定し、端末の回転差に応じて映像を90度回転。横長映像を縦動画へ縮小して入れる黒帯を解消する。
2. AOSP Android 14 QPR2のアプリ単位録画・標準アプリ選択へ準拠し、Galaxy録画でも利用する。
3. 追加オプションとして、タスクの切替先Activity全体へ追従せず、選択時の1つのウィンドウへ固定する。
4. それぞれON/OFF。Material 3、多言語、クラッシュ保護、release署名配布。

## 確認した実装

- Galaxy録画のMP4RecorderもMediaProjectionを使用する。semGetMediaProjectionからprojectionを作り、固定寸法のVirtualDisplayへ投影する。onCapturedContentResizeは何もしない。
- SystemUIにAOSP系MediaProjectionAppSelectorActivityが存在する。標準のLaunchCookieとtaskIdを返し、IMediaProjectionへ設定できる。
- ContentRecorderはSurfaceControlのmirrorへ等比縮小と中央配置を適用する。ここで回転差に応じた座標変換を適用すれば、再エンコードや毎フレームのCPUコピーなしで対策できる可能性がある。
- AOSPのタスク単位録画を基準に、固定ウィンドウは追加の制約として扱う。保護画面のフラグ・secure layer・MediaProjectionの同意や停止処理は維持する。

## 参照

- https://developer.android.com/about/versions/14/features/app-screen-sharing
- https://developer.android.com/media/grow/media-projection
- 端末の実装調査資料はevidence/deviceへ保存し、Git・配布ZIPには含めない。
