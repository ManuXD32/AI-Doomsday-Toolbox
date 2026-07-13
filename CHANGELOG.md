# Changelog

All notable user-facing changes are documented here in English and Spanish.

## [0.946] - 2026-07-12

Baseline: latest public GitHub release `v0.945` from June 18, 2026.

APK artifact: fill in the final sideload APK filename, timestamp, and size during release upload.

### English

#### Added

- Expanded llama.cpp speculative decoding controls in LLM Settings, including grouped strategy selection for draft-simple, draft-mtp, draft-dflash, ngram-mod, ngram-simple, ngram-map-k, ngram-map-k4v, and ngram-cache modes, plus per-mode parameter inputs and saved speculative run history.
- A redesigned AI Hub with a centralized tool catalog, search, grouped categories, pinned tools, pinned chats, and per-tool settings sheets for shared AI workflows.
- A restyled Settings Hub that now matches the shared hub shell and keeps General, LLM/runtime defaults, Prompts, Debug/Logs, and About as the main app-wide settings entry points.
- More explicit LiteRT model capability and routing controls, including editable vision/audio support flags, per-server Whisper-audio preference, and fuller saved backend URLs for Live Translator templates.

#### Changed

- Manga and PDF translation quality was upgraded with stronger Japanese OCR cleanup, better same-bubble grouping, chunk retry/recovery, page-aware correction, translated-image export fallback, and translated CBZ/PDF rendering that preserves the original page artwork more reliably.
- LiteRT runtime handling now retains engines for reuse, isolates GPU work in the protected worker process, namespaces compiled caches by backend/context/MTP mode, unloads idle engines after the retention window, and quarantines crashing GPU model/device pairs before falling back to CPU in Auto mode.
- Live Translator now preserves full backend URLs in templates, keeps URL drafts while editing, supports LiteRT thinking persistence, and exposes clearer backend/audio-routing behavior across llama-server, llama-swap, Ollama, and LiteRT.
- The AI Servers browser layer and bundled Web UI were refined with a calmer responsive layout, descriptor-driven controls, task retry/cancel handling, better upload progress, user-scoped web chat/provider state, and chat-first tooling panels for browser Llama Chat.
- Native chat and llama-server command generation now surface the newer speculative decoding flags more accurately, including upstream-style `--spec-type` handling, MTP-specific fallback behavior, and clearer generated runtime argument structure.
- Release packaging and runtime compatibility were updated again, including newer native payload metadata, refined LiteRT/OpenCL packaging rules, and additional migrations for translator, LiteRT, server-audio, and web-chat storage.

#### Compatibility

- App database schema is now `87`.
- Tama database schema is now `43`.
- New migrations since `v0.945` include newer Live Translator template fields, LiteRT model capability metadata, server audio-routing preferences, AI server web ownership/tool-event storage, and later release-stage app/Tama storage updates.
- Users upgrading from older `0.945`-era installs should keep their data through migrations, but a backup is still recommended before installing `0.946` because translation, LiteRT, AI server, and database state all changed again.

### Español

#### Añadido

- Controles ampliados de decodificación especulativa de llama.cpp en Ajustes LLM, incluyendo selección agrupada de estrategias para draft-simple, draft-mtp, draft-dflash, ngram-mod, ngram-simple, ngram-map-k, ngram-map-k4v y ngram-cache, además de parámetros por modo e historial guardado de ejecuciones especulativas.
- Un AI Hub rediseñado con catálogo centralizado de herramientas, búsqueda, categorías agrupadas, herramientas fijadas, chats fijados y hojas de ajustes por herramienta para flujos compartidos de IA.
- Un Settings Hub rediseñado que ahora coincide con el shell compartido de los hubs y mantiene General, valores por defecto de LLM/runtime, Prompts, Debug/Logs y About como entradas principales de ajustes globales.
- Controles más explícitos para capacidades y enrutado de modelos LiteRT, incluyendo flags editables de soporte de visión/audio, preferencia por servidor para usar Whisper con audio y URLs completas guardadas en las plantillas del Traductor en vivo.

#### Cambiado

- La calidad de traducción de Manga y PDF mejoró con limpieza OCR japonesa más fuerte, mejor agrupación de bocadillos, reintentos y recuperación por fragmentos, corrección contextual por página, fallback de exportación basada en imágenes y renderizado de CBZ/PDF traducido que preserva mejor el arte original de cada página.
- El manejo del runtime LiteRT ahora retiene engines para reutilización, aísla el trabajo GPU en el worker protegido, separa las caches compiladas por backend/contexto/modo MTP, descarga engines inactivos tras la ventana de retención y pone en cuarentena combinaciones modelo/dispositivo GPU que fallan antes de caer a CPU en modo Auto.
- El Traductor en vivo ahora conserva URLs completas de backend en las plantillas, mantiene borradores de URL mientras se editan, guarda el estado de thinking de LiteRT y expone con más claridad el comportamiento de backend y enrutado de audio entre llama-server, llama-swap, Ollama y LiteRT.
- La capa web de AI Servers y su Web UI integrada se pulieron con un diseño responsive más limpio, controles guiados por descriptores, reintento/cancelación de tareas, mejor progreso de subidas, estado por usuario para chats/proveedores web y paneles de herramientas orientados al chat para Llama Chat en navegador.
- El chat nativo y la generación de comandos de llama-server ahora reflejan mejor los nuevos flags de decodificación especulativa, incluyendo manejo estilo upstream de `--spec-type`, fallback específico de MTP y una estructura más clara de argumentos runtime generados.
- El empaquetado de release y la compatibilidad del runtime se actualizaron otra vez, incluyendo metadatos más nuevos de binarios nativos, reglas más afinadas de empaquetado LiteRT/OpenCL y migraciones adicionales para traductor, LiteRT, audio por servidor y almacenamiento web-chat.

#### Compatibilidad

- La base de datos principal ahora usa el esquema `87`.
- La base de datos Tama ahora usa el esquema `43`.
- Las nuevas migraciones desde `v0.945` incluyen campos más nuevos para plantillas del Traductor en vivo, metadatos de capacidades LiteRT, preferencias de enrutado de audio por servidor, almacenamiento de propiedad y eventos de herramientas del chat web de AI Servers, y otras actualizaciones posteriores del almacenamiento principal y Tama.
- Quien actualice desde instalaciones antiguas de la era `0.945` debería conservar sus datos mediante migraciones, pero sigue siendo recomendable hacer una copia de seguridad antes de instalar `0.946` porque la traducción, LiteRT, AI Servers y el estado de base de datos volvieron a cambiar.

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
