# Android 17の互換性記録

- 対象候補: Android 17 / API 37、SM-S948Q、ファームウェアS948QOPU1BZI9。
- 状態: バージョン分離の準備済み。ROM・実機のフック契約は解析中です。
- `Android17Hooks`は独立したプロファイルです。実装と動作を確認するまではフックを許可しません。16の許可条件を広げて17へ流用しません。
- 今後はAPK／frameworkのハッシュ、クラス・メソッド・引数・フィールド・呼び出し関係、実機で確認した項目をこの記録へ追記します。
- ビルドの成功、フック登録の成功、画面上の機能確認は別々に記録します。

## 分離準備の検証

Android 16の許可条件の維持、17から16へフォールバックしないこと、未対応SDKを暗黙に有効化しないことを確認しました。既存分を含むJVMテスト57件、release Lint、releaseビルドが通過しています。この段階のAPKは端末へインストールしていません。17の機能確認済みという意味ではありません。

## BZI9 ROMとの照合（2026-10-20）

Samsungキャプチャ6.2.44.29 / 624429000、SystemUI、framework、servicesをROMから抽出しました。SDK 37、ビルド`CP2A.260605.016.S948QOPU1BZI9`です。版とSHA-256は[android17-rom.json](android17-rom.json)へ保存しています。systemパーティションのfingerprintは実機の値と区別しています。

| 処理 | Android 16 | Android 17で確認した変更 |
| --- | --- | --- |
| 対象選択のSamsungダイアログ | Builder `M4.c`、生成`f()` | Builder `androidx.recyclerview.widget.g2`、生成`h()`へ変更。`(Context,int)`とパラメータの`b/a/d/i/o`を照合 |
| 録画チップのライフサイクル | `CollapsedStatusBarFragment` | Fragmentが存在せず、`SamsungOngoingCastController.setChipView(View)`と`updateAll(String)`を使用する17専用処理を準備 |
| WMSのスクリーンショット | 6引数 | 最後に`SurfaceControl[]`が加わり7引数。現在のフックが変更するbooleanの位置（5）は同じ |
| 黒帯補正 | `ContentRecorder.updateMirroredSurface(Transaction,Rect,Point)` | 同じシグネチャを確認。実際の回転と180度の挙動は未確認 |

ダイアログと録画チップは17用の処理を追加し、16のFragment経路は残しています。17のチップは再配置中のdetachと実際の破棄を区別し、繰り返しbindと復元を考慮しています。親Viewの構造・幅・タップ・停止操作は実機での確認が必要です。

ROM照合後のJVMテスト58件、release Lint、releaseビルドが通過しました。これは17の画面・録画動作を確認した結果ではありません。17のフック許可は引き続き無効です。

## 残る確認

- Samsung/AOSP開始UI、対象タスク固定、音声対象の絞り込み、メディアとマイクの分岐と録画音量。
- チップの親コンテナ、録画停止、回転時のオーバーレイ除外、カウントダウンとスクリーンショットUI。
- 設定アプリとの同期、機能OFF時の復元、クラッシュ保護の誤検知。
- キャプチャの許可・投影・録画範囲の17における実際のデータ経路。

TailADBの接続先は接続拒否のため、実機確認・インストールは未実施です。TailADBの更新は保留し、端末・SystemUI・ホームを再起動していません。
