# 🛠️ AI Doomsday Toolbox

**Your offline AI survival companion for Android.** Run powerful AI models entirely on-device with no internet required. Perfect for when the world ends... or just when you want privacy.

[![Android](https://img.shields.io/badge/Platform-Android-green.svg)](https://developer.android.com)
[![Min SDK](https://img.shields.io/badge/Min%20SDK-26-blue.svg)](https://developer.android.com)
[![License](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

## ✨ Features

<table>
  <tr>
    <td><img src="https://github.com/user-attachments/assets/615b97dc-9a63-4d27-90b5-6e4444350ff0" width="200"/></td>
    <td><img src="https://github.com/user-attachments/assets/95fef9c7-dbff-494e-9a8e-34797402b8f2" width="200"/></td>
    <td><img src="https://github.com/user-attachments/assets/dd8ad945-66cc-4d63-89b3-4621b3582cad" width="200"/></td>
    <td><img src="https://github.com/user-attachments/assets/a2022fab-70a2-498d-bf2d-631b903948da" width="200"/></td>
  </tr>
</table>

- Send videos, PDFs, images and audio files to the app through share intents to process them

### 🤖 AI Chat
- Chat with Large Language Models (LLMs) completely offline
- Support for GGUF model format
- OpenAI-compatible server mode on port **8080**
- Multiple model support with easy switching
- Support for LAN visible server through settings

<img src="https://github.com/user-attachments/assets/01a98c6d-f45c-4ec7-abdb-2d6c36067ba1" width="300"/>

### 🎨 Image Generation
- Generate images from text prompts
- **Supported models:**
  - Stable Diffusion (SD 1.5, SD 2.1, SDXL)
  - FLUX (schnell, dev) (very slow)
- Adjustable parameters (steps, CFG scale, dimensions, tiling)
- Export generated images to Output folder

<table>
  <tr>
    <td><img src="https://github.com/user-attachments/assets/9f169dfa-8e3c-4301-83fa-0c1f41679888" width="250"/></td>
    <td><img src="https://github.com/user-attachments/assets/858856c9-05b8-4285-a446-fb8c5e922106" width="250"/></td>
  </tr>
</table>

### 🎙️ Audio Transcription
- Transcribe audio and video files to text using Whisper
- Export transcriptions to Output folder
- Support for multiple languages
- Various model sizes (tiny, base, small, medium, large)

<img src="https://github.com/user-attachments/assets/8e64591e-111b-47e7-9c29-bff786de7734" width="300"/>

### 🎬 Video Summarization  
- Extract audio from videos
- Transcribe and summarize video content
- Uses FFmpeg for audio extraction

<img src="https://github.com/user-attachments/assets/aba2846b-e30b-4f0a-a585-5b57db2347f9" width="300"/>

### 📄 PDF Tools
- Extract text from PDFs
- Summarize documents with AI
- OCR support for scanned documents
- Much more

<table>
  <tr>
    <td><img src="https://github.com/user-attachments/assets/7a11a764-622c-4232-ae66-3cd5f8439390" width="250"/></td>
    <td><img src="https://github.com/user-attachments/assets/cd7cf6ca-0e3f-42f9-87ca-eb22469802d7" width="250"/></td>
  </tr>
</table>

### 🖼️ Image and video Upscaling
- Upscale images with RealESRGAN
- Multiple scale factors (2x, 3x, 4x)
- High-quality AI enhancement
- Export image and videos to Output folder

<table>
  <tr>
    <td><img src="https://github.com/user-attachments/assets/846244e5-0cda-46a4-80b3-18cc58b341ac" width="250"/></td>
    <td><img src="https://github.com/user-attachments/assets/cf3333df-7396-4fd3-b703-d2831e90eb3b" width="250"/></td>
  </tr>
</table>

### 📚 Offline Wikipedia (Kiwix)
- Browse Wikipedia without internet
- ZIM file support (download them through the catalog or import them from internal storage)
- Built-in Kiwix server on port **8888**
- Access from any device on your network

<table>
  <tr>
    <td><img src="https://github.com/user-attachments/assets/0618ac08-07f1-4b62-b345-274170254e2e" width="200"/></td>
    <td><img src="https://github.com/user-attachments/assets/96639c84-1afb-4d87-b8e9-addd91ad9501" width="200"/></td>
    <td><img src="https://github.com/user-attachments/assets/847eda4f-c346-4898-9440-2334fd2f02f5" width="200"/></td>
  </tr>
</table>

### 📤 Model & File Sharing
- Share AI models over LAN
- Web UI for downloading models
- QR codes for easy connection
- ZIM file sharing for offline content
- Export models and zim files to internal storage

<table>
  <tr>
    <td><img src="https://github.com/user-attachments/assets/51b7bdf2-e84b-4726-b23d-1e1acef0fbf0" width="150"/></td>
    <td><img src="https://github.com/user-attachments/assets/c3d64778-fdce-40b5-b4d7-108be49de4d4" width="150"/></td>
    <td><img src="https://github.com/user-attachments/assets/14c5c464-8b6b-4520-a6ae-ec11d15252aa" width="150"/></td>
  </tr>
  <tr>
    <td><img src="https://github.com/user-attachments/assets/a71b4664-2662-4c4e-9de8-80e4f2a2e130" width="150"/></td>
    <td><img src="https://github.com/user-attachments/assets/8956c59a-dadb-4bfc-bf05-80a5e70c3a2a" width="150"/></td>
    <td><img src="https://github.com/user-attachments/assets/8621d42d-e207-449e-a6c4-03ae9ad04f27" width="150"/></td>
  </tr>
</table>

### 📝 Notes
- Create and manage notes
- Automatic note creation for PDF and video summaries and for transcriptions
- Markdown support

<img src="https://github.com/user-attachments/assets/acb50b21-6079-42ed-9abc-925c64325b73" width="300"/>

## 🏗️ How It Was Built

Built with the help of Antigravity

This app integrates several native C++ AI inference engines into an Android app using JNI and native binaries:

### Core Technologies
- **[llama.cpp](https://github.com/ggerganov/llama.cpp)** - LLM inference
- **[whisper.cpp](https://github.com/ggerganov/whisper.cpp)** - Audio transcription
- **[stable-diffusion.cpp](https://github.com/leejet/stable-diffusion.cpp)** - Image generation
- **[FFmpeg](https://ffmpeg.org)** - Video/audio processing
- **[Kiwix-tools](https://github.com/kiwix/kiwix-tools)** - Offline Wikipedia
- **[RealESRGAN](https://github.com/xinntao/Real-ESRGAN)** - Image upscaling

### Android Stack
- **Kotlin** with Jetpack Compose for UI
- **Room Database** for local data persistence
- **NanoHTTPD** for embedded HTTP servers
- **ZXing** for QR code generation
- **ML Kit** for OCR
- **Apache PDFBox** for PDF handling

### Architecture
- Native binaries compiled for **arm64-v8a**
- Foreground services for long-running AI tasks
- Unified notification system for background processes
- Store Access Framework (SAF) for file management

## 🚀 Getting Started

### Requirements
- Android 8.0+ (API 26)
- arm64-v8a device (most modern Android phones)
- 4GB+ RAM recommended
- Storage space for AI models

### Building from Source

```bash
# Clone the repository
git clone https://github.com/ManuXD32/AI-Doomsday-Toolbox.git
cd AI-Doomsday-Toolbox

# Build with Gradle (requires Java 17+)
./gradlew assembleDebug

# Install on device
adb install app/build/outputs/apk/debug/app-debug.apk
```

### Download Models

Models are downloaded separately from within the app. The app includes a curated model catalog with popular options for each task.

## 🤝 Contributing

Contributions are welcome! Please feel free to submit a Pull Request.

## 💖 Support the Developer

If you find this app useful, consider supporting development:

- ☕ [Ko-fi](https://ko-fi.com/L3L61QAJ1S)
- 💳 [PayPal](https://paypal.me/ManuelG815)

## License

This project is licensed under the Apache License, Version 2.0.

## 👨‍💻 Author

**ManuXD32** - [GitHub](https://github.com/ManuXD32)

---

*Built with ❤️ for the AI apocalypse*
