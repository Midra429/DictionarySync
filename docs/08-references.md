# 08 出典

初回調査日: **2026-10-28（Asia/Tokyo）**。以下は当時参照した一次資料。ライブラリの採用バージョンは[バージョンカタログ](../gradle/libs.versions.toml)を参照。

## 出典台帳

| ID | URL | 確認内容と適用範囲 |
|---|---|---|
| R01 | [hyakkano IME辞書配布ページ](https://hyakkano.souhait.app/ime-dict) | 公開ページの直接ダウンロードURL、条件、利用上の注意。辞書9応答のバイト内容を別途検査 |
| R02 | [ime-dictionaries 固定コミットのREADME](https://github.com/maebahesioru/ime-dictionaries/blob/7acd49b9f94f4c8e7de56e966f2c8d2ca5e4824c/README.md) | ファイル一覧・配布者の説明・CC0表明。READMEの互換性表はAndroidアプリからの書込可否の証拠ではない |
| R03 | [UserDictionary.Words](https://developer.android.com/reference/android/provider/UserDictionary.Words) | word/shortcut/locale/frequency/APP_IDの公開契約 |
| R04 | [UserDictionaryProvider.java 固定リビジョン](https://android.googlesource.com/platform/packages/providers/UserDictionaryProvider/+/c2c6b446636f6229d2e1c4fb45b7bccd548178cd/src/com/android/providers/userdictionary/UserDictionaryProvider.java) | query/insert/update/deleteの呼出元制限（insertは当初記述を訂正）、空Cursor、APP_ID=0、表定義、ID条件付き操作。OEMへ一般化しない |
| R05 | [AOSP UserDictionary.java](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/provider/UserDictionary.java) | addWordのAPP_ID、Locale.toString、frequencyの処理。main確認時の内容 |
| R06 | [Content provider basics](https://developer.android.com/guide/topics/providers/content-provider-basics) | READ/WRITE_USER_DICTIONARYの説明。実際には権限に加えて呼び出し元（有効なIME等）の確認がある |
| R07 | [framework Manifest](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/res/AndroidManifest.xml)、[Provider Manifest](https://android.googlesource.com/platform/packages/providers/UserDictionaryProvider/+/c2c6b446636f6229d2e1c4fb45b7bccd548178cd/AndroidManifest.xml) | 旧辞書権限がnormal/removedであること、Providerのexportedと権限宣言 |
| R08 | [AOSP ContentProvider.applyBatch](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/content/ContentProvider.java) | 基底実装は操作を順番に適用。Provider側の原子性を仮定できない |
| R09 | [AOSP LatinIME UserBinaryDictionary](https://android.googlesource.com/platform/packages/inputmethods/LatinIME/+/refs/heads/main/java/src/com/android/inputmethod/latin/UserBinaryDictionary.java) | 長い語の除外処理等。Gboardの挙動・上限の証拠ではない |
| R10 | [Room releases](https://developer.android.com/jetpack/androidx/releases/room)、[DataStore releases](https://developer.android.com/jetpack/androidx/releases/datastore) | リリース情報。採用版はgradle/libs.versions.tomlを参照 |
| R11 | [Compose Material3 releases](https://developer.android.com/jetpack/androidx/releases/compose-material3)、[公式1.5.0-beta02 sources.jar](https://dl.google.com/dl/android/maven2/androidx/compose/material3/material3-android/1.5.0-beta02/material3-android-1.5.0-beta02-sources.jar) | Expressive APIとリリース情報。採用版はgradle/libs.versions.tomlを参照 |
| R12 | [WorkManager releases](https://developer.android.com/jetpack/androidx/releases/work) | リリース情報 |
| R13 | [Define work requests](https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work)、[PeriodicWorkRequest](https://developer.android.com/reference/androidx/work/PeriodicWorkRequest) | 周期・制約・backoff。Define work requests本文はAndroid CLIの公式KBからも取得 |
| R14 | [NetworkCapabilities](https://developer.android.com/reference/android/net/NetworkCapabilities)、[Network.openConnection](https://developer.android.com/reference/android/net/Network)、[Constraints.Builder](https://developer.android.com/reference/androidx/work/Constraints.Builder) | transportとmeteringの区別、Network固定、API 28以上のNetworkRequest制約と旧APIのfallback |
| R15 | [Optimize for Doze and App Standby](https://developer.android.com/training/monitoring-device-state/doze-standby) | 制約、除外の限界、直接除外を要求できる用途とPlay方針の説明 |
| R16 | [Settings](https://developer.android.com/reference/android/provider/Settings)、[ActivityManager.isBackgroundRestricted](https://developer.android.com/reference/android/app/ActivityManager#isBackgroundRestricted()) | 設定Intent、直接除外権限・package URI、API 28以上の制限検出 |
| R17 | [Window size classes](https://developer.android.com/develop/ui/compose/layouts/adaptive/use-window-size-classes) | ウィンドウ領域による幅分類と適応レイアウト |
| R18 | [Material3 Adaptive releases](https://developer.android.com/jetpack/androidx/releases/compose-material3-adaptive) | 適応レイアウトのリリース情報 |
| R19 | [Android 17 SDK setup](https://developer.android.com/about/versions/17/setup-sdk) | compileSdk/targetSdk 37。採用依存のminor要件は別検証 |
| R20 | [Auto Backup](https://developer.android.com/identity/data/autobackup) | バックアップと移行に対するデータ除外を計画する根拠 |
| R21 | [Samsung Application Management](https://developer.samsung.com/mobile/app-management.html)、[Samsung sleeping apps support](https://www.samsung.com/us/support/answer/ANS10003442/) | sleep/deep sleep/例外の影響、公開設定画面へのIntent。機種依存 |
| R22 | [CC0 1.0](https://creativecommons.org/publicdomain/zero/1.0/)、[Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0) | 外部辞書の権利表明とアプリ自身のライセンスを分離 |
| R23 | [Mozc user_dictionary_importer.cc](https://github.com/google/mozc/blob/master/src/client/user_dictionary/user_dictionary_importer.cc) | 2026-10-30確認。MS-IME・ATOK（`!!DICUT11`以降）・Gboard v1のヘッダー判定、Gboard第3列が言語であること |
| R24 | [ATOK 単語ファイルの書き方](https://www.atok.com/other/support/howtouse/mac/dc/pgs/dc_word_file.htm)、[注釈行](https://www.atok.com/other/support/howtouse/mac/dc/pgs/dc_word_file_inscoment.htm) | 2026-10-30確認。ヘッダー、品詞の後の単語コメント・自動置換・置換候補1〜5、`!!`注釈行 |
| R25 | [Apple 日本語入力 追加辞書のテキストファイル](https://support.apple.com/ja-jp/guide/japanese-input-method/jpim10226/mac) | 2026-10-30確認。`読み,単語,品詞`と`,,メモ`、引用符。文字数上限はApple側の取り込み制約 |
| R26 | [NatoBoram/PersonalDictionary](https://github.com/NatoBoram/PersonalDictionary) | 2026-10-30確認。実在するGboard v2ファイルのversion/format行、空ショートカット、言語タグ |
| R27 | [SKK辞書](https://skk-dev.github.io/dict/)、[Rime Wiki](https://github.com/rime/home/wiki)、[skk-dict/jisyo](https://github.com/skk-dict/jisyo) | 対応しない形式の判定材料（SKK見出し、Rime YAMLヘッダー） |

## アイコン

Google Material Symbols Rounded（Apache License 2.0）を使用する。Android VectorDrawableへ変換し、ランチャーアイコンではdictionaryとautorenewを加工・合成している。

- [Material Symbols](https://developers.google.com/fonts/docs/material_symbols)
- [format_list_bulleted](https://github.com/google/material-design-icons/blob/master/symbols/web/format_list_bulleted/materialsymbolsrounded/format_list_bulleted_24px.svg)
- [settings](https://github.com/google/material-design-icons/blob/master/symbols/web/settings/materialsymbolsrounded/settings_24px.svg)
- [dictionary](https://github.com/google/material-design-icons/blob/master/symbols/web/dictionary/materialsymbolsrounded/dictionary_fill1_24px.svg)
- [autorenew](https://github.com/google/material-design-icons/blob/master/symbols/web/autorenew/materialsymbolsrounded/autorenew_fill1_24px.svg)
- [同梱ライセンス本文](../app/src/main/assets/licenses/apache-2.0.txt)
