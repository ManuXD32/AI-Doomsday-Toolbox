# Changelog

All notable user-facing changes are documented here in English and Spanish.

## [0.942] - 2026-05-27

Baseline: latest public GitHub release `v0.938` from May 1, 2026.

APK artifact: fill in the final sideload APK filename, timestamp, and size during release upload.

### English

#### Added

- Native Llama Call mode with foreground voice capture, Whisper fallback transcription, backend generation, TTS playback, call status, and hang-up controls.
- Live Translator with bilingual speaker setup, templates, saved sessions, language sampling, Whisper transcription, TTS playback, timing controls, and Llama/Ollama/LiteRT backend support.
- AI Servers Hub infrastructure with server configs, users, permissions, sessions, artifacts, web chat storage, tool events, and bundled web UI assets.
- Media translation/dubbing workflows for audio and video, subtitle translation, optional subtitle burn-in, batch processing, pausing/resuming, foreground notifications, and output gallery management.
- PDF translation workflows for OCR/exported PDFs and existing searchable PDFs, with multiple backends and optional page screenshot context.
- Manga/Comic Translation for CBZ batches, with OCR, page-level translation, translated PDF export, translated CBZ export, progress, and per-file results.
- Knowledge Bases with named collections, PDF/TXT/MD/note imports, embedding/indexing progress, content summaries, selected-base chat tools, citation links, and Deep Research integration.
- ONNX Background Removal with batch image input, transparent PNG output, optional masks, runtime controls, metadata, a dedicated BgR gallery, and a native chat tool path.
- Quadtrix Trainer WebUI/worker support for local Qwen3 training, dataset/profile management, checkpoint resume, GGUF export, generated-model management, distributed worker mode, telemetry, and logs.
- LiteRT model management for `.litertlm` packages, including catalog downloads, import/export, display-name renames, deletion, capability flags, Hugging Face token support, and backend doctor diagnostics.
- P.E.T./Tama dialog workbook generation, Adventure Gate assets/content, farm drones/fuel/livestock polish, P.E.T. widgets, and expanded gameplay tests.
- Native chat tools for unified image generation, ONNX background removal, image saving, and direct audio handling where supported.

#### Changed

- Upgraded LiteRT-LM runtime dependency to `0.12.0`.
- LiteRT chat now uses protected worker isolation for GPU runs, CPU fallback/quarantine behavior, structured tool calls, streamed message handling, image/audio multimodal turns, and clearer diagnostics.
- Native chat can auto-check and auto-start local llama-server backends before sending a localhost chat turn.
- PDF translation supports richer backend choices, including LiteRT, and can use page screenshots as vision context when available.
- PDF, Manga, media, and subtitle workflows now use stronger foreground progress, batch messaging, and resumable/background-owned execution where appropriate.
- Release packaging and verification were tightened with focused feature-size scanning, Gradle caching, and parallel Gradle execution.
- Native binary payloads were refreshed across llama.cpp, media, Kiwix, and Snapdragon/OpenCL-related packages.

#### Compatibility

- App database schema is now `79`.
- Tama database schema is `40`.
- New migrations cover Live Translator tables, AI Server Hub tables, LiteRT model metadata, knowledge-base summaries, and related feature storage.
- Users upgrading from very old builds should make a backup before installing this APK because many storage and native-runtime systems changed across recent releases.

### Español

#### Añadido

- Modo Llama Call nativo con captura de voz en primer plano, transcripción fallback con Whisper, generación por backend, reproducción TTS, estado de llamada y controles para colgar.
- Traductor en vivo con configuración de dos hablantes, plantillas, sesiones guardadas, muestreo de idioma, transcripción con Whisper, reproducción TTS, controles de tiempo y soporte para backends Llama/Ollama/LiteRT.
- Infraestructura del AI Servers Hub con configuraciones de servidor, usuarios, permisos, sesiones, artefactos, almacenamiento de chat web, eventos de herramientas y assets web incluidos.
- Flujos de traducción/doblaje multimedia para audio y video, traducción de subtítulos, grabado opcional de subtítulos, procesamiento por lotes, pausa/reanudación, notificaciones en primer plano y gestión de galería de salidas.
- Flujos de traducción PDF para PDFs OCR/exportados y PDFs buscables existentes, con varios backends y contexto opcional de capturas de página.
- Traducción Manga/Cómic para lotes CBZ, con OCR, traducción por página, exportación a PDF traducido, exportación a CBZ traducido, progreso y resultados por archivo.
- Bases de conocimiento con colecciones nombradas, importación de PDF/TXT/MD/notas, progreso de embeddings/indexación, resúmenes de contenido, herramientas de chat por base seleccionada, citas e integración con Deep Research.
- Eliminación de fondo ONNX con entrada de imágenes por lote, salida PNG transparente, máscaras opcionales, controles runtime, metadatos, galería BgR dedicada y ruta de herramienta para chat nativo.
- Entrenador Quadtrix con WebUI/worker para entrenamiento local Qwen3, gestión de datasets/perfiles, reanudación de checkpoints, exportación GGUF, gestión de modelos generados, modo worker distribuido, telemetría y logs.
- Gestión de modelos LiteRT para paquetes `.litertlm`, incluyendo descargas del catálogo, importación/exportación, renombrado visible, borrado, flags de capacidades, token de Hugging Face y diagnósticos de backend doctor.
- Generación del workbook de diálogos P.E.T./Tama, assets/contenido de Adventure Gate, drones/combustible/ganado en la granja, widgets P.E.T. y más tests de gameplay.
- Herramientas de chat nativo para generación unificada de imágenes, eliminación de fondo ONNX, guardado de imágenes y manejo de audio directo cuando el backend lo soporta.

#### Cambiado

- Actualizado el runtime LiteRT-LM a `0.12.0`.
- El chat LiteRT ahora usa aislamiento en worker protegido para GPU, fallback/cuarentena hacia CPU, tool calls estructuradas, streaming mejorado, turnos multimodales con imagen/audio y diagnósticos más claros.
- El chat nativo puede comprobar y arrancar automáticamente backends llama-server locales antes de enviar un turno a localhost.
- La traducción PDF admite más backends, incluido LiteRT, y puede usar capturas de página como contexto visual cuando esté disponible.
- Los flujos PDF, Manga, media y subtítulos ahora tienen mejor progreso en primer plano, mensajes por lote y ejecución reanudable o gestionada en segundo plano cuando corresponde.
- El empaquetado y la verificación de release se ajustaron con escaneo enfocado de tamaño de features, cache de Gradle y ejecución paralela de Gradle.
- Se actualizaron payloads de binarios nativos para llama.cpp, multimedia, Kiwix y paquetes relacionados con Snapdragon/OpenCL.

#### Compatibilidad

- La base de datos principal ahora usa el esquema `79`.
- La base de datos Tama usa el esquema `40`.
- Las nuevas migraciones cubren tablas del Traductor en vivo, tablas del AI Servers Hub, metadatos de modelos LiteRT, resúmenes de bases de conocimiento y almacenamiento relacionado.
- Quien actualice desde builds muy antiguas debería hacer una copia de seguridad antes de instalar este APK, porque muchos sistemas de almacenamiento y runtime nativo han cambiado en las últimas releases.
