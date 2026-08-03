# AI Doomsday Toolbox

**An offline AI toolbox for Android that turns one phone, or a cluster of old phones, into a local AI workstation.**

Access our new wiki and read all about the app https://adt.manube.org/

Run local LLMs, Whisper transcription, image generation, distributed inference, dataset creation, offline knowledge tools, and AI-powered utilities directly on Android. The project is built for people who care about privacy, edge AI, on-device AI, distributed compute, and squeezing useful work out of old phones instead of leaving them in a drawer.

Read the web guide at [adt.manube.org](https://adt.manube.org/) for the page-by-page walkthrough.

[![Platform](https://img.shields.io/badge/Platform-Android-green.svg)](https://developer.android.com)
[![Min SDK](https://img.shields.io/badge/Min%20SDK-26-blue.svg)](https://developer.android.com)
[![License](https://img.shields.io/badge/License-Apache%202.0-yellow.svg)](LICENSE)

<p align="center">
  <img width="31%" alt="AI Doomsday Toolbox home screen" src="https://github.com/user-attachments/assets/fcaeb854-6804-4f65-ac39-de7a00b3a77b" />
  <img width="31%" alt="AI Doomsday Toolbox feature overview" src="https://github.com/user-attachments/assets/943faf10-5613-43b7-85cf-8e414a235237" />
</p>



## Why It's Different

Most Android AI apps focus on one feature. AI Doomsday Toolbox goes further: it combines offline AI on Android with distributed inference, phone-to-phone model sharing, and workflows that can reuse old phones as a low-cost Android cluster or pocket edge-compute setup.

If you are searching for a local LLM on Android, image generation, AI agents to build your projects, offline AI assistant, mobile HPC experiment, phone cluster, Android distributed compute app, or a way to reuse old phones for edge AI workloads, this project is built in that direction.

## Highlights

- Offline AI on Android with local LLM, Whisper, image generation, upscaling, and media tools
- Distributed inference features for coordinating multiple Android devices on the same network
- Built-in Ollama manager, llama native chat, and remote summary tools
- Dataset creator that turns text and PDFs into cleaned, rated instruction-answer pairs with Alpaca export
- Termux + proot tool environment with install helpers, SSH workflows, in-app webview access, and file management
- AI agent workspace with custom tools, custom agents, and project memory
- Tama virtual pet systems with adventures, farming, chat, and persistent memories
- Android share-intent support for PDFs, videos, images, and audio files

## Guide Index

- [Dashboard](#dashboard): servers, knowledge bases, file sharing, Kiwix, distributed inference
- [AI HUB](#ai-hub): AI Servers, studios, workflows, datasets, native chat, agent tools
- [Models](#models): model families, imports, downloads, exports, sharing
- [Settings](#settings): runtime controls, backups, restore, prompts, output paths
- [Organizer](#organizer): notes, calendar, alarms, widgets
- [P.E.T.](#pet): pet systems, chat, gallery, farm, adventures

## Pages From The Web Guide

### Dashboard

- The Dashboard is the control surface for the app's local services and infrastructure tools.
- It brings together server start and stop controls, knowledge bases, local file sharing, Kiwix, and distributed inference entry points.
- The file server browser can now receive uploads from connected devices too, including whole folders that stay grouped under the selected folder name, plus progress, speed, and remaining-time feedback behind a compact `+` upload picker.

### AI HUB

- The AI HUB groups the main AI work surfaces inside the app.
- It groups AI Servers, image and video generation, ONNX image generation, background removal, text to speech, audio transcription, subtitle and translation workflows, PDF tools, video summary, benchmark, dataset creation, training, Termux SSH tools, the AI agent workspace, Ollama Manager, and native llama chat.
- The browser-facing AI Servers Hub also maps Image Studio, Video Studio, Workflows, Voice Studio, Video Upscale, Docs and Datasets, and Llama Chat into local web UIs with QR and LAN access controls.

### Models

- The Models area is split by model family so you can manage LLM, Stable Diffusion, ONNX, LiteRT, Whisper, and shared model workflows separately.
- The model screens cover installed items, downloads, discovery, import, export, rename, and sharing flows.
- The LLM-side model flow also includes vision-projector handling for compatible setups.

### Settings

- The Settings area covers general app behavior, LLM runtime controls, image generation settings, Whisper settings, video upscaler settings, and system prompts.
- It also contains the backup and restore flows, including full app backup and the native chat plus Organizer ZIP transfer flow.
- It includes output-folder, acceleration, thread-count, and saved-prompt controls.

### Organizer

- The Organizer groups notes, calendar events, alarms, and their widget surfaces.
- It also connects with the native chat tooling and the ZIP transfer flow described in Settings.

### P.E.T.

- P.E.T. is the app's Tamagotchi-like subsystem with its own home screen, chat, gallery, farm, store, and adventure systems.
- The broader P.E.T. area includes dream recaps, artwork/gallery flows, room and decoration systems, and Adventure Gate.
- It also connects with the app's local AI backends and long-term saved state.

## Features

### Distributed Inference And Android Cluster Workflows

- Run distributed inference features across multiple Android devices
- Experiment with turning old phones into a low-cost phone cluster for local AI workloads
- Monitor worker/master flows from inside the app
- Share models and services over the local network for offline collaboration
<p align="center">
  <img width="31%" alt="Distributed inference screen" src="https://github.com/user-attachments/assets/4cf4ef0a-631c-4e86-b59f-1ec799ec6a5f" />
</p>


### Local AI Chat

- Chat with local LLMs on Android
- Support GGUF-based llama.cpp workflows
- Connect to llama.cpp-compatible servers and llama-server backends through a native in-app chat UI
- OpenAI-compatible local server mode on port `8080`
- Multiple model support with switching from the app interface
- Optional LAN-visible server behavior through settings when needed
- Use Ollama-compatible workflows where they fit best for your setup
- Keep inference on-device or on your own local network instead of relying on cloud chat

<p align="center">
  <img width="31%" alt="Local AI chat screen" src="https://github.com/user-attachments/assets/096752ba-3832-4020-a670-969a94acb1da" />
</p>

### Ollama Manager

- Add and manage Ollama servers from inside the app
- Pull, inspect, copy, delete, and organize models
- View and edit Modelfiles
- Create derived models without leaving the Android interface

<p align="center">
  <img width="31%" alt="Ollama manager screen" src="https://github.com/user-attachments/assets/59cb9abd-635f-4d1a-aaec-bbd566085dc0" />
</p>

### Benchmarking

- Benchmark your device for LLM workloads
- Compare thread counts to find the best number of threads for a specific model
- Save benchmark results for later reference
- Use real llama benchmarking output instead of guessing performance

<p align="center">
  <img width="31%" alt="Benchmarking screen" src="https://github.com/user-attachments/assets/131a2495-20e1-4ea7-b3f5-02801afc4881" />
</p>

### Dataset Creator

- Import `.txt` and PDF files
- Split source material into chunks for cleaner processing
- Clean chunks before question generation
- Generate five questions per chunk using neighboring chunk context for better continuity
- Generate answers, rate the pairs, and export the best entries
- Export in Alpaca JSON format
- Customize the prompts used for cleaning, question generation, answer generation, and review


### Termux Tools And Ubuntu SSH Workflows

- Connect to the Ubuntu SSH server from inside the app, with host, port, username, and password entered manually
- Follow the in-app setup help for installing `proot-distro`, provisioning Ubuntu, and enabling `sshd` inside Ubuntu on the default app port `8025`
- Install predefined tools such as Ollama, Open WebUI, Big-AGI, Oobabooga text-generation-webui, FastSDCPU, and experimental A1111 workflows with one in-app installer button per tool or the new `Install all` flow
- Open compatible tools in an in-app webview
- Manage remote files with the built-in Termux file manager
- Optionally expose each service outside `localhost` when your workflow needs LAN access
- See the fixed service port directly in the tool cards, and when LAN mode is enabled the cards also show the detected Ubuntu LAN `host:port` target for connecting outside the app

**Note:** The Termux tool installers now clone the maintained ManuXD32 forks for Big-AGI (`v2-dev`), FastSDCPU, Oobabooga/textgen, and A1111 to reduce upstream breakage. FastSDCPU installation also prepares the shared MCP runtime, so MCP no longer needs its own separate install step. A1111 / AUTOMATIC1111 support is still experimental, but the current Ubuntu installer now uses Python 3.11, mirrored Stable Diffusion dependencies, clip import verification/repair, and disables the default SD 1.5 auto-download / repeated environment prep on first launch.

<p align="center">
  <img width="31%" alt="Termux tools screen" src="https://github.com/user-attachments/assets/7b0a2e06-26b2-4829-ad73-cd29f10fd3c6" />
</p>


### AI Agent Workspace

- Run an AI coding/workflow agent environment powered by Termux and Ollama-compatible backends
- Create custom tools and custom agents
- Keep project-specific workspace memory and task context
- Build reusable automation flows around your own projects

<p align="center">
  <img width="31%" alt="AI agent workspace screen" src="https://github.com/user-attachments/assets/cab59f56-eb7f-4d2c-9619-e479c2a8ec8e" />
  <img width="31%" alt="AI agent session screen" src="https://github.com/user-attachments/assets/5b026fbb-7bc7-4478-9f18-e5f0b7142815" />
</p>


### PDF, Video, And Summary Tools

- Extract text from PDFs with OCR fallback when needed
- Summarize PDFs, videos, and transcription workflows
- Use Ollama and/or llama.cpp-compatible remote backends for summary generation
- Tune prompts, context limits, output length, and related summary parameters per workflow

<p align="center">
  <img width="31%" alt="PDF summary screen" src="https://github.com/user-attachments/assets/a360e96d-b887-4b35-bf67-99df7d12b5bb" />
  <img width="31%" alt="Remote summary backend screen" src="https://github.com/user-attachments/assets/bc79cd17-20a7-41f4-a56f-fc33becbc10b" />
  <img width="31%" alt="Summary output screen" src="https://github.com/user-attachments/assets/5ec398cc-c178-4388-a4c3-802b4edb93a9" />
</p>



### Audio, Video, And Subtitle Tools

- Transcribe audio and video with Whisper
- Support multiple languages and model sizes from lightweight to larger accuracy-focused options
- Summarize video content after transcription
- Extract audio from videos with FFmpeg as part of summarization/transcription workflows
- Burn subtitles into video with styling controls such as font, color, and position
- Process media directly from Android share intents


### Image And Video Generation

- Generate images with Stable Diffusion workflows directly on Android
- Generate local `txt2vid` and `img2vid` clips with `stable-diffusion.cpp` video models
- Tune per-run `stable-diffusion.cpp` caching for `txt2img`, `img2img`, `txt2vid`, and `img2vid` with raw cache options, SCM policy, and SCM mask support
- Tune video prompts with optional negative prompts, selectable sampling methods, `--cfg-scale`, optional `--flow-shift`, and a `480x832` default output size
- Manage FLUX diffusion models with a dedicated `vid_gen` badge so video-capable models are easy to spot
- Save generated videos as AVI + MP4 with metadata, and mirror them to your chosen output folder under `Generated videos`
- Browse a dedicated video gallery with `txt2vid` / `img2vid` badges, prompt details, sharing, deletion, and copyable generation info

### Image Generation And Upscaling

- Generate images with Stable Diffusion workflows directly on Android
- Includes SD 1.5, SD 2.1, SDXL, and FLUX-oriented workflows
- Adjustable generation settings such as steps, CFG scale, dimensions, sampler, seed, tiling, and per-run diffusion caching
- Upscale images and videos with RealESRGAN-based tools
- Multiple scale factors are available depending on the selected model
- Built-in scrollable option guides explain the image and video generation controls and note which features are powered by `stable-diffusion.cpp`
- Use FastSDCPU in Termux/proot workflows for additional image-generation setups
- Experiment with A1111-style web UI workflows through the Termux tools area

<p align="center">
  <img width="31%" alt="Image generation screen" src="https://github.com/user-attachments/assets/e624aee3-8533-4345-a610-f8931ab89ee7" />
</p>

### Offline Knowledge, Sharing, And Utilities

- Browse offline knowledge bases with Kiwix and ZIM file support
- Download ZIM files through the catalog or import them from internal storage
- Built-in Kiwix server support on port `8888`
- Optional LAN access so other devices on your network can connect to shared content
- Share models and ZIM files over LAN with web UI and QR-based connection flows
- Export shared files to storage when needed
- Create and manage notes with Markdown support
- Automatic note creation for summaries and transcription workflows
- Use Android share intents to send PDFs, videos, images, and audio into the app’s processing flows

### Tama Virtual Pet

- Raise a Tamagotchi-like pet inside the app
- Go on AI-generated adventures
- Work, farm, and interact across multiple gameplay systems
- Talk to your pet and build memories over time
- Discover different personalities and long-term companionship mechanics
- Let your pet dream, paint, and keep growing with you over long play sessions


## Built With

### Core AI And Media Projects

- [llama.cpp](https://github.com/ggerganov/llama.cpp)
- [whisper.cpp](https://github.com/ggerganov/whisper.cpp)
- [stable-diffusion.cpp](https://github.com/leejet/stable-diffusion.cpp)
- [FFmpeg](https://ffmpeg.org)
- [Kiwix-tools](https://github.com/kiwix/kiwix-tools)
- [Real-ESRGAN](https://github.com/xinntao/Real-ESRGAN)

### Compatible And Integrated Tooling

- [Ollama](https://github.com/ollama/ollama)
- [Open WebUI](https://github.com/open-webui/open-webui)
- [Big-AGI](https://github.com/ManuXD32/big-AGI/tree/v2-dev)
- [Oobabooga text-generation-webui](https://github.com/ManuXD32/textgen)
- [FastSDCPU](https://github.com/ManuXD32/fastsdcpu)
- [AUTOMATIC1111 / stable-diffusion-webui](https://github.com/ManuXD32/stable-diffusion-webui)
- [EasyDataset](https://github.com/ConardLi/easy-dataset)
- [Termux](https://github.com/termux)

### Android Stack

- Kotlin with Jetpack Compose for the UI
- Room for local persistence
- NanoHTTPD for embedded local servers
- ZXing for QR code generation
- ML Kit for OCR
- Apache PDFBox for PDF handling

### Architecture

- Native binaries built for `arm64-v8a`
- Foreground services for long-running AI tasks
- Unified notification flows for background processing
- SAF-based file handling for imports and exports

## Getting Started

### Join The Google Play Beta

If you want the easiest install path, you can join the Google Play beta here:

[AI Doomsday Toolbox on Google Play](https://play.google.com/store/apps/details?id=com.manuxd32.aidoomsdaytoolbox)

The Google Play version uses an Android App Bundle, so the installation is usually smaller than downloading a universal package manually. Joining the beta also helps a lot by improving testing coverage, surfacing device-specific issues, and making it easier to validate updates before wider releases.

### Requirements

- Android 8.0+ (API 26)
- `arm64-v8a` device
- More RAM and storage if you plan to run larger local models
- Additional devices on the same network if you want to experiment with distributed inference and phone-cluster workflows

### Build From Source

```bash
git clone https://github.com/ManuXD32/AI-Doomsday-Toolbox.git
cd AI-Doomsday-Toolbox

./gradlew assembleDebug
```

For release bundles, the project expects Java 21 and the usual signing environment variables:

```bash
KEYSTORE_PATH=/absolute/path/to/your-release.keystore \
JAVA_HOME=/usr/lib/jvm/java-21-openjdk \
KEYSTORE_PASSWORD=your_keystore_password \
KEY_PASSWORD=your_key_password \
./gradlew :app:bundleRelease
```

### Models And Content

Models and large offline content are managed separately inside the app. The project includes workflows for downloading, importing, and organizing models and ZIM content based on the feature you want to use.

## Contributing

Contributions are welcome. If you want to improve a feature, fix a bug, or help with documentation, pull requests are appreciated.

## Support

If this project helps you, you can support development here:

- [Ko-fi](https://ko-fi.com/L3L61QAJ1S)
- [PayPal](https://paypal.me/ManuelG815)

## Disclaimer

This project is provided as-is, without any guarantee that it will be error-free, fit for a particular purpose, or safe for every workflow. You are responsible for how you use it, including any commands, model workflows, remote connections, generated output, or automation built on top of it.

The author is not responsible for misuse of the app, data loss, device issues, network exposure, third-party tool behavior, or damage caused by incorrect configuration, generated content, or user actions.

## License

This project is licensed under the [Apache License 2.0](LICENSE).

## Author

**ManuXD32** - [GitHub](https://github.com/ManuXD32)
