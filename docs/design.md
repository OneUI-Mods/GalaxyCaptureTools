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

## 初回実機確認の経過

- release variantの非debuggable・検証Activity除外・zipalign・v3署名を検証し、端末へインストール。Vectorの本モジュールの対象だけをシステムフレームワーク・SystemUI・Samsung Captureに設定。機能の初期値はOFF。
- この段階の8件の座標変換テストとLintは成功。録画結果の実機検証は未完了。
- 読み込み直しにソフトリブートで十分なところ、誤って完全再起動を実行。以後の読み込み直しはソフトリブートのみとする。LANで新しいADBポートを見つけて一度再接続したが、suが見つからない状態となり、その後接続が再び切れた。その後、rootと全対象プロセスの読み込みを確認できた。初期版の黒帯補正は利用者の実機操作で有効・無効の違いが確認されたが、録画ファイルによる検証は未完了。

## build 2 の追加実装（実機確認前）

- 利用者の指示に合わせ、AOSP選択Activityへ飛ばす方式を廃止。Samsungの既存「録画するエリア」の全画面・一部の行を拡張し、アプリとウィンドウの選択、対象一覧、音声範囲を同じ開始ダイアログへ統合。
- 対象一覧は権限を限定した通信で取得する。実行中タスク・表示中ウィンドウ・起動可能アプリを扱い、対象IDは期限付き。表示中のウィンドウを選ぶと、そのWindowStateへ固定する。メインウィンドウに限り同じActivityRecordの再生成へ追従する。
- Galaxyの音声はSEM_PLAYBACK_RECORDという独自ソースだった。対象アプリのみを選んだ場合はMediaProjectionの再生キャプチャとUID条件へ切り替える。マイク側のソースは維持。先に必要となるprojectionは映像側でも再利用し、2つのprojectionを起動しない。Android標準側も再生キャプチャのUID条件を追加する。
- 全体スイッチを「すべての録画拡張」に変更し、OFF中の個別設定は無効・グレー表示。設定値は保持する。設定変更は次回録画へ適用する。
- 180度反転を無視する設定を独立して追加。黒帯補正ON時は開始向きと同じ軸なら0度、軸が変わったら最初の90度/270度を保持する。黒帯補正OFF時は標準の論理座標（回転0）を維持する。横開始でもこの設定を選択できる。
- 座標変換8件、再起動保護7件、回転方針5件の単体テスト、Lint、releaseビルド成功。録画した映像と複数UIDの音声を使う実機検証はこれから行う。

## 録画開始画面のデザイン調整

- 「録画するエリア」は横一列の文字ボタンから、元の図・ラベル・ラジオ表示へ戻す。全画面・画面の一部はSamsungの既存Viewとクリック処理を再利用する。アプリ・ウィンドウは同じEnhancedRadioButtonで2列に配置し、独自のアプリ／単一ウィンドウの図を付ける。Samsungの画像アセットは配布APKへコピーしない。
- 選択後の対象一覧と音声範囲は同じ開始ダイアログで展開する。対象が決まるまでは開始を無効にする。
- ソフトリブートではBOOT_COUNTが変わらないため、Android標準のウィンドウ固定開始時は前回の状態表示だけに頼らず、現在のシステムプロセスへ応答を要求する。
- 設定画面の全体OFFによる無効化・設定保持を確認する内部instrumentationを追加。これは実機実行前。グラフィカルUIも実機描画・操作確認前。
