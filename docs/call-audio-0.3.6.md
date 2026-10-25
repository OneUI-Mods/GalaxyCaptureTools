# 0.3.6: 内部音声の通話音声選択

## 設定と対象範囲

`include_call_audio`は初期falseの共通スイッチ。Options、CaptureSettings、providerの既存allowlist、Material 3の共通タブ、Samsungの音声ページへ同じキーを接続する。5言語へ翻訳。Samsung画面と専用アプリは既存の更新通知で同期し、全体OFF中は設定を保持して無効化する。

録画を開始するときの対象UIDと通話音声設定を保存する。SamsungのAudioRecord再生成でも同じ選択を保つ。内部音声にはMEDIA、GAME、UNKNOWNを使い、ON時だけVOICE_COMMUNICATIONの条件を追加する。対象UIDの条件を消したり、ほかのアプリのUIDを追加したりしない。Discord／Rainのアプリ録画なら、同じUIDの通常再生音と通話再生音が候補になり、別UIDのアプリの音声は候補にならない。別プロセスでもUIDが同じなら同じ条件に従う。

Samsungはモジュール有効中の再生側をMediaProjectionの再生キャプチャへ統一し、対象アプリのみ／端末全体の両方でOFF時の通話除外を定義する。既存の早期projectionを映像側でも再利用する。マイク側のAudioRecord、純正PCM混音、録画音量、フォーマット、バッファ、codec、音声ルートは変更しない。AOSPはScreenInternalAudioRecorderの構築中に限って既存の再生キャプチャへUID・通話条件を追加する。

## フレームワークの許可範囲

通常のAudioPlaybackCaptureConfigurationにVOICE_COMMUNICATIONを足すだけでは、AudioServiceのCAPTURE_VOICE_COMMUNICATION_OUTPUT確認で拒否される。確認したSamsungキャプチャ・SystemUIのmanifestにも、その権限の要求はない。

AudioServiceのregisterAudioPolicy内に限った例外を設ける。パッケージへ権限を付与せず、ContextやPackageManager全体の判定も変更しない。次のすべてが必要。

- 対応ファームウェアで、全体スイッチと通話音声がON。
- 呼び出し元とAttributionSourceのUIDが一致し、そのUIDがSamsungキャプチャまたはSystemUIのパッケージを所有する。
- 現在の音声投影可能なMediaProjectionが存在する。
- focus・volume制御ではなく、1本だけのLOOP_BACK|RENDER／PLAYERSのmix。
- MEDIA・GAME・UNKNOWNと、モジュールのtag付きVOICE_COMMUNICATIONだけのusage条件。UID条件は任意の1本だけで、除外条件や複数UIDは不可。
- privileged media capture・call redirectionは無効。

元の登録処理をそのまま実行し、同じAudioService・呼び出しUID・BinderスレッドのCAPTURE_VOICE_COMMUNICATION_OUTPUT確認だけを許可する。終了・例外・ネスト時はThreadLocalを復元し、関係のない再登録は外側の許可を継承しない。小さいpermission helperのインライン化対策として、登録側をdeoptimizeする。元のAudioServiceがvoice flagを設定し、mixの登録・解除とprojectionのライフサイクルを管理する。

契約が解決できない場合は元の権限判定を維持する。permissionやmixの不明な構造を推測で許可しない。PCMのコピー・別ミキサー・ポーリング処理は追加しない。

## Android 16・17の照合

Android 16（AZHL）とAndroid 17（BZI9）のローカル調査資料を別々に読み、登録シグネチャ、権限確認、voice flag設定の分岐を照合した。資料の元JARハッシュとシグネチャは[契約記録](compatibility/call-audio-contracts.json)へ記録する。既存のAndroid16Hooks／Android17Hooksの許可リストと、過去の基準ソース記録は変更しない。17の未確認構造を16へフォールバックさせない。外部アプリやOSの逆コンパイル結果はevidence/device内にのみ置き、Git・配布ソースへ含めない。

## PC側の検証と未確認事項

JUnitで、UID保持、両ホストの明示ONとprojection、別パッケージ／UID偽装の拒否、経路・role・privileged／redirectionの拒否、tagとcriteriaの制限、権限の種類・service・UID・スレッド・ネスト・例外の範囲を確認する。既存分を含む66件、Lint、releaseビルドが成功。ライセンス56項目を照合した。

公開時点では実機操作禁止の指示に従い、PC側の検証のみを行った。その後、端末の利用時間をほかの作業と調整して署名済みrelease版を実機へ更新し、zygoteのソフトリブートで反映した。カーネルのboot IDが変わらないことと、Samsung・SystemUI・フレームワークが新しい設定を含むReady状態になったことを確認した。通話の実音収録、Samsungの全画面経路変更、混音・ルート変更との実機比較はまだ未確認。ロック解除後に以下の専用fixtureで確認する。

携帯回線やBluetoothなど、OSの通常の再生mixへ現れない音声は収録できない場合がある。別UIDで再生される音声を対象UIDへ付け替えない。メディアusageで流される通話や、スピーカーからマイクへ回り込む音声の内容判別はしない。通話用と分類された再生音だけの切り替えであり、会話の両側を保証する機能とは扱わない。

参照：[AOSPの音声mix判定](https://android.googlesource.com/platform/frameworks/av/+/dd833ba601e642d72da2200a2fd9c3fa1cb69d2d/services/audiopolicy/common/managerdefinitions/src/AudioPolicyMix.cpp)でもusage条件とUID条件の双方が必要となり、NO_SYSTEM_CAPTUREは別に拒否される。対象アプリの音声制限を解除する処理は追加していない。

## 実機用の内部fixture

`-PcaptureFixtures`で別UIDの音声アプリを有効にし、`:app:assembleDebugAndroidTest :audioFixture:assembleRelease`をビルドする。共有の`TonePlayback`はandroidTestとfixture専用のsource setにだけ含まれる。release APKの検査ではActivity名とfixture名前空間の両方を拒否する。CIはコンパイルのみ行い、これらのAPKを公開しない。

対象の`CaptureFixtureActivity`は`--ez tone true`でMEDIAの440 Hz、`--ez voice true`でVOICE_COMMUNICATIONの660 Hzを再生する。singleTopのIntentで後からvoiceを開始できる。別UIDの`ToneActivity`はMEDIAの880 Hz、同じvoice引数でVOICE_COMMUNICATIONの1100 Hzを再生する。別UIDのサービスは60秒で自動停止する。実際の通話への参加や個人の会話の録音は不要。

| 音声範囲 | 通話音声 | 期待する周波数 | 混入してはいけない周波数 |
| --- | --- | --- | --- |
| 対象アプリのみ | OFF | 440 | 660・880・1100 |
| 対象アプリのみ | ON | 440・660 | 880・1100 |
| 端末全体 | OFF | 440・880 | 660・1100 |
| 端末全体 | ON | 440・660・880・1100 | なし |

最初は内部音声だけで比較し、スピーカーからマイクへの回り込みを除外する。`tools/analyze_test_recording.py`に`--tones 440 660 880 1100`と時点を渡して周波数を比較する。通話音声の録画途中の開始も別途確認する。

内部の`CallAudioConfigTest`は明示した`capture_audio_driver`引数がない通常のテストではスキップする。`snapshot`、`update`、`restore`の順で使用し、元の設定は端末のアプリ専用領域に保存する。updateは音声検証に必要なキーだけを受け付け、restoreで値と型を戻す。既存の退避がある場合は上書きしない。終了時には録画とテスト音を停止し、設定・変更した端末音量を戻し、今回導入した検証用APKだけを削除する。
