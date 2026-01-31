# OpenClaw Assistant

OpenClaw専用のAndroid音声アシスタントアプリ。

## 機能

- 🎤 **ウェイクワード「OpenClaw」** - 音声だけで起動
- 🏠 **ホームボタン長押し** - システムアシスタントとして動作
- 🔄 **連続会話** - セッションを維持して自然な対話
- 🔒 **プライバシー重視** - 設定は暗号化保存

## セットアップ

### 1. アプリのインストール

Android Studioでビルドするか、Releasesからapkをダウンロード。

### 2. 設定

1. アプリを開く
2. 右上の⚙️から設定画面へ
3. 以下を入力：
   - **Webhook URL** (必須): OpenClawのエンドポイント
   - **認証トークン** (任意): Bearer認証用
   - **Picovoice Access Key**: https://console.picovoice.ai で無料取得

### 3. ウェイクワード「OpenClaw」の設定

1. [Picovoice Console](https://console.picovoice.ai) にログイン
2. **Porcupine** → **Custom Keywords**
3. 「OpenClaw」と入力してキーワード作成
4. Android用の `.ppn` ファイルをダウンロード
5. `app/src/main/assets/openclaw_android.ppn` として配置
6. アプリを再ビルド

### 4. システムアシスタントとして設定（任意）

1. 端末の設定 → アプリ → デフォルトアプリ → アシスタントアプリ
2. 「OpenClaw Assistant」を選択
3. ホームボタン長押しで起動可能に

## OpenClaw側の設定

### リクエスト形式

```json
POST /your-webhook-endpoint
Content-Type: application/json
Authorization: Bearer <token>

{
  "message": "ユーザーの発話テキスト",
  "session_id": "uuid-xxx-xxx",
  "user_id": "optional"
}
```

### レスポンス形式

以下のいずれかの形式でOK：

```json
{"response": "応答テキスト"}
{"text": "応答テキスト"}
{"message": "応答テキスト"}
```

## 技術スタック

- Kotlin + Jetpack Compose
- VoiceInteractionService
- Picovoice Porcupine (ホットワード検知)
- Android SpeechRecognizer
- TextToSpeech
- OkHttp + Gson
- EncryptedSharedPreferences

## 必要な権限

- `RECORD_AUDIO` - 音声認識
- `INTERNET` - API通信
- `FOREGROUND_SERVICE` - 常時聴取
- `POST_NOTIFICATIONS` - 通知表示

## ライセンス

MIT License
