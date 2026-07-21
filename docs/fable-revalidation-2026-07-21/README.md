# Spright Fable再レビュー後の実機検証

実行日: 2026-07-21

端末: Xperia 1 VII / XQ-FS54 / Android 16 / adb serial `HQ64CD0577`

## 結論

- Fableが指摘した本番とテストの経路差を解消した。78件を`current`なし/ありで実行した本番相当156件は97.44%。ルール75件は両モードとも100%、LLM 3件は33.33%。
- 相対LLMはraw出力がほぼ定数で最終結果への実効的寄与がなかったため、本番は決定的な相対ルールへ直結した。12/12方向一致、ルール経路率100%、p95 1ms。
- ルールを必ず迂回する独立36件では、全モデルが目標の色域合格率90%に未達。未合格モデルを採用せず、配布デフォルトはQwen3-0.6Bのまま。
- カラーマップのマーカーを内側へクランプし、明度スクリムを追加し、Image/Canvasを角丸16dpでクリップした。
- JVM 67件、Compose 10件、相対実機1件、本番色実機1件、LLM実機1件は成功。lintはerror 0 / warning 39。

## LLM単体36件

| モデル | HEX | HSV合格率 | p50 | p95 | rule leak |
|---|---:|---:|---:|---:|---:|
| Qwen3-0.6B Q4_K_M | 100% | 41.67% | 349ms | 559ms | 0 |
| Qwen3.5-0.8B Q4_0 | 100% | 27.78% | 2,257ms | 3,182ms | 0 |
| Qwen2.5-1.5B Q4_K_M（調査候補） | 100% | 69.44% | 391ms | 542ms | 0 |
| Qwen2.5-3B Q4_K_M（調査候補） | 100% | 63.89% | 1,007ms | 1,080ms | 0 |

3Bのraw JSONはAndroidTest APKを1.5B用のまま再利用したため、JSON内`model`だけが`qwen2.5-1.5b`と誤表示されている。アプリAPK・実ロードパス・推論時間は3Bである。この候補は精度と速度の双方で1.5Bに負けたため、表示だけを直す再実行は行わなかった。

Qwen2.5調査候補の公式配布元と検証値:

- 1.5B: revision `91cad51170dc346986eccefdc2dd33a9da36ead9`、size 1,117,320,736、SHA-256 `6a1a2eb6d15622bf3c96857206351ba97e1af16c30d7a74ee38970e434e9407e`
- 3B: revision `cc1e68eea5f05f88f41a6de1fc73110178f23715`、size 2,104,932,768、SHA-256 `626b4a6678b86442240e33df819e00132d3ba7dddfe1cdc4fbb18e0a9615c62d`

## ファイル

- `broad-color-production-xperia.json`: 本番相当156件
- `relative-color-production-rule-xperia.json`: 相対命令12件
- `llm-color-*.json`: ルール非該当36件のモデル別raw結果
- `screen-home-final.png`: 展示ホーム画面
- `screen-color-picker-final.png`: 白選択時のマーカーと角丸
- `screen-color-picker-selected-final.png`: 有彩色選択時
- `lint-results-debug.html` / `.xml`: 最終lint

## 未達・展示前の注意

- LLM単体90%は未達。アプリ全体97.44%を「LLM精度」と説明しない。
- 本番経路のLLM 3件のうち、`パンプキンカラー`と`月明かりのような青`が`#000000`になった。この実測後、意味解釈LLMの完全黒は失敗としてBLEへ送らない安全ガードを追加した。黒・消灯の明示命令はルール経路で処理する。
- `libinertialmotionlib2.so`の4KB LOAD alignmentはSDK再リンクが必要。Xperia展示には影響しないがPlay配布前に提供元へ確認する。
- LEDファーム側のガンマ有無と会場照明下の1% duty視認性は、実機目視で確認する。
