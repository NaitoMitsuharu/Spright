# Spright 再レビュー・改善プロンプト

あなたは **Fable 5** として、Spright Androidアプリの再レビュー、原因分析、改善設計、最終検証を担当してください。

実装が必要になった場合、可能なら **Opusサブエージェントへ実装を委任**してください。Fable 5は、要件整理、コード・実測資料の調査、設計判断、Opusへの具体的な実装指示、実装後レビュー、最終報告を担当してください。Opusの変更をそのまま承認せず、必ずFable 5自身で差分とテスト結果を再確認してください。

## 対象

- リポジトリ: `C:\Users\mirro\repository\Spright`
- Android端末: Xperia 1 VII / `XQ-FS54`
- adb serial: `HQ64CD0577`
- 現在のモデル: Qwen3-0.6B Q4_K_M
- モデル配置先: `/sdcard/Android/data/com.example.ble1507/files/models/Qwen3-0.6B-Q4_K_M.gguf`

作業ツリーには、この機能以外も含む未コミット変更があります。既存変更を破棄、巻き戻し、上書きしないでください。GradleのKotlinキャッシュ競合を避けるため、テストとlintは並列実行せず、順番に実行してください。

## 最初に読む実測資料

以下はGradleの`clean`で消えない引き渡し資料です。記載内容を信用するだけでなく、JSONの各ケースとコードを照合してください。

- 案内・実測概要: `C:\Users\mirro\repository\Spright\docs\fable-handoff-2026-07-21\README.md`
- Xperia絶対色精度レポート: `C:\Users\mirro\repository\Spright\docs\fable-handoff-2026-07-21\broad-color-accuracy-xperia.json`
- Xperia相対色LLMレポート: `C:\Users\mirro\repository\Spright\docs\fable-handoff-2026-07-21\relative-color-model-xperia.json`
- カラーピッカー実機スクリーンショット: `C:\Users\mirro\repository\Spright\docs\fable-handoff-2026-07-21\color-picker-xperia.png`
- Android lint HTMLレポート: `C:\Users\mirro\repository\Spright\docs\fable-handoff-2026-07-21\lint-results-debug.html`

## 現在確認できている結果

- JVM unit tests: 66件成功、失敗0件
- Xperia Compose instrumentation tests: 8件成功、失敗0件
- Debug APK / JNI build: 成功
- lint: エラー0件、警告39件
- 絶対色評価: 78/78件正解、全体p95 3ms
- 上記のうちLLM経路: 3/3件正解、推論p95 378ms
- 相対命令: 12/12件で方向一致
- 相対LLM受理率: 66.7%
- 相対処理p95: 411ms

今回の実機再検証で、次の追加修正が入っています。

- 「新鮮」の一文字「鮮」を“鮮やか”と誤判定してミルクを暗くする問題を修正し、「鮮やか」「鮮烈」に限定
- LLMプロンプトへ「月明かりのような青」「ネオンブルー」の具体例を追加
- カラーピッカーの明るさを0%にすると`#000000`と「消灯」になることを実機操作とComposeテストで確認

主な変更箇所:

- `app/src/main/kotlin/com/example/ble1507/QwenColorInterpreter.kt`
- `app/src/main/cpp/ble1507_qwen.cpp`
- `app/src/main/kotlin/com/example/ble1507/LedOutput.kt`
- `app/src/main/kotlin/com/example/ble1507/MainActivity.kt`
- `app/src/main/kotlin/com/example/ble1507/ExhibitionScreen.kt`
- `app/src/test/java/com/example/ble1507/DarkColorInterpretationTest.kt`
- `app/src/test/java/com/example/ble1507/PwmDutyGammaTest.kt`
- `app/src/androidTest/java/com/example/ble1507/ExhibitionScreenInstrumentedTest.kt`
- `app/src/androidTest/assets/broad_color_accuracy_cases.json`

## 再レビューで必ず確認すること

### 1. LLM精度評価が過大評価になっていないか

78件中、実際にLLMへ渡っているのは3件だけです。全体100%を「ローカルLLM精度100%」とは評価しないでください。

- 元計画どおり、ルールを強制的に迂回する日本語フレーズを最低30件用意すべきか判断する
- 自然物、感情、温度感、抽象表現、未知・珍しい物体、比喩、暗色、ネオン、ブランド・キャラクターの派生表現を含める
- 開発用ケースとholdoutケースを分離する
- テストに出た文言をプロンプトへそのまま列挙して合格させるだけの過学習を避ける
- HEX成功率、HSV許容色域、推論p50/p95、ルール/LLM別件数を分けて報告する
- LLM単体90%以上、推論p95 1秒以内を満たすか実機で確認する
- Qwen3-0.6BとQwen3.5-0.8BのA/B評価が未完なら、費用対効果を判断し、必要なら実行する

