# 配布

`python3 tools/package_release.py --source-ref <commit-or-tag>` を使用する。
release variantのみビルドし、debuggable・検証Activityの混入を検査する。
zipalign 16KiBとv3署名を検証し、ソースZIPとSHA-256を生成する。
鍵はGit管理外の`.signing/release.keystore`、alias `galaxycapture-release`。
パスワードは`.signing/password`。新規アプリのため署名ローテーションは使用しない。
外部向けリリース文に対応環境・更新手順・既知の制限を記載する。
実装詳細・テスト結果・個人の設定は開発資料へ分ける。
