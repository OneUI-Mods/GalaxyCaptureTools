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

- 利用者の指定に合わせ、Galaxy開始画面へ追加する検索・一覧・音声範囲もSamsungのContextと標準部品へ統一。音声範囲には既存の音声選択と同じEnhancedRadioButtonを使い、Materialの別テーマを混在させない。設定アプリはMaterial 3を維持する。
- 設定画面の全体スイッチ階層のinstrumentationは実機で成功（1件）。

## 音声の検証素材

`-PcaptureFixtures`でのみ別UIDの880Hz音声テストAPKをビルドする。アプリ本体へは依存させず、配布手順にも含めない。androidTest側の映像fixtureはtone=trueで440Hzを再生する。対象のみで440Hz、端末全体で440Hzと880Hzの両方を収録できることをスペクトルで比較する予定。別UIDの音は60秒で自動停止する。

SamsungのDisplayManagerServiceは通常の逆コンパイル結果で条件が逆転していた。fallbackの分岐を確認し、launch cookieが存在する場合にcreateTaskSessionへ進むことを確認した。

## 対象一覧の実機修正

ログからActivityRecord.findMainWindowの引数不一致を確認。このビルドにはfindMainWindow(boolean)だけが存在するため、一覧取得・固定対象の初期化・再生成時の取得をすべてfalse付きへ統一した。元の無引数呼び出しでは一覧取得が中止されていた。

## Samsung実機で確認できた動作

- findMainWindow(false)への修正後、開始画面の対象一覧取得・検索・明示選択・カウントダウンからのアプリ録画に成功。dumpsysで対象TaskとUIDを確認した。
- 同一TaskのFixture Aから赤一色のFixture Bへ遷移。アプリ録画の96フレーム中36フレームにBを確認。ウィンドウ録画は18フレームすべてにBを含まず、選択ウィンドウの表示を保持した。Samsungは静止画面を可変フレーム間隔で保存するため、解析では指定時刻以前の最後のフレームを調べる。
- 別UIDの880Hzサービスが動作中の比較を行った。端末全体では440Hzと880Hzのピークが約719、対象のみでは440Hzが約719、880Hzは0.003未満。対象の音声を残したまま別アプリの音声を除外できた。
- 一覧取得中に検索すると、応答後にフィルターがリセットされる問題を発見。検索文字列を状態に保持するよう修正した（Samsung側の再起動のみで反映可能）。

## 固定ウィンドウの回転中の再生成

Android標準のタスク録画＋ウィンドウ固定＋回転で、WindowStateのフレームが一時的に空になり、Empty recording geometryによって保護停止することを実機で検出した。空の期間は投影を隠し、同じActivityRecordのメインウィンドウの再レイアウトを待つ。以後のsurface配置で有効な寸法へ戻った時だけ座標変換を再適用する。別Activityへ対象を差し替えることはしない。保護停止後のセッション終了が「Ready」で上書きされる表示も修正した。

## 修正版の最終確認

- Android標準のタスク録画＋固定ウィンドウで縦→横→逆横を実施。録画は継続し、同じ90度補正を保持。保存動画の横向きの四隅に色付きの映像が入り、同一アプリの別Activityへ移ると黒画面へ移行した。別UIDの880Hz再生中でも、440Hzが約508、880Hzは0.003未満だった。
- Galaxyの固定ウィンドウでも縦→横→逆横を実施。1080×2340を保持し、横・逆横とも同じ四隅の並びを確認。保護停止は発生しなかった。
- 横開始・黒帯補正OFF・180度無視ONでGalaxy録画を実施。2340×1080で持ち替え前後の四隅が一致した。
- 設定画面の全体OFF、設定値の保持、黒帯補正OFF中でも180度設定を操作できることのinstrumentationを再実行し成功（1件）。20件の単体テストとLintも成功。
- 内部の映像fixture、別UIDの音声fixture、コンソール用dexと一時スクリーンショットを実機から除去した。録画データは削除しない。機能設定は検証前の値へ戻し、録画が終了していることを確認。
- 指定ウィンドウのroot surfaceを投影するため、付属する子ウィンドウまで分離する方式ではない。保護フラグの継承を崩すbuffer layerへの単純な差し替えは行わない。付属ポップアップの制限を利用者向け資料にも記載した。
