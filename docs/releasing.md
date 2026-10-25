# 配布

`python3 tools/package_release.py --source-ref <commit-or-tag>` を使用する。
release variantのみビルドし、debuggable・検証Activityの混入を検査する。
zipalign 16KiBとv3署名を検証し、ソースZIP・署名検証結果・release-metadata.json・SHA-256を生成する。公開済みアプリの署名証明書との一致も照合し、鍵の意図しない変更を拒否する。
鍵はGit管理外の`.signing/release.keystore`、alias `galaxycapture-release`。
パスワードは`.signing/password`。新規アプリのため署名ローテーションは使用しない。
外部向けリリース文に対応環境・更新手順・既知の制限を記載する。
実装詳細・テスト結果・個人の設定は開発資料へ分ける。

ライセンス一覧は`app/src/main/assets/legal/libraries.json`で管理します。依存関係を更新した場合は、解決済みのruntime componentsと照合して一覧とバージョン・本文・通知を更新してください。配布スクリプトは一覧との一致と参照ファイルの存在も検査します。

現在の配布対象はlibxposed API 102。配布スクリプトはmodern metadata、entryとscope、旧API参照とlegacy entryの混入も検査します。APIはcompileOnlyでAPKへ同梱しません。