### 2. ルール・絶対色・相対色の境界

- 「基本色+修飾語」は現在色に依存しない絶対色になるか
- 「もっと」「今の色」「近づけて」など明示的アンカーがある場合だけ相対命令になるか
- 比喩表現が単純な部分文字列ルールに奪われずLLMへ渡るか
- 「新鮮」「鮮魚」など、短すぎる部分一致による別の語彙衝突が残っていないか
- 色名の長いキーが短い基本色より先に評価されるか
- LLM失敗時のフォールバックがユーザー意図から危険に外れないか
- 黒への相対変更と完全消灯が明確に区別されているか

### 3. RGB、明度、PWM、ガンマ補正

- アプリはRGB LEDの光の加法混色として色を扱えているか
- `#0000FF`と`#000033`の違いがRGB、UI、PWMすべてで保持されるか
- `duty = round(100 × (channel/255)^2.2)`が現在のBLE1507ファームウェアに適切か
- ファームウェア側で既にガンマ補正していないか、コードまたは仕様から確認できるか
- `ensureVisibleDuty`が最終色だけに適用され、黒へのフェードや中間色を壊していないか
- 1%最低発光が展示会場で有効か。根拠なく値を変更せず、実機確認が必要なら明記する
- 色変化時間が伸びたとき、ステップ数も増えて滑らかになるか

### 4. カラーピッカーUI

実機画像を必ず確認してください。

- H×Sマップと明るさスライダーで、黒、暗い有彩色、灰色、白、純色を選択できるか
- 明るさ0%の「消灯」が理解しやすいか
- CURRENT、TARGET、比較帯、スライダーの情報階層が展示UIとして自然か
- スライダーのつまみ、余白、文字サイズ、コントラスト、モーダルの高さに視覚上の問題がないか
- 既存の星空、GLBペンライト、音声UIと並べても色を使いすぎていないか
- 修正案を出す場合は、展示会での操作時間と誤操作防止を優先する

### 5. lintとネイティブライブラリ

lint警告39件を分類し、アプリ側で直すべきものと、依存関係・SDK側のものを分けてください。

特に`libinertialmotionlib2.so`の16KBアライン警告3件について:

- 現在のXperiaで動くことと、16KBページ専用端末への互換性は別問題として扱う
- APK内の実ファイルとELF情報を確認する
- アプリ側のパッケージ設定だけで直せるのか、SDK提供バイナリの再リンクが必要なのかを判断する
- 展示直前に無理な置換を行わず、必要な対処とリスクを明記する

### 6. テスト設計

- 現在のテストが実装を追認するだけになっていないか
- UIで明るさ0%→`#000000`、中間値→暗い有彩色、キャンセル→現在色維持を検証する
- RGB→PWMの境界値、非黒の丸め、最終ステップ、中間ステップ、黒フェードを検証する
- ルールの独立性、相対命令の方向、LLM失敗フォールバックを検証する
- 実機テストでレポートファイルが古い結果に上書きされ、誤って参照される危険がないか確認する

## 実装方針

まずFable 5自身でコード、資料、テスト、実機状態を調査し、問題と優先順位を提示してください。その後、必要な修正だけを明確な単位に分割し、可能ならOpusサブエージェントへ実装を依頼してください。

Opusへの指示には、少なくとも以下を含めてください。

- 変更対象と変更しない範囲
- 期待する動作
- 回帰テスト
- 実機で取得すべき指標
- 既存の未コミット変更を維持すること
- テストとlintを並列実行しないこと

実装後はFable 5自身で差分レビューを行い、必要ならOpusへ追修正を依頼してください。テストを通すためだけのハードコード、許容HSV範囲の不自然な拡張、失敗ケースの削除は禁止します。

## 最終報告形式

最後に次を明確に報告してください。

1. 調査したファイルと実測資料
2. 発見した問題を重大度順に整理
3. 採用案と不採用案、その理由
4. Fable 5が設計した内容とOpusへ委任した内容
5. 実際の変更ファイル
6. JVM、lint、ビルド、Compose実機テストの結果
7. ルール経路とLLM経路を分けた精度・p50・p95
8. Xperia実機で得られた代表的な入力、HEX、source、推論時間
9. UI変更がある場合は修正後スクリーンショット
10. 未解決事項、展示前に人間が確認すべき項目

不明点や実機でしか判断できない点は推測で成功扱いにせず、「未確認」と明記してください。
