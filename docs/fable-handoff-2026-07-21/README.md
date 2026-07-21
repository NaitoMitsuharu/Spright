# Fable review handoff — 2026-07-21

Xperia 1 VII (`XQ-FS54`, serial `HQ64CD0577`)で、Qwen3-0.6B Q4_K_Mを使用して取得した実測資料です。

## Files

- `broad-color-accuracy-xperia.json`
  - 絶対色・ブランド・食べ物・感情・伝統色・キャラクター・暗色の実機評価
  - 78/78件正解、全体p95 3ms
  - LLM経路3/3件正解、LLM推論p95 378ms
- `relative-color-model-xperia.json`
  - 現在色を基準とした相対命令12件の実機評価
  - 方向一致12/12件、LLM受理率66.7%、p95 411ms
- `color-picker-xperia.png`
  - 明るさスライダー追加後のXperia実機スクリーンショット
  - 明るさ0%ではTARGETが`#000000`になり「消灯」と表示されることを別途実機操作・Composeテストで確認済み
- `lint-results-debug.html`
  - Android lintのHTMLレポート
  - エラー0、警告39
  - 警告のうち3件はSDK由来の`libinertialmotionlib2.so`が16KBアラインされていないという内容

## Additional verification

- JVM unit tests: 66 passed, 0 failed
- Xperia Compose instrumentation tests: 8 passed, 0 failed
- Debug APK / JNI build: successful

## Review caveat

絶対色78件のうちLLMへ渡るケースは3件だけです。ルールを迂回するLLM単体の精度を主張する場合は、独立した30件以上の強制LLMケースによる追加評価が必要です。
