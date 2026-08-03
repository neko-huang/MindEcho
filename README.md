# MindEcho

**Emotion-Aware Conversation Recorder & Daily Reflection App**

MindEcho is an Android app that records daily conversations, analyzes speech emotions in real-time, and generates AI-powered daily emotional reflection reports. Designed for individuals who want to understand their emotional patterns through voice — no wearable devices required.

## Features

### Core Functionality
- **🎙️ Voice Recording** — Start/stop anytime with a foreground service that continues even when the app is in the background
- **🔊 Local Emotion Analysis** — Rule-based speech emotion recognition using acoustic features (RMS energy, zero-crossing rate, pause ratio, energy variance). Runs fully offline, no network required
- **🗣️ Speaker Diarization** — Identify *who said what* using AssemblyAI's cloud transcription API with speaker separation
- **📝 AI Conversation Summary** — Powered by DeepSeek LLM, generates structured conversation summaries
- **📊 Daily Emotional Report** — AI-generated reflection combining conversation content + emotional trajectory
- **📜 History Archive** — All recordings, transcriptions, emotion data, and reports stored locally with full-text search
- **🔒 Privacy First** — All data stored on-device by default. Cloud APIs (DeepSeek, AssemblyAI) only activate when explicitly configured by the user

### Emotion Categories
The app detects 7 emotion states from voice characteristics:

| Emotion | Voice Characteristics |
|---------|----------------------|
| 😊 Happy | High energy, fast speech rate, few pauses |
| 🤩 Excited | Very high energy, rapid speech, energetic bursts |
| 😌 Calm | Stable energy, regular rhythm, low variance |
| 😐 Neutral | Baseline state, no strong emotional indicators |
| 😰 Anxious | Moderate energy, irregular pauses, unstable rhythm |
| 😢 Sad | Low energy, slow speech, many pauses |
| 😠 Angry | High energy, unstable, frequent sudden changes |

## Technical Architecture

### Emotion Analysis Pipeline

The emotion analysis runs **locally on-device** without any ML models or network dependency:

```
Audio Recording (AAC)
    ↓
AudioFeatureExtractor
    ├── Read WAV/PCM samples (16kHz, mono, 16-bit)
    ├── Frame extraction (25ms frame, 10ms shift)
    ├── Per-frame: RMS energy + Zero-Crossing Rate (ZCR)
    └── Window aggregation (5-second windows)
            ↓
    FeatureVector per window:
    - averageEnergy (RMS)
    - energyVariance
    - averageZeroCrossingRate
    - pauseRatio (% of frames below energy threshold)
    - energyChangeRate (% of frames with >50% energy shift)
            ↓
EmotionAnalyzer (Rule-Based Engine)
    ├── Normalize energy across session (relative comparison)
    ├── Apply heuristic rules per emotion category
    ├── Score confidence for each candidate emotion
    └── Select highest-confidence emotion (threshold: 0.3)
            ↓
    EmotionResult:
    - emotionType (7 categories)
    - confidence (0.0 ~ 1.0)
    - arousal (normalized energy)
    - valence (-0.8 ~ +0.8)
    - timestamp
```

**Key acoustic features used:**

| Feature | What it captures | How it's computed |
|---------|-----------------|-------------------|
| RMS Energy | Loudness / intensity | √(Σsample²/N) per frame |
| Zero-Crossing Rate | Pitch / speech speed | Sign changes per frame / frame length |
| Pause Ratio | Hesitation / withdrawal | Frames below energy threshold / total frames |
| Energy Variance | Emotional stability | Statistical variance of energy across window |
| Energy Change Rate | Sudden shifts | Frames with >50% energy delta / total transitions |

### Speaker Diarization (Cloud)

When AssemblyAI API key is configured:

```
Recording (AAC file)
    ↓
Upload to AssemblyAI API
    ↓
Create transcript task (speaker_labels=true, language=zh)
    ↓
Poll until completed
    ↓
Parse utterances: [{speaker: "A", text: "...", start: ms, end: ms}, ...]
    ↓
Store as formatted transcript: "[Speaker A] ...\n[Speaker B] ..."
    ↓
Emotion analysis runs only on user's own utterances
```

