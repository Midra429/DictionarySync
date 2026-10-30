# リリース手順

バージョンは `app/build.gradle.kts` の `versionCode` / `versionName` で管理し、[Release workflow](../.github/workflows/release.yml)が更新します。minSdk 26、target/compileSdk 37、Room schema 1。

## ビルド・検証

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest
```

実機試験は通常アプリと分離した検証用パッケージで行います。本来のアプリに対して `connectedDebugAndroidTest` を実行するとデータを変更する可能性があります。

```sh
./gradlew -PisolatedAcceptance=true :app:connectedDebugAndroidTest
```

実Provider・公開HTTPS・スクリーンショット用の試験はオプトインで、追加の実行引数がない場合はスキップされます。[受け入れ条件](07-validation-and-plan.md)と各試験の実行引数を確認してください。

## 署名

Releaseビルドは、次の値がGradleプロパティ（`~/.gradle/gradle.properties` や `-P`）または環境変数で与えられたときだけ署名されます。与えない場合は `app-release-unsigned.apk` を生成します。鍵や秘密情報はリポジトリへ保存しないでください（`*.jks` / `*.keystore` はGit対象外）。

| 名前 | 内容 |
|---|---|
| `RELEASE_STORE_FILE` | キーストアのパス |
| `RELEASE_STORE_PASSWORD` | キーストアのパスワード |
| `RELEASE_KEY_ALIAS` | 鍵の別名 |
| `RELEASE_KEY_PASSWORD` | 鍵のパスワード |

## GitHub Releases

ビルドとリリースはActionsタブの[Release workflow](../.github/workflows/release.yml)を「Run workflow」で実行したときだけ行います。bump type（`major` / `minor` / `patch`）を選ぶと、実行したブランチで次の処理を行います。

1. `versionName` を選んだ種類で上げ、`versionCode` を1増やす。
2. 単体テスト・Lintの後に署名済みAPKを作る。
3. 変更を `Release vX.Y.Z` としてコミットし、タグ `vX.Y.Z` と一緒にpushする。
4. 「DictionarySync vX.Y.Z」というタイトルでReleaseを公開し、APKを添付する。

テストやビルドが失敗した場合はコミット・タグ・Releaseを作りません。ブランチ保護でActionsからのpushを禁止している場合は、`github-actions[bot]` のpushを許可してください。リポジトリのSecretsに以下を登録してください。

- `RELEASE_KEYSTORE_BASE64`: キーストアをBase64にした文字列（`base64 -i release.jks`）
- `RELEASE_STORE_PASSWORD`、`RELEASE_KEY_ALIAS`、`RELEASE_KEY_PASSWORD`

## 公開前に行うこと

1. 署名済みReleaseで、ソースの追加・検索・同期・無効化・削除と、未完了の診断からの復旧を確認する。
2. 対象端末・キーボードでの反映と候補表示を確認する。
3. Release workflowをbump typeを選んで実行し、公開されたReleaseの説明に確認済みの範囲を書き足す。

## 注意事項

- 電池の最適化の除外は `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` でOSの確認ダイアログを直接表示しています。Google Playではこの権限を使えるアプリの用途が制限されるため、Playで配布する場合は審査の可否を確認し、認められなければ権限を外して `BackgroundAccess` の電池の項目を `ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS`（一覧）に変えてください。
- Room schemaは公開前のため1に統合し、移行処理を持ちません。公開後に初めてschemaを変えるときから、`app/schemas` にエクスポートしたschemaを基に移行処理と移行試験を追加します。
- 診断・所有権の情報を別端末へ引き継がないため、バックアップと端末移行は無効です。
- 共有辞書へのアクセスと候補表示は端末・キーボードに依存します。確認済みの環境はSM-F971Z / Android 17 / GboardWuのみで、他端末での成功を示すものではありません。
