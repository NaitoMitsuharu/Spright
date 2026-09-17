# Model files

GGUFはこのディレクトリやGitへ配置しません。リポジトリ直下の`README.md`に従い、`prepareColorModel`または`installDebug`を実行してください。

モデルはGradleユーザーキャッシュへダウンロードされます。APKのビルド時に選択モデルをAPKへ同梱し、初回起動時にアプリのexternal files `models/`へ自動展開します。

通常の開発インストール:

```powershell
.\gradlew.bat :app:installDebug
```

このコマンドは、モデルのダウンロード、APKへの同梱、インストール、初回起動までを行います。モデルを指定する場合は `-PcolorModel=qwen3.5-0.8b` を追加してください。

既存のモデル転送方式も利用できるため、同梱しないAPKから手動で配置する場合は `prepareColorModel` と `adb push` を使用できます。