### AI Summary & Report (Cloud)

When DeepSeek API key is configured:

```
Transcription text + Emotion data
    ↓
DeepSeek Chat API (deepseek-chat model)
    ├── Conversation summary (key topics, action items)
    └── Daily emotional report (mood trajectory, insights, suggestions)
    ↓
Store results in Room database
```

## Tech Stack

| Layer | Technology |
|-------|-----------|
| Language | Kotlin |
| UI | Jetpack Compose + Material 3 |
| Architecture | MVVM (ViewModel + Repository + DataSource) |
| Database | Room (SQLite) |
| Preferences | DataStore |
| Networking | Retrofit + OkHttp |
| Audio | Android MediaRecorder (AAC) |
| Background | Android Foreground Service |
| Navigation | Navigation Compose |
| Build | Gradle KTS + KSP |

## Project Structure

```
app/src/main/java/com/moodecho/app/
├── analysis/
│   ├── AudioFeatureExtractor.kt   # Audio feature extraction (RMS, ZCR, etc.)
│   └── EmotionAnalyzer.kt         # Rule-based emotion classification engine
├── data/
│   ├── api/
│   │   └── ApiClient.kt           # Retrofit interfaces (DeepSeek, AssemblyAI)
│   ├── db/
│   │   ├── AppDatabase.kt         # Room database definition
│   │   ├── dao/                   # Data Access Objects
│   │   └── entity/                # Database entities
│   └── repository/
│       └── RecordingRepository.kt # Data layer between UI and DB/API
├── domain/model/                  # Domain models (EmotionResult, FeatureVector, etc.)
├── service/
│   └── RecordingService.kt        # Foreground recording service
├── ui/
│   ├── screens/
│   │   ├── RecordingScreen.kt     # Main recording interface
│   │   ├── SessionDetailScreen.kt # Recording detail + transcript + emotions
│   │   ├── HistoryScreen.kt       # Historical recordings list
│   │   └── SettingsScreen.kt      # API key configuration
│   ├── theme/                     # Material 3 theme
│   └── viewmodel/                 # ViewModels
└── util/
    ├── Constants.kt               # App constants
    └── PreferenceManager.kt       # DataStore preference management
```

## Getting Started

### Prerequisites
- Android Studio Ladybug or later
- JDK 17
- Android SDK 34

### Build & Run
```bash
git clone https://github.com/neko-huang/MindEcho.git
cd MindEcho
./gradlew assembleDebug
```

The debug APK will be at `app/build/outputs/apk/debug/app-debug.apk`.

### Configuration (Optional)
1. Open the app → Settings
2. Enter your **DeepSeek API Key** for AI conversation summaries & reports
3. Enter your **AssemblyAI API Key** for speaker diarization & transcription
4. Both are optional — the app works without them (local emotion analysis only)

### Minimum Requirements
- Android 8.0 (API 26) or higher
- Microphone permission required
- No wearables or external sensors needed

## Privacy

- **All recordings and analysis data are stored locally on your device by default**
- Cloud APIs are only used when you explicitly configure API keys
- When cloud APIs are used, audio data is sent to AssemblyAI for transcription and text data to DeepSeek for summarization
- You can delete any recording and its associated data at any time
- The app does not collect, track, or share any personal data

## Roadmap

- [ ] On-device ML emotion model (replace rule-based engine with trained SER model)
- [ ] Heart rate sensor integration (wearable devices)
- [ ] Weekly/monthly emotional trend analysis
- [ ] Export reports as PDF
- [ ] Multi-language support
- [ ] Voice activity detection (VAD) to skip silence

## License

This project is developed for the [One Person Company (OPC) Challenge](https://github.com/yobimo/OPC-challenge).

---

Built with ❤️ for understanding emotions through voice.
