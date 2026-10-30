# DictionarySync

公開HTTPSの辞書ファイルを定期取得し、端末の共有ユーザー辞書へ一方向に同期するAndroidアプリです。取得した単語はオフラインで閲覧・検索できます。

> An Android app that periodically downloads public dictionary files (Mozc/Gboard/ATOK/MS-IME TSV, Apple plist/CSV) and syncs them one-way into the system user dictionary. The UI is Japanese only.

- **動作環境**: Android 8.0（API 26）以上。ユーザー辞書への反映は端末・キーボードに依存します。
- **入手**: [Releases](../../releases)
- **資料**: [仕様](docs/README.md) / [リリース手順](docs/release.md)

## 使い方

1. 「ソース」画面の「ソースを追加」で名前とURL（https://）を入力します。形式・言語・文字コードは自動判定、同期間隔の初期値は24時間です。
2. ソースをタップすると単語を閲覧・検索できます。同期・編集・削除は各ソースの「その他の操作」から行います。
3. スイッチで自動同期とユーザー辞書への反映を切り替えます。長押しで一括操作できます。

| 用語 | 意味 |
| --- | --- |
| 取得 | 配布元からダウンロードして保存 |
| 反映 | 端末のユーザー辞書へ書き込み |
| 同期 | 取得＋反映 |

- 取得や編集が失敗しても、旧設定と旧データは保持されます。
- 自動同期を遅らせないため、設定の「バックグラウンド実行」で電池の最適化から除外してください。

### ユーザー辞書への反映（初回のみ）

閲覧だけなら不要です。設定の「端末の対応診断」で次を行います。

1. OSのキーボード一覧でDictionarySyncを有効化（普段のキーボードは切り替え不要）
2. 診断画面の確認語（`dictionarysync_check_…`）をOSの日本語ユーザー辞書へショートカットなしで登録
3. 「読み取りを確認」「書き込みを確認」を実行し、ソース画面から同期を再試行

> [!IMPORTANT]
> 確認語は同期を使う間、削除・編集しないでください。

IMEサービスは入力内容を読み取り・保存・送信しません。

## 仕様と制約

| 項目 | 内容 |
| --- | --- |
| 対応形式 | IME共通TSV、Microsoft TSV、ATOK、Gboard v1/v2、XML plist、Apple CSV（手動指定） |
| 文字コード | UTF-8、UTF-16LE/BE、Windows-31J（手動指定） |
| 非対応 | SKK、Rime、圧縮ファイル、認証、配布ページからのリンク抽出 |
| 上限 | 20ソース／1ソース100,000項目・20 MiB／反映合計200,000件 |
| 同期間隔 | 1・6・12・24時間、3日、7日（WorkManager。OSにより遅れる場合あり） |

- 「Wi-Fiのみで取得」は手動同期にも適用し、VPN利用中は待機します。
- 手動登録語・他ソースの語・外部で編集された語は削除しません。
- 確認済み環境は SM-F971Z / Android 17 / GboardWu のみです。他環境の動作報告は[Issues](../../issues)へ。

## 開発

Android SDK 37、minSdk 26。

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:assembleRelease
```

- Debug: `app/build/outputs/apk/debug/app-debug.apk`
- Release: `app/build/outputs/apk/release/app-release-unsigned.apk`（[署名](docs/release.md#署名)）

### 実機試験

既存データを守るため、別applicationIdの検証用パッケージで実行します（本来のアプリに `connectedDebugAndroidTest` は使わないでください）。

```sh
./gradlew -PisolatedAcceptance=true :app:assembleDebug :app:assembleDebugAndroidTest
```

実Providerへ書き込むテストは追加引数が必要で、指定しなければスキップされます。

画面確認用の合成データ投入:

```sh
./gradlew -PisolatedAcceptance=true :app:installDebug :app:installDebugAndroidTest
adb shell am instrument -w -e seedScreenshots 1 -e class me.midra.dictionarysync.library.ScreenshotFixture \
  me.midra.dictionarysync.acceptance.test/androidx.test.runner.AndroidJUnitRunner
```

## ライセンス

[Apache License 2.0](LICENSE)（[APK同梱版](app/src/main/assets/licenses/apache-2.0.txt)は設定の「ライセンス」から表示できます）。

アイコンはGoogleの[Material Symbols Rounded](https://developers.google.com/fonts/docs/material_symbols)とMaterial Icons（Apache License 2.0）を使用しています。出典は[資料一覧](docs/08-references.md)、編集用SVGは[artwork](artwork/app-icon.svg)にあります。
