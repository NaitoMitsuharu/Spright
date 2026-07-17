# Local Qwen model

Place the local GGUF model here for development installs:

```text
qwen2.5-0.5b-instruct-q4.gguf
```

The model file is intentionally ignored by Git. After `gradle :app:installDebug`, Gradle pushes it to:

```text
/sdcard/Android/data/com.example.ble1507/files/models/qwen2.5-0.5b-instruct-q4.gguf
```