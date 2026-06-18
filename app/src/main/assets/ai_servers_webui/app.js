const STRINGS = {
  en: {
    eyebrow: "AI Tool Server",
    subtitle: "Run this toolbox from your browser, phone, or laptop.",
    loginTitle: "Private access",
    loginBody: "Sign in with a server user to continue.",
    username: "Username",
    password: "Password",
    signIn: "Sign in",
    logout: "Log out",
    controls: "Controls",
    engine: "Engine",
    mode: "Mode",
    startJob: "Start task",
    sendMessage: "Send message",
    status: "Status",
    gallery: "Gallery",
    tasks: "Tasks",
    server: "Server",
    access: "Access",
    uploaded: "Uploaded",
    started: "Task queued",
    noModels: "No choices available",
    noJobs: "No tasks yet",
    noGallery: "No saved outputs yet",
    publicMode: "Public",
    usersMode: "Users",
    open: "Open",
    copyUrl: "Copy URL",
    download: "Download",
    ready: "Ready",
    loading: "Loading options...",
    upload: "Upload",
    uploading: "Uploading",
    taskQueued: "Queued",
    taskRunning: "Running",
    taskComplete: "Complete",
    taskFailed: "Failed",
    taskCancelled: "Cancelled",
    removeTask: "Remove",
    cancelTask: "Cancel",
	    clearFailed: "Clear failed",
	    retryTask: "Retry",
	    failReason: "Reason",
	    removeGallery: "Remove",
	    confirmRemoveGallery: "Remove this gallery item?",
	    resetPrompt: "Reset prompt",
	    createChat: "New chat",
    renameChat: "Rename",
    deleteChat: "Delete",
    saveProvider: "Save provider",
    provider: "Provider",
    model: "Model",
    providerName: "Name",
    providerEngine: "Engine",
    providerUrl: "URL",
    providerLiteRt: "LiteRT model",
    providerVision: "Vision",
    providerAudio: "Audio",
    providerMtp: "LiteRT MTP",
    providerAccelerator: "LiteRT accelerator",
    providerRefreshModels: "Refresh models",
    generation: "Generation",
    context: "Context",
    maxTokens: "Max tokens",
    maxOutputTokens: "Max output tokens",
    temperature: "Temperature",
    thinking: "Thinking",
    topP: "Top P",
    topK: "Top K",
    repeatPenalty: "Repeat penalty",
    chat: "Chat",
    image: "Image",
    audio: "Audio",
    document: "Document",
    webChatName: "Web chat",
    webProviderName: "Web provider",
	    uploadFailed: "Upload failed",
	    attach: "Attach",
	    removeAttachment: "Remove attachment",
	    recordAudio: "Record audio",
	    stopRecording: "Stop recording",
	    recording: "Recording...",
	    dropFiles: "Drop files here",
	    stopGenerating: "Stop",
	    providerSettings: "Provider settings",
	    toolsSettings: "Tools",
	    toolUsage: "Tool usage",
	    hideToolUsage: "Hide tools",
	    clearToolUsage: "Clear tool log",
	    noToolUsage: "No tool calls yet",
	    toolRunning: "Running",
	    toolComplete: "Complete",
	    toolFailed: "Failed",
	    messageComplete: "Finished",
	    messageFailed: "Failed",
	    messageRunning: "Generating",
	    generationSettings: "Generation settings",
	    copyMessage: "Copy",
	    editMessage: "Edit",
	    removeMessage: "Remove",
	    regenerateMessage: "Regenerate",
	    continueMessage: "Continue",
	    saveMessage: "Save",
	    cancelEdit: "Cancel",
	    confirmRemoveMessage: "Remove this message?",
	    loadingChat: "Loading chat...",
	    modelRefreshWarning: "Model refresh failed. You can still edit the URL or type a model manually.",
	    manualModel: "Type a model name",
	    searchLimits: "Search limits",
	    organizerTools: "Organizer",
	    knowledgeTools: "Knowledge",
	    imageTools: "Image tools",
	    backgroundTools: "Background removal",
	    voiceTools: "Voice and call",
	    maxPages: "Max pages",
	    maxChars: "Max chars",
	    sourceLimit: "Source limit",
	    selectedKnowledgeBases: "Knowledge base IDs",
	    ttsLanguage: "TTS language",
	    ttsVoice: "TTS voice",
	    ttsSteps: "TTS steps",
	    ttsSpeed: "TTS speed",
	    messagePlaceholder: "Ask anything...",
	    emptyChatMessage: "Write a message or attach a file.",
	    micPermissionDenied: "Microphone permission was denied",
	    message: "Message",
    attachments: "Attachments",
    noMessages: "No messages yet",
    assistant: "Assistant",
    user: "You",
    videoInfo: "Video",
    finalSize: "Final output",
    modelAdvice: "Open this card in the Android app once first if the upscaler models still need to be downloaded.",
	    modelRefreshFailed: "Could not refresh models"
  },
  es: {
    eyebrow: "Servidor de herramienta IA",
    subtitle: "Usa esta caja de herramientas desde el navegador, el movil o el portatil.",
    loginTitle: "Acceso privado",
    loginBody: "Inicia sesion con un usuario del servidor para continuar.",
    username: "Usuario",
    password: "Contrasena",
    signIn: "Entrar",
    logout: "Salir",
    controls: "Controles",
    engine: "Motor",
    mode: "Modo",
    startJob: "Iniciar tarea",
    sendMessage: "Enviar mensaje",
    status: "Estado",
    gallery: "Galeria",
    tasks: "Tareas",
    server: "Servidor",
    access: "Acceso",
    uploaded: "Subido",
    started: "Tarea en cola",
    noModels: "No hay opciones disponibles",
    noJobs: "Aun no hay tareas",
    noGallery: "Aun no hay resultados guardados",
    publicMode: "Publico",
    usersMode: "Usuarios",
    open: "Abrir",
    copyUrl: "Copiar URL",
    download: "Descargar",
    ready: "Listo",
    loading: "Cargando opciones...",
    upload: "Subir",
    uploading: "Subiendo",
    taskQueued: "En cola",
    taskRunning: "En curso",
    taskComplete: "Completada",
    taskFailed: "Fallida",
    taskCancelled: "Cancelada",
    removeTask: "Quitar",
    cancelTask: "Cancelar",
	    clearFailed: "Limpiar fallidas",
	    retryTask: "Reintentar",
	    failReason: "Motivo",
	    removeGallery: "Quitar",
	    confirmRemoveGallery: "Quitar este elemento de la galeria?",
	    resetPrompt: "Restablecer prompt",
	    createChat: "Nuevo chat",
    renameChat: "Renombrar",
    deleteChat: "Borrar",
    saveProvider: "Guardar proveedor",
    provider: "Proveedor",
    model: "Modelo",
    providerName: "Nombre",
    providerEngine: "Motor",
    providerUrl: "URL",
    providerLiteRt: "Modelo LiteRT",
    providerVision: "Vision",
    providerAudio: "Audio",
    providerMtp: "LiteRT MTP",
    providerAccelerator: "Acelerador LiteRT",
    providerRefreshModels: "Actualizar modelos",
    generation: "Generacion",
    context: "Contexto",
    maxTokens: "Tokens maximos",
    maxOutputTokens: "Tokens max salida",
    temperature: "Temperatura",
    thinking: "Razonamiento",
    topP: "Top P",
    topK: "Top K",
    repeatPenalty: "Penalizacion repeticion",
    chat: "Chat",
    image: "Imagen",
    audio: "Audio",
    document: "Documento",
    webChatName: "Chat web",
    webProviderName: "Proveedor web",
	    uploadFailed: "No se pudo subir",
	    attach: "Adjuntar",
	    removeAttachment: "Quitar adjunto",
	    recordAudio: "Grabar audio",
	    stopRecording: "Detener grabacion",
	    recording: "Grabando...",
	    dropFiles: "Suelta archivos aqui",
	    stopGenerating: "Detener",
	    providerSettings: "Ajustes del proveedor",
	    toolsSettings: "Herramientas",
	    toolUsage: "Uso de herramientas",
	    hideToolUsage: "Ocultar herramientas",
	    clearToolUsage: "Limpiar registro",
	    noToolUsage: "Aun no hay llamadas a herramientas",
	    toolRunning: "En curso",
	    toolComplete: "Completada",
	    toolFailed: "Fallida",
	    messageComplete: "Finalizado",
	    messageFailed: "Fallido",
	    messageRunning: "Generando",
	    generationSettings: "Ajustes de generacion",
	    copyMessage: "Copiar",
	    editMessage: "Editar",
	    removeMessage: "Quitar",
	    regenerateMessage: "Regenerar",
	    continueMessage: "Continuar",
	    saveMessage: "Guardar",
	    cancelEdit: "Cancelar",
	    confirmRemoveMessage: "Quitar este mensaje?",
	    loadingChat: "Cargando chat...",
	    modelRefreshWarning: "No se pudieron actualizar los modelos. Puedes editar la URL o escribir el modelo manualmente.",
	    manualModel: "Escribe un modelo",
	    searchLimits: "Limites de busqueda",
	    organizerTools: "Organizador",
	    knowledgeTools: "Conocimiento",
	    imageTools: "Herramientas de imagen",
	    backgroundTools: "Quitar fondo",
	    voiceTools: "Voz y llamada",
	    maxPages: "Paginas maximas",
	    maxChars: "Caracteres maximos",
	    sourceLimit: "Limite de fuentes",
	    selectedKnowledgeBases: "IDs de bases de conocimiento",
	    ttsLanguage: "Idioma TTS",
	    ttsVoice: "Voz TTS",
	    ttsSteps: "Pasos TTS",
	    ttsSpeed: "Velocidad TTS",
	    messagePlaceholder: "Pregunta lo que quieras...",
	    emptyChatMessage: "Escribe un mensaje o adjunta un archivo.",
	    micPermissionDenied: "Permiso de microfono denegado",
	    message: "Mensaje",
    attachments: "Adjuntos",
    noMessages: "Aun no hay mensajes",
    assistant: "Asistente",
    user: "Tu",
    videoInfo: "Video",
    finalSize: "Salida final",
    modelAdvice: "Abre antes esta tarjeta en la app Android si todavia hay que descargar los modelos de escalado.",
    modelRefreshFailed: "No se pudieron actualizar los modelos"
  }
};

const storedLanguage = localStorage.getItem("adt-ai-server-lang");
const browserLanguage = (navigator.language || "en").startsWith("es") ? "es" : "en";
const serverType = document.body.dataset.serverType;
const serverName = document.body.dataset.serverName;
const serverEmoji = document.body.dataset.serverEmoji;
const $ = (id) => document.getElementById(id);

const state = {
  lang: storedLanguage || browserLanguage,
  health: null,
  options: null,
  jobs: [],
  gallery: [],
  chat: null,
  files: {},
	  mediaInfo: {},
	  formCache: {},
	  chatAttachments: [],
	  activeChatJobId: null,
	  mediaRecorder: null,
	  recordingChunks: [],
	  recordingStream: null,
	  chatAutoScroll: true,
	  forceChatScrollToBottom: false,
	  providerModelsCache: {},
	  providerModelsStatus: {},
	  activeChatId: null,
	  activeProviderId: null,
	  loadingChatId: null,
	  showToolUsage: false,
	  chatWasBusy: false,
	  toolsDrawerOpen: false,
	  openToolSections: {}
	};

const t = (key) => STRINGS[state.lang][key] || STRINGS.en[key] || key;

document.addEventListener("DOMContentLoaded", () => {
  document.body.classList.toggle("chat-mode", serverType === "llama_chat");
  $("serverName").textContent = serverName;
  $("serverEmoji").textContent = serverEmoji;
  $("languageSelect").value = state.lang;
  $("languageSelect").addEventListener("change", (event) => {
    state.lang = event.target.value;
    localStorage.setItem("adt-ai-server-lang", state.lang);
    applyLanguage();
    renderAll();
  });
  document.querySelectorAll(".tab").forEach((button) => {
    button.addEventListener("click", () => activateTab(button.dataset.tab));
  });
  $("loginForm").addEventListener("submit", login);
  $("logoutButton").addEventListener("click", logout);
  $("jobForm").addEventListener("submit", startJob);
  $("engine").addEventListener("change", () => {
    populateModesForEngine();
    renderControls();
  });
  $("mode").addEventListener("change", renderControls);
  applyLanguage();
  refreshAll();
  setInterval(refreshDynamic, 2500);
});

function applyLanguage() {
  document.documentElement.lang = state.lang;
  document.querySelectorAll("[data-i18n]").forEach((node) => {
    node.textContent = t(node.dataset.i18n);
  });
  $("startButton").textContent = serverType === "llama_chat" ? t("sendMessage") : t("startJob");
  $("modeHint").textContent = state.options ? currentModeHint() : t("loading");
}

async function requestJson(path, options = {}) {
  const response = await fetch(path, {
    headers: { "Content-Type": "application/json", ...(options.headers || {}) },
    credentials: "same-origin",
    ...options
  });
  const json = await response.json().catch(() => ({ ok: false, error: response.statusText }));
  if (response.status === 401) {
    $("loginCard").classList.remove("hidden");
    $("logoutButton").classList.add("hidden");
  }
  if (!response.ok || json.ok === false) {
    throw new Error(json.error || response.statusText);
  }
  return json;
}

async function refreshAll() {
  try {
    state.health = await requestJson("/api/health");
    $("loginCard").classList.add("hidden");
    $("logoutButton").classList.toggle("hidden", state.health.accessMode !== "USERS");
    state.options = await requestJson("/api/options");
    if (serverType === "llama_chat") {
      state.chat = await requestJson("/api/chat");
      syncActiveChatState();
    }
    setupControls();
    await refreshDynamic();
    renderAll();
  } catch (error) {
    showToast(error.message);
  }
}

async function refreshDynamic() {
  try {
    const [jobs, gallery] = await Promise.all([
      requestJson("/api/jobs"),
      requestJson("/api/gallery")
    ]);
	    state.jobs = jobs.jobs || [];
	    state.gallery = gallery.artifacts || [];
	    if (serverType === "llama_chat" && state.options) {
	      const chatId = state.activeChatId || fieldValue("chatId");
	      state.chat = await requestJson(`/api/chat${chatId ? `?chatId=${encodeURIComponent(chatId)}` : ""}`);
	      syncActiveChatState(false);
	      const busy = isChatBusy();
	      if (!busy && state.activeChatJobId) state.activeChatJobId = null;
	      if (busy !== state.chatWasBusy || state.loadingChatId) {
	        state.chatWasBusy = busy;
	        state.loadingChatId = null;
	        renderControls();
	      }
	    }
    renderStatus();
    renderJobs();
    renderGallery();
    renderChatHistory();
  } catch (error) {
    if (!String(error.message).includes("Unauthorized")) showToast(error.message);
  }
}

function syncActiveChatState(allowServerOverride = true) {
  if (serverType !== "llama_chat" || !state.chat) return;
  const chats = state.chat.chats || [];
  const providers = state.chat.providers || [];
  const serverChatId = state.chat.activeChatId || optionValue(chats[0] || {});
  if (allowServerOverride || !chats.some((chat) => String(chat.id) === String(state.activeChatId))) {
    state.activeChatId = serverChatId || state.activeChatId;
  }
  const activeChat = chats.find((chat) => String(chat.id) === String(state.activeChatId));
  const serverProviderId = activeChat?.providerId || state.chat.activeProviderId || optionValue(providers[0] || {});
  if (allowServerOverride || !providers.some((provider) => String(provider.id) === String(state.activeProviderId))) {
    state.activeProviderId = serverProviderId || state.activeProviderId;
  }
  if (state.activeChatId) state.formCache.chatId = String(state.activeChatId);
  if (state.activeProviderId) state.formCache.providerId = String(state.activeProviderId);
}

function setupControls() {
  const engines = state.options?.engines || [];
  const engineValues = engines.map((engine) => engine.id);
  const preferredEngine = normalizeSelectionValue(resolveSimpleValue("engine"), engineValues, true);
  $("engine").innerHTML = engines.map((engine) =>
    `<option value="${escapeAttr(engine.id)}">${escapeHtml(labelText(engine.label))}</option>`
  ).join("");
  if (preferredEngine) $("engine").value = preferredEngine;
  state.formCache.engine = $("engine").value;
  populateModesForEngine();
  renderControls();
}

function populateModesForEngine() {
  const currentEngine = $("engine").value;
  const modeIds = (state.options?.engines || []).find((engine) => engine.id === currentEngine)?.modes || [];
  const modes = (state.options?.modes || []).filter((mode) => modeIds.includes(mode.id));
  const preferredMode = normalizeSelectionValue(resolveSimpleValue("mode"), modes.map((mode) => mode.id), false);
  $("mode").innerHTML = modes.map((mode) =>
    `<option value="${escapeAttr(mode.id)}">${escapeHtml(labelText(mode.label))}</option>`
  ).join("");
  if (preferredMode) {
    $("mode").value = preferredMode;
  } else if (modes.length) {
    $("mode").value = modes[0].id;
  }
  state.formCache.mode = $("mode").value;
}

function renderControls() {
  snapshotFormState();
  $("startButton").textContent = serverType === "llama_chat" ? t("sendMessage") : t("startJob");
  if (serverType === "llama_chat") {
    $("controlsHost").innerHTML = renderChatControls();
    wireUploads();
    wireChatControls();
    $("modeHint").textContent = currentModeHint();
    renderChatHistory();
    return;
  }

  const action = currentAction();
  const fields = fieldsForAction(action);
  const defaults = state.options?.defaults?.[action] || {};
  const grouped = groupFields(fields.filter(fieldVisibleClient));
  const chunks = [];
  if (serverType === "video_upscale") {
    chunks.push(`<div class="info-banner">${escapeHtml(t("modelAdvice"))}</div><div id="videoInfoPanel"></div>`);
  }
  Object.entries(grouped).forEach(([section, sectionFields]) => {
    chunks.push(`
      <fieldset class="field-section">
        <legend>${escapeHtml(section)}</legend>
        <div class="form-grid">${sectionFields.map((field) => renderField(field, resolveFieldValue(field, defaults[field.id]))).join("")}</div>
      </fieldset>
    `);
  });
  $("controlsHost").innerHTML = chunks.join("");
  wireUploads();
  wireDependentControls();
  renderVideoInfoPanel();
  $("modeHint").textContent = currentModeHint();
}

function groupFields(fields) {
  return fields.reduce((acc, field) => {
    const section = field.section || labelText(currentMode()?.label) || t("controls");
    acc[section] = acc[section] || [];
    acc[section].push(field);
    return acc;
  }, {});
}

function renderField(field, value) {
  if (!fieldVisibleClient(field)) return "";
  if (field.id === "denoise" && serverType === "video_upscale") {
    const model = selectedUpscalerModel();
    if (model && !model.supportsDenoise) return "";
  }
  const id = escapeAttr(field.id);
  const label = escapeHtml(labelText(field.label));
  const normalizedValue = value ?? "";
	  const required = field.required ? "required" : "";
	  if (field.type === "textarea") {
	    const resetButton = isResettablePrompt(field)
	      ? `<button class="ghost reset-prompt" data-target="${id}" type="button">${t("resetPrompt")}</button>`
	      : "";
	    return `<label class="prompt-field"><span>${label}${resetButton}</span><textarea id="${id}" rows="5" data-default-value="${escapeAttr(field.default ?? "")}" ${required}>${escapeHtml(normalizedValue)}</textarea></label>`;
	  }
  if (field.type === "checkbox") {
    const checked = Boolean(normalizedValue) ? "checked" : "";
    return `<label class="check-field"><input id="${id}" type="checkbox" ${checked} /><span>${label}</span></label>`;
  }
  if (field.type === "file") {
    const multiple = field.multiple ? "multiple" : "";
    const pathControl = field.multiple
      ? `<textarea id="${id}" class="path-input" rows="3" ${required}>${escapeHtml(normalizedValue)}</textarea>`
      : `<input id="${id}" class="path-input" value="${escapeAttr(normalizedValue)}" ${required} />`;
    return `
      <label class="file-field">
        <span>${label}</span>
        ${pathControl}
        <div class="upload-row">
          <input id="file_${id}" type="file" ${multiple} ${field.accept ? `accept="${escapeAttr(field.accept)}"` : ""} />
          <button class="secondary upload-button" data-target="${id}" type="button">${t("upload")}</button>
        </div>
        <div id="upload_progress_${id}" class="upload-progress hidden"><div></div><span>0%</span></div>
      </label>
    `;
  }
  if (field.type === "model" || field.type === "select" || field.type === "upscalerModel") {
    return `<label><span>${label}</span><select id="${id}" ${required}>${optionsForField(field, normalizedValue)}</select></label>`;
  }
  const numberAttrs = field.type === "number"
    ? `${field.min !== undefined ? ` min="${escapeAttr(field.min)}"` : ""}${field.max !== undefined ? ` max="${escapeAttr(field.max)}"` : ""}${field.step !== undefined ? ` step="${escapeAttr(field.step)}"` : ""}`
    : "";
  const inputType = field.type === "number" ? "number" : "text";
  return `<label><span>${label}</span><input id="${id}" type="${inputType}" value="${escapeAttr(normalizedValue)}" ${numberAttrs} ${required} /></label>`;
}

function optionsForField(field, currentValue) {
  const items = itemsForField(field);
  const values = items.map((item) => optionValue(item)).filter((value) => String(value).trim() !== "");
  const shouldForceValue = field.required || (serverType === "video_upscale" && (field.id === "model" || field.id === "scale"));
  const effectiveCurrent = normalizeSelectionValue(currentValue, values, shouldForceValue);
  state.formCache[field.id] = effectiveCurrent;
  const blank = field.required || field.id === "scale" ? "" : `<option value=""></option>`;
  const options = items.map((item) => {
    const value = optionValue(item);
    const selected = String(value) === String(effectiveCurrent) ? "selected" : "";
    return `<option value="${escapeAttr(value)}" ${selected}>${escapeHtml(optionLabel(item))}</option>`;
  }).join("");
  return options ? blank + options : `<option value="">${t("noModels")}</option>`;
}

function itemsForField(field) {
  let items = [];
  if (serverType === "video_upscale" && field.id === "scale") {
    const model = selectedUpscalerModel();
    items = (model?.scales || []).map((scale) => ({ value: String(scale), label: `${scale}x` }));
  } else if (field.modelKey) {
    items = [...(state.options?.models?.[field.modelKey] || [])];
    if (field.type === "upscalerModel") {
      const engine = currentUpscalerEngine();
      items = items.filter((item) => !item.engine || item.engine === engine);
    }
  } else {
    items = field.options || [];
  }
  return items;
}

function optionValue(item) {
  return item?.value ?? item?.path ?? item?.id ?? item?.name ?? "";
}

function optionLabel(item) {
  if (item?.label) return labelText(item.label);
  return item?.filename || item?.displayName || item?.title || item?.name || item?.modelName || item?.baseUrl || String(optionValue(item));
}

function fieldVisibleClient(field) {
  const rule = field.visibleWhen;
  if (!rule) return true;
  const actual = fieldValue(rule.field);
  if (typeof rule.equals === "boolean") return Boolean(actual) === rule.equals;
  return String(actual) === String(rule.equals);
}

function isResettablePrompt(field) {
  return field.type === "textarea" && field.default !== undefined && /prompt/i.test(field.id || "");
}

function resetPromptField(id) {
  const node = $(id);
  if (!node) return;
  const field = fieldsForAction(currentAction()).find((item) => item.id === id);
  const value = field?.default ?? node.dataset.defaultValue ?? "";
  node.value = value;
  state.formCache[id] = value;
}

function wireUploads() {
  document.querySelectorAll(".upload-button").forEach((button) => {
    button.addEventListener("click", () => uploadField(button.dataset.target));
  });
}

function wireDependentControls() {
  ["model", "scale", "summaryBackend", "providerEngine", "providerId"].forEach((id) => {
    const node = $(id);
    if (node) {
      node.addEventListener("change", () => {
        if (id === "providerId") clearProviderEditorCache();
        snapshotFormState();
        renderControls();
      });
    }
  });
	  if (serverType === "video_upscale") {
	    const inputPath = $("inputPath");
	    if (inputPath) inputPath.addEventListener("change", refreshVideoInfo);
	  }
	  document.querySelectorAll(".reset-prompt").forEach((button) => {
	    button.addEventListener("click", () => resetPromptField(button.dataset.target));
	  });
	}

function renderAll() {
  renderStatus();
  renderJobs();
  renderGallery();
  renderChatHistory();
  renderVideoInfoPanel();
}

function renderStatus() {
  $("healthStatus").textContent = state.health?.ok ? t("ready") : "...";
  $("accessMode").textContent = state.health?.accessMode === "USERS" ? t("usersMode") : t("publicMode");
  const urls = state.health?.urls || [];
  $("urlList").innerHTML = urls.map((entry) => `
    <article class="url-card">
      <strong>${escapeHtml(entry.label)}</strong>
      <code>${escapeHtml(entry.url)}</code>
      <img alt="QR" src="${qrDataUrl(entry.url)}" />
      <div class="toolbar">
        <button class="secondary" type="button" onclick="copyText('${escapeAttr(entry.url)}')">${t("copyUrl")}</button>
        <a href="${escapeAttr(entry.url)}" target="_blank" rel="noreferrer"><button class="ghost" type="button">${t("open")}</button></a>
      </div>
    </article>
  `).join("");
}

function renderJobs() {
  const failed = state.jobs.some((job) => job.status === "FAILED");
  const toolbar = failed
    ? `<div class="toolbar task-toolbar"><button class="secondary" type="button" onclick="clearFailedJobs()">${t("clearFailed")}</button></div>`
    : "";
  $("jobsList").innerHTML = toolbar + (state.jobs.length ? state.jobs.map((job) => `
    <article class="job-card ${statusClass(job.status)}">
      <div class="job-heading">
        <strong>${escapeHtml(job.title || job.id)}</strong>
        <span>${statusText(job.status)}</span>
      </div>
	      <div class="progress-track"><div style="width:${Math.round((job.progress || 0) * 100)}%"></div></div>
	      <p>${escapeHtml(job.message || "")}</p>
	      ${job.status === "FAILED" ? `<p class="error-text"><strong>${t("failReason")}:</strong> ${escapeHtml(job.errorMessage || job.message || "")}</p>` : ""}
	      ${job.artifactPath ? renderArtifactPreview(job.artifactPath) : ""}
	      <div class="job-actions">
	        ${canCancelJob(job) ? `<button class="secondary cancel-job" type="button" onclick="cancelJob('${escapeAttr(job.id)}')">${t("cancelTask")}</button>` : ""}
	        ${job.canRetry ? `<button class="secondary retry-job" type="button" onclick="retryJob('${escapeAttr(job.id)}')">${t("retryTask")}</button>` : ""}
	        ${canRemoveJob(job) ? `<button class="ghost remove-job" type="button" onclick="removeJob('${escapeAttr(job.id)}')">${t("removeTask")}</button>` : ""}
	      </div>
    </article>
  `).join("") : `<p class="empty">${t("noJobs")}</p>`);
}

function renderArtifactPreview(path) {
  const url = mediaUrl(path);
  const lower = path.toLowerCase();
  const preview = lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".webp")
    ? `<img class="artifact-preview" src="${escapeAttr(url)}" alt="output" />`
    : lower.endsWith(".mp4") || lower.endsWith(".avi")
      ? `<video class="artifact-preview" controls src="${escapeAttr(url)}"></video>`
      : lower.endsWith(".wav") || lower.endsWith(".mp3")
        ? `<audio controls src="${escapeAttr(url)}"></audio>`
        : "";
  return `<div class="artifact-actions">${preview}<a href="${escapeAttr(url)}" target="_blank" rel="noreferrer">${t("download")}</a><code>${escapeHtml(path)}</code></div>`;
}

function canRemoveJob(job) {
  return ["QUEUED", "FAILED", "COMPLETED", "READY", "CANCELLED"].includes(job.status);
}

function canCancelJob(job) {
  return ["QUEUED", "RUNNING"].includes(job.status);
}

async function cancelJob(id) {
  try {
    await requestJson(`/api/jobs/${encodeURIComponent(id)}/cancel`, { method: "POST", body: "{}" });
    await refreshDynamic();
  } catch (error) {
    showToast(error.message);
  }
}

async function removeJob(id) {
  try {
    await requestJson(`/api/jobs/${encodeURIComponent(id)}`, { method: "DELETE" });
    await refreshDynamic();
  } catch (error) {
    showToast(error.message);
  }
}

async function retryJob(id) {
  try {
    await requestJson(`/api/jobs/${encodeURIComponent(id)}/retry`, { method: "POST", body: "{}" });
    showToast(t("started"));
    await refreshDynamic();
  } catch (error) {
    showToast(error.message);
  }
}

async function clearFailedJobs() {
  try {
    await requestJson("/api/jobs/clear-failed", { method: "POST", body: "{}" });
    await refreshDynamic();
  } catch (error) {
    showToast(error.message);
  }
}

function renderGallery() {
  $("galleryList").innerHTML = state.gallery.length ? state.gallery.map((artifact) => {
    const media = artifact.mimeType?.startsWith("image/")
      ? `<img src="${escapeAttr(artifact.url)}" alt="${escapeAttr(artifact.title || "output")}" />`
      : artifact.mimeType?.startsWith("video/")
        ? `<video controls src="${escapeAttr(artifact.url)}"></video>`
        : artifact.mimeType?.startsWith("audio/")
          ? `<audio controls src="${escapeAttr(artifact.url)}"></audio>`
          : "";
    return `
      <article class="artifact-card">
	        ${media}
	        <strong>${escapeHtml(artifact.title || artifact.path)}</strong>
	        <code>${escapeHtml(artifact.path || "")}</code>
	        <div class="toolbar">
	          <a href="${escapeAttr(artifact.url)}" target="_blank" rel="noreferrer">${t("download")}</a>
	          <button class="ghost" type="button" onclick="deleteArtifact('${escapeAttr(artifact.id)}')">${t("removeGallery")}</button>
	        </div>
	      </article>
	    `;
	  }).join("") : `<p class="empty">${t("noGallery")}</p>`;
	}

async function deleteArtifact(id) {
  if (!window.confirm(t("confirmRemoveGallery"))) return;
  try {
    await requestJson(`/api/gallery/${encodeURIComponent(id)}`, { method: "DELETE" });
    await refreshDynamic();
  } catch (error) {
    showToast(error.message);
  }
}

function renderChatControls() {
  const providers = state.chat?.providers || state.options?.models?.webProviders || [];
  const chats = state.chat?.chats || state.options?.models?.webChats || [];
  const activeChatId = state.activeChatId || state.chat?.activeChatId || optionValue(chats[0] || {});
  const activeProviderId = state.activeProviderId || state.chat?.activeProviderId || optionValue(providers[0] || {});
  const activeProvider = providers.find((provider) => String(provider.id) === String(activeProviderId)) || null;
  const providerParams = activeProvider?.params || {};
  const activeProviderEngine = normalizeProviderEngine(activeProvider?.engine || "ollama");
  const activeProviderModelName = activeProvider?.modelName || "";
  const activeProviderLiteRtModelId = String(activeProvider?.liteRtModelId || "");
  const draftProviderKey = providerDraftCacheId(activeProviderId);
  const draftEngine = normalizeProviderEngine(resolveSimpleValue("providerDraftEngine", activeProvider?.engine || "ollama"));
  const draftBaseUrl = resolveSimpleValue("providerDraftBaseUrl", activeProvider?.baseUrl || "");
  const draftModels = getCachedProviderModels(draftProviderKey, draftEngine, draftBaseUrl);
  const draftModelName = resolveSimpleValue("providerDraftModelName", activeProvider?.modelName || "");
  const draftLiteRtModelId = String(resolveSimpleValue("providerDraftLiteRtModelId", activeProvider?.liteRtModelId || ""));
  const draftModelStatus = getProviderModelsStatus(draftProviderKey, draftEngine, draftBaseUrl);
  const contextTokens = resolveSimpleValue("contextTokens", providerParams.contextTokens ?? 8192);
  const maxTokens = resolveSimpleValue("maxTokens", providerParams.maxTokens ?? 2048);
  const maxOutputTokens = resolveSimpleValue("maxOutputTokens", providerParams.maxOutputTokens ?? 1024);
  const temperature = resolveSimpleValue("temperature", providerParams.temperature ?? 0.7);
  const thinkingEnabled = Boolean(resolveSimpleValue("thinkingEnabled", providerParams.thinkingEnabled ?? providerParams.enable_thinking ?? false));
  const topP = resolveSimpleValue("topP", providerParams.topP ?? providerParams.top_p ?? 0.95);
  const topK = resolveSimpleValue("topK", providerParams.topK ?? providerParams.top_k ?? 40);
  const repeatPenalty = resolveSimpleValue("repeatPenalty", providerParams.repeatPenalty ?? providerParams.repeat_penalty ?? 1.1);
  const chatBusy = isChatBusy();
  return `
    <section id="chatDropZone" class="chat-layout ${state.showToolUsage ? "tool-usage-open" : ""}">
      <aside class="chat-sidebar">
        <button id="newChatButton" class="secondary" type="button">${t("createChat")}</button>
        <div class="chat-list">
          ${chats.map((chat) => `
            <button class="chat-list-item ${String(chat.id) === String(activeChatId) ? "active" : ""}" data-chat-id="${escapeAttr(chat.id)}" type="button">
              ${escapeHtml(chat.title || "Chat")}
            </button>
          `).join("")}
        </div>
        <details id="toolUsageShell" class="tool-usage-shell ${state.showToolUsage ? "" : "hidden"}" ${state.showToolUsage ? "open" : ""}>
          <summary>${t("toolUsage")}</summary>
          <aside id="toolUsagePanel" class="tool-usage-panel"></aside>
        </details>
      </aside>
      <section class="chat-main">
        <div class="chat-header">
          <div class="chat-title-tools">
            <select id="chatId">${chats.map((chat) => `
              <option value="${escapeAttr(chat.id)}" ${String(chat.id) === String(activeChatId) ? "selected" : ""}>${escapeHtml(chat.title || t("chat"))}</option>
            `).join("")}</select>
            <input id="chatTitle" value="${escapeAttr((chats.find((chat) => String(chat.id) === String(activeChatId)) || {}).title || "")}" />
            <button id="renameChatButton" class="secondary" type="button">${t("renameChat")}</button>
            <button id="deleteChatButton" class="ghost" type="button">${t("deleteChat")}</button>
          </div>
          <div class="chat-provider-tools">
            <label><span>${t("provider")}</span><select id="providerId">${providers.map((provider) => `
              <option value="${escapeAttr(provider.id)}" ${String(provider.id) === String(activeProviderId) ? "selected" : ""}>${escapeHtml(provider.name || provider.baseUrl || provider.engine)}</option>
            `).join("")}</select></label>
            ${renderChatHeaderModel(activeProvider, activeProviderEngine, activeProviderModelName, activeProviderLiteRtModelId)}
            <button id="toggleToolUsageButton" class="secondary" type="button">${state.showToolUsage ? t("hideToolUsage") : t("toolUsage")}</button>
          </div>
        </div>
        <div id="chatHistory" class="chat-history"></div>
	        <div id="chatAttachmentChips" class="attachment-chips">${renderAttachmentChips()}</div>
	        <div id="upload_progress_chat" class="upload-progress hidden"><div></div><span>0%</span></div>
	        <div class="chat-composer">
          <input id="chatFileInput" class="hidden" type="file" multiple accept="image/*,audio/*,video/*,.txt,.md,.pdf,text/plain,application/pdf" />
          <button id="chatAttachButton" class="secondary icon-button" type="button" title="${escapeAttr(t("attach"))}">+</button>
          <textarea id="message" rows="2" placeholder="${escapeAttr(t("messagePlaceholder"))}">${escapeHtml(resolveSimpleValue("message", ""))}</textarea>
          <button id="chatSendButton" class="${chatBusy ? "ghost" : "primary"}" type="button">${chatBusy ? t("stopGenerating") : t("sendMessage")}</button>
        </div>
        <div class="toolbar chat-extra-actions">
          <button id="recordAudioButton" class="ghost" type="button">${state.mediaRecorder ? t("stopRecording") : t("recordAudio")}</button>
          <span id="chatDropHint">${t("dropFiles")}</span>
        </div>
        <details class="provider-editor">
          <summary>${t("providerSettings")}</summary>
          <div class="form-grid">
            <label><span>${t("providerName")}</span><input id="providerDraftName" value="${escapeAttr(resolveSimpleValue("providerDraftName", activeProvider?.name || ""))}" /></label>
            <label><span>${t("providerEngine")}</span><select id="providerDraftEngine">
              <option value="llama-server" ${draftEngine === "llama-server" ? "selected" : ""}>llama-server</option>
              <option value="ollama" ${draftEngine === "ollama" ? "selected" : ""}>Ollama</option>
              <option value="llama-swap" ${draftEngine === "llama-swap" ? "selected" : ""}>llama-swap</option>
              <option value="litert-lm" ${draftEngine === "litert-lm" ? "selected" : ""}>LiteRT</option>
            </select></label>
            ${draftEngine === "litert-lm" ? "" : `<label><span>${t("providerUrl")}</span><input id="providerDraftBaseUrl" value="${escapeAttr(draftBaseUrl)}" placeholder="http://localhost:11434" /></label>`}
            ${renderProviderEditorModel(draftProviderKey, draftEngine, draftModels, draftModelName, draftLiteRtModelId, draftModelStatus)}
            ${draftModelStatus?.warning ? `<div class="info-banner provider-warning">${escapeHtml(t("modelRefreshWarning"))}<br><small>${escapeHtml(draftModelStatus.warning)}</small></div>` : ""}
            ${draftEngine === "litert-lm" ? `
              <label><span>${t("providerAccelerator")}</span><select id="providerDraftLiteRtBackend">
                <option value="auto" ${String(resolveSimpleValue("providerDraftLiteRtBackend", activeProvider?.liteRtBackend || "auto")) === "auto" ? "selected" : ""}>Auto</option>
                <option value="cpu" ${String(resolveSimpleValue("providerDraftLiteRtBackend", activeProvider?.liteRtBackend || "auto")) === "cpu" ? "selected" : ""}>CPU</option>
                <option value="gpu" ${String(resolveSimpleValue("providerDraftLiteRtBackend", activeProvider?.liteRtBackend || "auto")) === "gpu" ? "selected" : ""}>GPU</option>
              </select></label>
              <label class="check-field"><input id="providerDraftLiteRtMtpEnabled" type="checkbox" ${Boolean(resolveSimpleValue("providerDraftLiteRtMtpEnabled", false)) ? "checked" : ""} /><span>${t("providerMtp")}</span></label>
            ` : ""}
            <label class="check-field"><input id="providerDraftSupportsVision" type="checkbox" ${Boolean(resolveSimpleValue("providerDraftSupportsVision", activeProvider?.supportsVision || false)) ? "checked" : ""} /><span>${t("providerVision")}</span></label>
            <label class="check-field"><input id="providerDraftSupportsAudio" type="checkbox" ${Boolean(resolveSimpleValue("providerDraftSupportsAudio", activeProvider?.supportsAudio || false)) ? "checked" : ""} /><span>${t("providerAudio")}</span></label>
            <button id="saveProviderButton" class="secondary" type="button">${t("saveProvider")}</button>
          </div>
        </details>
        <details class="provider-editor">
          <summary>${t("generationSettings")}</summary>
          <div class="form-grid">
            <label><span>${t("context")}</span><input id="contextTokens" type="number" value="${escapeAttr(contextTokens)}" min="512" step="1" /></label>
            <label><span>${activeProviderEngine === "litert-lm" ? t("maxOutputTokens") : t("maxTokens")}</span><input id="${activeProviderEngine === "litert-lm" ? "maxOutputTokens" : "maxTokens"}" type="number" value="${escapeAttr(activeProviderEngine === "litert-lm" ? maxOutputTokens : maxTokens)}" min="1" step="1" /></label>
            <label><span>${t("temperature")}</span><input id="temperature" type="number" value="${escapeAttr(temperature)}" min="0" max="${activeProviderEngine === "litert-lm" ? "1" : "2"}" step="0.05" /></label>
            <label><span>${t("topP")}</span><input id="topP" type="number" value="${escapeAttr(topP)}" min="0" max="${activeProviderEngine === "litert-lm" ? "0.95" : "1"}" step="0.01" /></label>
            <label><span>${t("topK")}</span><input id="topK" type="number" value="${escapeAttr(topK)}" min="${activeProviderEngine === "litert-lm" ? "5" : "1"}" max="${activeProviderEngine === "litert-lm" ? "64" : "100"}" step="1" /></label>
            <label><span>${t("repeatPenalty")}</span><input id="repeatPenalty" type="number" value="${escapeAttr(repeatPenalty)}" min="0.8" max="2" step="0.05" /></label>
            <label class="check-field"><input id="thinkingEnabled" type="checkbox" ${thinkingEnabled ? "checked" : ""} /><span>${t("thinking")}</span></label>
          </div>
        </details>
        ${renderChatToolsDrawer(providerParams)}
      </section>
    </section>
  `;
}

function renderChatHeaderModel(provider, providerEngine, providerModelName, providerLiteRtModelId) {
  const modelLabel = providerEngine === "litert-lm"
    ? liteRtModelLabel(providerLiteRtModelId)
    : providerModelName;
  return `<label><span>${t("model")}</span><span class="readonly-pill">${escapeHtml(modelLabel || t("manualModel"))}</span></label>`;
}

function renderProviderEditorModel(draftProviderKey, draftEngine, draftModels, draftModelName, draftLiteRtModelId, modelStatus) {
  if (draftEngine === "litert-lm") {
    const models = state.options?.models?.liteRtLlm || [];
    return `<label><span>${t("providerLiteRt")}</span><select id="providerDraftLiteRtModelId">${models.map((model) => `
      <option value="${escapeAttr(model.id)}" ${String(model.id) === String(draftLiteRtModelId) ? "selected" : ""}>${escapeHtml(model.displayName || model.filename)}</option>
    `).join("")}</select></label>`;
  }
  if (draftEngine === "llama-server") {
    return `<label><span>${t("model")}</span><input id="providerDraftModelName" value="${escapeAttr(draftModelName)}" placeholder="${escapeAttr(t("manualModel"))}" /></label>`;
  }
  return `<label><span>${t("model")}</span><div class="upload-row">
    ${draftModels.length ? `<select id="providerDraftModelName">${renderProviderModelOptions(draftModels, draftModelName)}</select>` : `<input id="providerDraftModelName" value="${escapeAttr(draftModelName)}" placeholder="${escapeAttr(t("manualModel"))}" />`}
    <button id="refreshProviderModelsButton" class="secondary" type="button" data-provider-key="${escapeAttr(draftProviderKey)}">${t("providerRefreshModels")}</button>
  </div>${modelStatus?.loading ? `<small>${escapeHtml(t("loading"))}</small>` : ""}</label>`;
}

function liteRtModelLabel(modelId) {
  const models = state.options?.models?.liteRtLlm || [];
  const model = models.find((item) => String(item.id) === String(modelId || ""));
  return model?.displayName || model?.filename || String(modelId || "");
}

function renderAttachmentChips() {
  return state.chatAttachments.map((attachment, index) => `
    <span class="attachment-chip">
      ${escapeHtml(attachment.name || attachment.path || t("attachments"))}
      <button type="button" title="${escapeAttr(t("removeAttachment"))}" onclick="removeChatAttachment(${index})">x</button>
    </span>
  `).join("");
}

function renderChatToolsDrawer(params) {
  const sections = chatToolSections();
  return `
    <details class="provider-editor tools-drawer" ${state.toolsDrawerOpen ? "open" : ""}>
      <summary>${t("toolsSettings")}</summary>
      <div class="tools-grid">
        ${sections.map((section) => `
          <details class="tool-section" data-tool-section="${escapeAttr(section.id)}" ${state.openToolSections[section.id] ? "open" : ""}>
            <summary>${escapeHtml(labelText(section.label))}</summary>
            <div class="form-grid">${section.fields.map((field) => renderChatToolField(field, params)).join("")}</div>
          </details>
        `).join("")}
      </div>
    </details>
  `;
}

function chatToolSections() {
  const languages = (state.options?.languages || ["en", "es"]).map((code) => [code, code]);
  const ttsVoices = modelOptions("ttsVoices");
  return [
    {
      id: "core",
      label: { en: "Core", es: "Principal" },
      fields: [
        toolField("tools_enabled", "checkbox", "Tools", "Herramientas", false),
        toolField("tool_max_rounds", "number", "Max tool rounds", "Rondas maximas", 12, { min: 1, max: 24, step: 1 }),
        toolField("tool_datetime_enabled", "checkbox", "Date and time", "Fecha y hora", true),
        toolField("tool_calculator_enabled", "checkbox", "Calculator", "Calculadora", true)
      ]
    },
    {
      id: "search",
      label: { en: "Search and research", es: "Busqueda e investigacion" },
      fields: [
        toolField("tool_web_search_enabled", "checkbox", "Web search", "Busqueda web", false),
        toolField("tool_web_search_max_pages", "number", "Web max pages", "Paginas web max", 3, { min: 1, max: 10, step: 1 }),
        toolField("tool_web_search_max_chars", "number", "Web max chars", "Caracteres web max", 2000, { min: 500, max: 20000, step: 100 }),
        toolField("tool_kiwix_search_enabled", "checkbox", "Kiwix", "Kiwix", false),
        toolField("tool_kiwix_server_url", "text", "Kiwix URL", "URL Kiwix", "http://127.0.0.1:8081"),
        toolField("tool_kiwix_max_pages", "number", "Kiwix max pages", "Paginas Kiwix max", 3, { min: 1, max: 10, step: 1 }),
        toolField("tool_kiwix_max_chars", "number", "Kiwix max chars", "Caracteres Kiwix max", 2000, { min: 500, max: 20000, step: 100 }),
        toolField("tool_fetch_url_enabled", "checkbox", "Fetch URLs", "Abrir URLs", false),
        toolField("tool_fetch_url_max_chars", "number", "Fetch max chars", "Caracteres fetch max", 4000, { min: 500, max: 40000, step: 100 }),
        toolField("tool_deep_research_enabled", "checkbox", "Deep research", "Investigacion profunda", false),
        toolField("tool_deep_research_import_selected_kb_enabled", "checkbox", "Import into selected KB", "Importar en KB seleccionada", false),
        toolField("tool_deep_research_source_limit", "number", "Source limit", "Limite de fuentes", 8, { min: 1, step: 1 })
      ]
    },
    {
      id: "organizer",
      label: { en: "Organizer and knowledge", es: "Organizador y conocimiento" },
      fields: [
        toolField("tool_note_tools_enabled", "checkbox", "Notes", "Notas", false),
        toolField("tool_todo_tools_enabled", "checkbox", "Todos", "Tareas", false),
        toolField("tool_calendar_tools_enabled", "checkbox", "Calendar", "Calendario", false),
        toolField("tool_alarm_tools_enabled", "checkbox", "Alarms", "Alarmas", false),
        toolField("tool_knowledge_base_enabled", "checkbox", "Knowledge bases", "Bases de conocimiento", false),
        toolField("tool_knowledge_auto_context_enabled", "checkbox", "Auto context", "Contexto automatico", false),
        toolField("tool_knowledge_base_ids", "text", "Knowledge base IDs", "IDs de bases de conocimiento", ""),
        toolField("tool_chat_document_kb_id", "number", "Document KB ID", "ID KB documento", 0, { min: 0, step: 1 }),
        toolField("tool_knowledge_max_results", "number", "KB max results", "Resultados KB max", 6, { min: 1, max: 20, step: 1 })
      ]
    },
    {
      id: "image_onnx",
      label: { en: "Image tools", es: "Herramientas de imagen" },
      fields: [
        toolField("tool_image_generation_enabled", "checkbox", "Image generation", "Generacion de imagen", false),
        toolField("tool_image_iteration_enabled", "checkbox", "Image iteration", "Iteracion de imagen", false),
        toolField("tool_image_engine", "select", "Image engine", "Motor de imagen", "ONNX", { options: [["ONNX", "ONNX"], ["SD", "Stable Diffusion"]] }),
        toolField("tool_image_model", "select", "ONNX model", "Modelo ONNX", "", { options: modelOptions("onnxImage") }),
        toolField("tool_image_width", "number", "ONNX width", "Ancho ONNX", 512, { min: 64, max: 2048, step: 8 }),
        toolField("tool_image_height", "number", "ONNX height", "Alto ONNX", 512, { min: 64, max: 2048, step: 8 }),
        toolField("tool_image_steps", "number", "ONNX steps", "Pasos ONNX", 20, { min: 1, max: 150, step: 1 }),
        toolField("tool_image_cfg", "number", "ONNX CFG", "CFG ONNX", 6.5, { min: 0.1, max: 30, step: 0.1 }),
        toolField("tool_image_seed", "text", "ONNX seed", "Semilla ONNX", ""),
        toolField("tool_image_negative_prompt", "textarea", "ONNX negative prompt", "Prompt negativo ONNX", ""),
        toolField("tool_image_backend", "select", "ONNX backend", "Backend ONNX", "CPU", { options: enumOptions(["CPU", "NNAPI", "XNNPACK"]) }),
        toolField("tool_image_runtime_threads", "number", "Runtime threads", "Hilos runtime", "", { min: 1, max: 16, step: 1 }),
        toolField("tool_image_graph_optimization", "select", "Graph optimization", "Optimizacion grafo", "ALL", { options: enumOptions(["DISABLE_ALL", "BASIC", "EXTENDED", "ALL"]) }),
        toolField("tool_image_unet_backend", "select", "UNet backend", "Backend UNet", "DEFAULT", { options: enumOptions(["DEFAULT", "CPU", "NNAPI", "XNNPACK"]) }),
        toolField("tool_image_vae_decoder_backend", "select", "VAE decoder backend", "Backend decodificador VAE", "DEFAULT", { options: enumOptions(["DEFAULT", "CPU", "NNAPI", "XNNPACK"]) }),
        toolField("tool_image_vae_encoder_backend", "select", "VAE encoder backend", "Backend codificador VAE", "DEFAULT", { options: enumOptions(["DEFAULT", "CPU", "NNAPI", "XNNPACK"]) }),
        toolField("tool_image_intra_threads", "number", "Intra threads", "Hilos intra", "", { min: 1, max: 16, step: 1 }),
        toolField("tool_image_inter_threads", "number", "Inter threads", "Hilos inter", "", { min: 1, max: 16, step: 1 }),
        toolField("tool_image_execution_mode", "select", "Execution mode", "Modo ejecucion", "SEQUENTIAL", { options: enumOptions(["SEQUENTIAL", "PARALLEL"]) }),
        toolField("tool_image_memory_pattern", "checkbox", "Memory pattern", "Patron memoria", true),
        toolField("tool_image_cpu_arena", "checkbox", "CPU arena", "Arena CPU", true),
        toolField("tool_image_nnapi_cpu_disabled", "checkbox", "NNAPI CPU disabled", "CPU NNAPI desactivada", true),
        toolField("tool_image_nnapi_fp16", "checkbox", "NNAPI FP16", "NNAPI FP16", false)
      ]
    },
    {
      id: "image_sd",
      label: { en: "Stable Diffusion image", es: "Imagen Stable Diffusion" },
      fields: [
        toolField("tool_image_sd_model", "select", "SD model", "Modelo SD", "", { options: modelOptions("sdImageGeneration") }),
        toolField("tool_image_sd_vae", "select", "VAE", "VAE", "", { options: modelOptions("vae", true) }),
        toolField("tool_image_sd_tae", "select", "TAE", "TAE", "", { options: modelOptions("tae", true) }),
        toolField("tool_image_sd_clip_l", "select", "CLIP-L", "CLIP-L", "", { options: modelOptions("clipL", true) }),
        toolField("tool_image_sd_clip_g", "select", "CLIP-G", "CLIP-G", "", { options: modelOptions("clipG", true) }),
        toolField("tool_image_sd_t5xxl", "select", "T5XXL", "T5XXL", "", { options: modelOptions("t5xxl", true) }),
        toolField("tool_image_sd_llm", "select", "LLM", "LLM", "", { options: modelOptions("llm", true) }),
        toolField("tool_image_sd_llm_vision", "select", "LLM vision", "LLM vision", "", { options: modelOptions("llmVision", true) }),
        toolField("tool_image_sd_photomaker", "select", "PhotoMaker", "PhotoMaker", "", { options: modelOptions("photoMaker", true) }),
        toolField("tool_image_sd_width", "number", "SD width", "Ancho SD", 512, { min: 256, max: 1024, step: 8 }),
        toolField("tool_image_sd_height", "number", "SD height", "Alto SD", 512, { min: 256, max: 1024, step: 8 }),
        toolField("tool_image_sd_steps", "number", "SD steps", "Pasos SD", 20, { min: 1, max: 50, step: 1 }),
        toolField("tool_image_sd_cfg", "number", "SD CFG", "CFG SD", 7, { min: 1, max: 20, step: 0.1 }),
        toolField("tool_image_sd_sampler", "select", "Sampler", "Sampler", "EULER_A", { options: enumOptions(["EULER_A", "EULER", "HEUN", "DPM2", "DPMPP2S_A", "DPMPP2M", "LCM"]) }),
        toolField("tool_image_sd_seed", "text", "SD seed", "Semilla SD", ""),
        toolField("tool_image_sd_negative_prompt", "textarea", "SD negative prompt", "Prompt negativo SD", ""),
        toolField("tool_image_sd_threads", "number", "SD threads", "Hilos SD", -1, { min: -1, max: 16, step: 1 }),
        toolField("tool_image_sd_flow_shift", "text", "Flow shift", "Flow shift", ""),
        toolField("tool_image_sd_diffusion_fa", "checkbox", "Diffusion FA", "Diffusion FA", false),
        toolField("tool_image_sd_mmap", "checkbox", "mmap", "mmap", false),
        toolField("tool_image_sd_vae_conv_direct", "checkbox", "VAE conv direct", "VAE conv direct", false),
        toolField("tool_image_sd_qwen_zero_cond_t", "checkbox", "Qwen zero cond T", "Qwen zero cond T", false),
        toolField("tool_image_sd_chroma_disable_dit_mask", "checkbox", "Chroma disable DiT mask", "Chroma disable DiT mask", false)
      ]
    },
    {
      id: "background",
      label: { en: "Background removal", es: "Quitar fondo" },
      fields: [
        toolField("tool_background_removal_enabled", "checkbox", "Background removal", "Quitar fondo", false),
        toolField("tool_background_removal_model", "select", "Model", "Modelo", "", { options: modelOptions("onnxBgr") }),
        toolField("tool_background_removal_backend", "select", "Backend", "Backend", "CPU", { options: enumOptions(["CPU", "NNAPI", "XNNPACK"]) }),
        toolField("tool_background_removal_runtime_threads", "number", "Runtime threads", "Hilos runtime", "", { min: 1, max: 16, step: 1 }),
        toolField("tool_background_removal_graph_optimization", "select", "Graph optimization", "Optimizacion grafo", "ALL", { options: enumOptions(["DISABLE_ALL", "BASIC", "EXTENDED", "ALL"]) }),
        toolField("tool_background_removal_alpha_threshold", "number", "Alpha threshold", "Umbral alfa", 0.5, { min: 0, max: 1, step: 0.01 }),
        toolField("tool_background_removal_feather_radius", "number", "Feather radius", "Radio suavizado", 1, { min: 0, max: 16, step: 1 }),
        toolField("tool_background_removal_mask_softness", "number", "Mask softness", "Suavidad mascara", 1, { min: 0, max: 1, step: 0.01 }),
        toolField("tool_background_removal_mask_contrast", "number", "Mask contrast", "Contraste mascara", 1, { min: 0.25, max: 4, step: 0.05 }),
        toolField("tool_background_removal_export_mask", "checkbox", "Export mask", "Exportar mascara", false),
        toolField("tool_background_removal_resize_before_processing", "checkbox", "Resize before processing", "Redimensionar antes", true),
        toolField("tool_background_removal_resize_max_edge", "number", "Resize max edge", "Lado max redimension", 512, { min: 128, max: 2048, step: 8 })
      ]
    },
    {
      id: "voice",
      label: { en: "Voice and call", es: "Voz y llamada" },
      fields: [
        toolField("assistant_tts_enabled", "checkbox", "Assistant TTS", "Voz del asistente", false),
        toolField("assistant_tts_language", "select", "TTS language", "Idioma TTS", "en", { options: languages }),
        toolField("assistant_tts_voice", "select", "TTS voice", "Voz TTS", "", { options: ttsVoices }),
        toolField("assistant_tts_steps", "number", "TTS steps", "Pasos TTS", 8, { min: 1, max: 32, step: 1 }),
        toolField("assistant_tts_speed", "number", "TTS speed", "Velocidad TTS", 1.05, { min: 0.5, max: 2, step: 0.01 }),
        toolField("call_silence_after_speech_seconds", "number", "Silence after speech", "Silencio tras hablar", 5, { min: 1, max: 15, step: 1 }),
        toolField("call_no_speech_timeout_seconds", "number", "No speech timeout", "Espera sin voz", 10, { min: 3, max: 60, step: 1 })
      ]
    }
  ];
}

function toolField(id, type, en, es, defaultValue, extra = {}) {
  return { id, type, label: { en, es }, default: defaultValue, ...extra };
}

function renderChatToolField(field, params) {
  const value = resolveSimpleValue(field.id, params[field.id] ?? field.default ?? "");
  const label = escapeHtml(labelText(field.label));
  if (field.type === "checkbox") {
    return `<label class="check-field"><input id="${escapeAttr(field.id)}" type="checkbox" ${Boolean(value) ? "checked" : ""} /><span>${label}</span></label>`;
  }
  if (field.type === "select") {
    const options = field.options || [];
    return `<label><span>${label}</span><select id="${escapeAttr(field.id)}">${renderSimpleOptions(options, value)}</select></label>`;
  }
  if (field.type === "textarea") {
    return `<label><span>${label}</span><textarea id="${escapeAttr(field.id)}" rows="3">${escapeHtml(value)}</textarea></label>`;
  }
  return `<label><span>${label}</span><input id="${escapeAttr(field.id)}" type="${field.type || "text"}" value="${escapeAttr(value)}" ${field.min !== undefined ? `min="${escapeAttr(field.min)}"` : ""} ${field.max !== undefined ? `max="${escapeAttr(field.max)}"` : ""} ${field.step !== undefined ? `step="${escapeAttr(field.step)}"` : ""} /></label>`;
}

function modelOptions(key, includeEmpty = false) {
  const items = state.options?.models?.[key] || [];
  const mapped = items.map((item) => [item.path || item.value || item.id || item.name || "", labelText(item.label) || item.displayName || item.name || item.filename || item.path || item.value || ""]);
  return includeEmpty ? [["", "None"], ...mapped] : mapped;
}

function enumOptions(values) {
  return values.map((value) => [value, value.replaceAll("_", " ")]);
}

function renderSimpleOptions(options, currentValue) {
  const current = String(currentValue ?? "");
  const rendered = (options || []).map(([value, label]) => {
    const selected = String(value) === current ? "selected" : "";
    return `<option value="${escapeAttr(value)}" ${selected}>${escapeHtml(label)}</option>`;
  }).join("");
  if (rendered) return rendered;
  return `<option value="${escapeAttr(current)}" selected>${escapeHtml(current || t("noModels"))}</option>`;
}

function isChatBusy() {
  const active = state.activeChatJobId
    ? state.jobs.find((job) => job.id === state.activeChatJobId)
    : state.jobs.find((job) => job.serverType === "llama_chat" && ["QUEUED", "RUNNING"].includes(job.status));
  return Boolean(active && ["QUEUED", "RUNNING"].includes(active.status));
}

function wireChatControls() {
  hydrateProviderEditor();
  const chatSelect = $("chatId");
  if (chatSelect) chatSelect.addEventListener("change", () => loadChat(chatSelect.value));
  const providerSelect = $("providerId");
  if (providerSelect) {
    providerSelect.addEventListener("change", () => {
      snapshotFormState();
      state.activeProviderId = providerSelect.value;
      state.formCache.providerId = providerSelect.value;
      clearProviderEditorCache();
      renderControls();
    });
  }
  const providerDraftEngine = $("providerDraftEngine");
	  if (providerDraftEngine) {
	    providerDraftEngine.addEventListener("change", () => {
	      snapshotFormState();
	      clearCurrentProviderModelList();
	      renderControls();
	      const engine = normalizeProviderEngine(fieldValue("providerDraftEngine"));
	      if (engine === "ollama" || engine === "llama-swap") setTimeout(refreshProviderModels, 0);
	    });
	  }
  const providerDraftBaseUrl = $("providerDraftBaseUrl");
  if (providerDraftBaseUrl) {
    providerDraftBaseUrl.addEventListener("change", () => {
      snapshotFormState();
      clearCurrentProviderModelList();
      setTimeout(refreshProviderModels, 0);
    });
  }
  document.querySelectorAll(".chat-list-item").forEach((button) => {
    button.addEventListener("click", () => {
      loadChat(button.dataset.chatId);
    });
  });
  const newChatButton = $("newChatButton");
  if (newChatButton) newChatButton.onclick = () => createChat();
  const renameButton = $("renameChatButton");
  if (renameButton) renameButton.onclick = renameChat;
  const deleteButton = $("deleteChatButton");
  if (deleteButton) deleteButton.onclick = deleteChat;
  const saveProviderButton = $("saveProviderButton");
  if (saveProviderButton) saveProviderButton.onclick = saveProvider;
	  const refreshModelsButton = $("refreshProviderModelsButton");
	  if (refreshModelsButton) refreshModelsButton.onclick = refreshProviderModels;
	  const toolUsageButton = $("toggleToolUsageButton");
	  if (toolUsageButton) toolUsageButton.onclick = () => {
	    state.showToolUsage = !state.showToolUsage;
	    renderControls();
	  };
	  const toolUsageShell = $("toolUsageShell");
	  if (toolUsageShell) {
	    toolUsageShell.addEventListener("toggle", (event) => {
	      if (event.target !== toolUsageShell) return;
	      state.showToolUsage = toolUsageShell.open;
	    });
	  }
	  const attachButton = $("chatAttachButton");
	  const fileInput = $("chatFileInput");
	  if (attachButton && fileInput) {
	    attachButton.onclick = () => fileInput.click();
	    fileInput.onchange = () => uploadChatFiles(Array.from(fileInput.files || []));
	  }
	  const sendButton = $("chatSendButton");
	  if (sendButton) sendButton.onclick = sendChatMessage;
	  const toolsDrawer = document.querySelector(".tools-drawer");
	  if (toolsDrawer) {
	    toolsDrawer.addEventListener("toggle", (event) => {
	      if (event.target !== toolsDrawer) return;
	      state.toolsDrawerOpen = toolsDrawer.open;
	    });
	  }
	  document.querySelectorAll(".tool-section").forEach((section) => {
	    section.addEventListener("toggle", (event) => {
	      if (event.target !== section) return;
	      const id = section.dataset.toolSection;
	      if (!id) return;
	      state.openToolSections[id] = section.open;
	    });
	  });
	  const recordButton = $("recordAudioButton");
	  if (recordButton) recordButton.onclick = toggleAudioRecording;
	  const dropZone = $("chatDropZone");
	  if (dropZone) {
	    dropZone.addEventListener("dragover", (event) => {
	      event.preventDefault();
	      dropZone.classList.add("drag-active");
	    });
	    dropZone.addEventListener("dragleave", () => dropZone.classList.remove("drag-active"));
	    dropZone.addEventListener("drop", (event) => {
	      event.preventDefault();
	      dropZone.classList.remove("drag-active");
	      uploadChatFiles(Array.from(event.dataTransfer?.files || []));
	    });
	  }
	  const history = $("chatHistory");
  if (history) {
    history.addEventListener("scroll", () => {
      state.chatAutoScroll = isNearBottom(history);
    });
  }
}

function renderChatHistory() {
  if (serverType !== "llama_chat") return;
	  const host = $("chatHistory");
	  if (!host) return;
	  const messages = state.chat?.messages || [];
	  const shouldStick = state.forceChatScrollToBottom || isNearBottom(host);
	  host.innerHTML = state.loadingChatId
	    ? `<p class="empty">${t("loadingChat")}</p>`
	    : (messages.length ? messages.map(renderChatMessage).join("") : `<p class="empty">${t("noMessages")}</p>`);
	  if (shouldStick) {
	    host.scrollTop = host.scrollHeight;
	    state.chatAutoScroll = true;
	  } else {
	    state.chatAutoScroll = false;
	  }
	  state.forceChatScrollToBottom = false;
	  renderToolUsagePanel();
	}

function renderChatMessage(message) {
  const isUser = message.role === "user";
  const status = chatMessageStatus(message);
  return `
    <article class="chat-message ${isUser ? "from-user" : "from-assistant"} ${message.isError ? "error-message" : ""}">
      <div class="message-meta">
        <div class="message-identity">
          <small>${isUser ? t("user") : t("assistant")}</small>
          ${status ? `<span class="message-status ${status.className}" title="${escapeAttr(status.label)}" aria-label="${escapeAttr(status.label)}">${status.symbol}</span>` : ""}
        </div>
        <div class="message-actions">
          <button type="button" class="ghost mini-button" onclick="copyMessage(${Number(message.id)})">${t("copyMessage")}</button>
          <button type="button" class="ghost mini-button" onclick="editMessage(${Number(message.id)})">${t("editMessage")}</button>
          <button type="button" class="ghost mini-button" onclick="deleteMessage(${Number(message.id)})">${t("removeMessage")}</button>
          ${isUser ? "" : `<button type="button" class="ghost mini-button" onclick="regenerateMessage(${Number(message.id)})">${t("regenerateMessage")}</button>`}
          ${isUser ? "" : `<button type="button" class="ghost mini-button" onclick="continueChatFromMessage()">${t("continueMessage")}</button>`}
        </div>
      </div>
      ${renderMessageBody(message.content || "")}
      ${message.toolActivity ? `<span class="tool-activity">${escapeHtml(message.toolActivity)}</span>` : ""}
      ${renderMessageAttachments(message)}
    </article>
  `;
}

function chatMessageStatus(message) {
  if (message.role !== "assistant") return null;
  if (message.isError) return { className: "status-failed", symbol: "×", label: t("messageFailed") };
  if (message.toolActivity) return { className: "status-running", symbol: "…", label: t("messageRunning") };
  return { className: "status-complete", symbol: "✓", label: t("messageComplete") };
}

function renderMessageBody(content) {
  const text = String(content || "");
  if (!text.trim()) return "";
  return `<p class="message-body">${linkifyMessageText(text)}</p>`;
}

function linkifyMessageText(value) {
  const text = String(value || "");
  const markdownLink = /\[([^\]]+)\]\((https?:\/\/[^)\s]+|www\.[^)\s]+)\)/gi;
  let html = "";
  let lastIndex = 0;
  text.replace(markdownLink, (match, label, url, offset) => {
    html += linkifyPlainText(text.slice(lastIndex, offset));
    html += renderSafeLink(url, label);
    lastIndex = offset + match.length;
    return match;
  });
  html += linkifyPlainText(text.slice(lastIndex));
  return html;
}

function linkifyPlainText(text) {
  const plainUrl = /(https?:\/\/[^\s<]+|www\.[^\s<]+)/gi;
  let html = "";
  let lastIndex = 0;
  String(text || "").replace(plainUrl, (match, offset) => {
    html += escapeHtml(text.slice(lastIndex, offset));
    const { core, trailing } = splitTrailingUrlPunctuation(match);
    html += renderSafeLink(core, core);
    html += escapeHtml(trailing);
    lastIndex = offset + match.length;
    return match;
  });
  html += escapeHtml(text.slice(lastIndex));
  return html;
}

function splitTrailingUrlPunctuation(value) {
  let core = String(value || "");
  let trailing = "";
  while (/[.,!?;:]$/.test(core)) {
    trailing = core.slice(-1) + trailing;
    core = core.slice(0, -1);
  }
  return { core, trailing };
}

function renderSafeLink(rawUrl, label) {
  const normalized = normalizeMessageUrl(rawUrl);
  if (!normalized) return escapeHtml(label || rawUrl || "");
  return `<a href="${escapeAttr(normalized)}" target="_blank" rel="noreferrer">${escapeHtml(label || rawUrl)}</a>`;
}

function normalizeMessageUrl(rawUrl) {
  const trimmed = String(rawUrl || "").trim();
  const withScheme = trimmed.toLowerCase().startsWith("www.") ? `https://${trimmed}` : trimmed;
  if (!/^https?:\/\//i.test(withScheme)) return "";
  return withScheme;
}

function renderToolUsagePanel() {
  const panel = $("toolUsagePanel");
  if (!panel) return;
  const events = (state.chat?.messages || []).flatMap((message) =>
    (message.toolEvents || []).map((event) => ({ ...event, messageRole: message.role }))
  );
  panel.innerHTML = `
    <div class="section-title tool-usage-title">
      <div>
        <h2>${t("toolUsage")}</h2>
        <p>${events.length ? `${events.length}` : t("noToolUsage")}</p>
      </div>
      <button class="ghost mini-button" type="button" onclick="clearToolUsage()" ${events.length ? "" : "disabled"}>${t("clearToolUsage")}</button>
    </div>
    <div class="tool-event-list">
      ${events.length ? events.map(renderToolEvent).join("") : `<p class="empty">${t("noToolUsage")}</p>`}
    </div>
  `;
}

function renderToolEvent(event) {
  const status = String(event.status || "").toUpperCase();
  const label = status === "COMPLETED" ? t("toolComplete") : status === "FAILED" ? t("toolFailed") : t("toolRunning");
  const args = event.arguments && typeof event.arguments === "object" ? JSON.stringify(event.arguments, null, 2) : "";
  return `
    <article class="tool-event ${status.toLowerCase()}">
      <strong>${escapeHtml(event.toolName || "tool")}</strong>
      <span>${escapeHtml(label)}${event.phase ? ` - ${escapeHtml(event.phase)}` : ""}</span>
      <small>${escapeHtml(formatTimestamp(event.updatedAt || event.createdAt))}</small>
      ${args ? `<code>${escapeHtml(args)}</code>` : ""}
      ${event.resultText ? `<p>${linkifyMessageText(event.resultText)}</p>` : ""}
      ${event.errorText ? `<p class="error-text">${escapeHtml(event.errorText)}</p>` : ""}
    </article>
  `;
}

function renderMessageAttachments(message) {
  const attachments = [...(message.attachments || [])];
  if (message.imagePath && !attachments.some((item) => item.path === message.imagePath)) {
    attachments.push({ attachmentType: "image", path: message.imagePath, url: mediaUrl(message.imagePath), name: message.imagePath.split("/").pop() });
  }
  if (message.audioPath && !attachments.some((item) => item.path === message.audioPath)) {
    attachments.push({ attachmentType: "audio", path: message.audioPath, url: mediaUrl(message.audioPath), name: message.audioPath.split("/").pop() });
  }
  if (message.documentPath && !attachments.some((item) => item.path === message.documentPath)) {
    attachments.push({ attachmentType: "document", path: message.documentPath, url: mediaUrl(message.documentPath), name: message.documentPath.split("/").pop() });
  }
  if (!attachments.length) return "";
  return `<div class="message-attachments">${attachments.map((attachment) => {
    const url = attachment.url || mediaUrl(attachment.path);
    const name = attachment.name || (attachment.path ? attachment.path.split("/").pop() : t("download"));
    const download = `download="${escapeAttr(name)}"`;
    if (attachment.attachmentType === "image") {
      return `<a class="message-media-link" href="${escapeAttr(url)}" target="_blank" rel="noreferrer" ${download} title="${escapeAttr(t("download"))}"><img src="${escapeAttr(url)}" alt="${escapeAttr(name || t("image"))}" /></a>`;
    }
    if (attachment.attachmentType === "audio") {
      return `<div class="message-media-stack"><audio controls src="${escapeAttr(url)}"></audio><a class="attachment-download" href="${escapeAttr(url)}" target="_blank" rel="noreferrer" ${download}>${t("download")}</a></div>`;
    }
    if (attachment.attachmentType === "video") {
      return `<div class="message-media-stack"><video controls src="${escapeAttr(url)}"></video><a class="attachment-download" href="${escapeAttr(url)}" target="_blank" rel="noreferrer" ${download}>${t("download")}</a></div>`;
    }
    return `<a class="attachment-download" href="${escapeAttr(url)}" target="_blank" rel="noreferrer" ${download}>${escapeHtml(name || attachment.path)}</a>`;
  }).join("")}</div>`;
}

async function login(event) {
  event.preventDefault();
  try {
    await requestJson("/api/auth/login", {
      method: "POST",
      body: JSON.stringify({ username: $("username").value, password: $("password").value })
    });
    await refreshAll();
  } catch (error) {
    showToast(error.message);
  }
}

async function logout() {
  await requestJson("/api/auth/logout", { method: "POST", body: "{}" }).catch(() => null);
  location.reload();
}

async function startJob(event) {
  event.preventDefault();
  if (serverType === "llama_chat") return sendChatMessage(event);
  const action = currentAction();
  const params = collectParams(action);
  try {
    const response = await requestJson("/api/jobs", {
      method: "POST",
      body: JSON.stringify({ action, params })
    });
    showToast(t("started"));
    activateTab("tasks");
    await refreshDynamic();
  } catch (error) {
    showToast(error.message);
  }
}

async function sendChatMessage(event) {
  event?.preventDefault?.();
  if (isChatBusy()) {
    await stopActiveChatJob();
    return;
  }
  const message = String(fieldValue("message") || "").trim();
  if (!message && !state.chatAttachments.length) {
    showToast(t("emptyChatMessage"));
    return;
  }
  const action = currentAction();
  const params = collectParams(action);
  try {
    const response = await requestJson("/api/jobs", {
      method: "POST",
      body: JSON.stringify({ action, params })
    });
    state.activeChatJobId = response.job?.id || null;
    showToast(t("started"));
    if ($("message")) $("message").value = "";
    state.formCache.message = "";
    state.chatAttachments = [];
    state.forceChatScrollToBottom = true;
    await refreshDynamic();
  } catch (error) {
    showToast(error.message);
  }
}

async function stopActiveChatJob() {
  const active = state.activeChatJobId
    ? state.jobs.find((job) => job.id === state.activeChatJobId)
    : state.jobs.find((job) => ["QUEUED", "RUNNING"].includes(job.status));
  if (!active) return;
  await cancelJob(active.id);
  state.activeChatJobId = null;
}

function collectParams(action) {
  const params = { action, engine: $("engine").value, mode: action };
  const fields = serverType === "llama_chat" ? chatFieldDescriptors() : fieldsForAction(action);
	  fields.forEach((field) => {
    const node = $(field.id);
    if (!node) return;
    if (field.type === "checkbox") {
      params[field.id] = node.checked;
    } else if (field.type === "number") {
      const number = Number(node.value);
      if (Number.isFinite(number)) params[field.id] = number;
    } else if (field.type === "file" && field.multiple) {
      params[field.id] = node.value.split(/\n+/).map((value) => value.trim()).filter(Boolean);
    } else {
      params[field.id] = node.value;
    }
    if (field.type === "file" && state.files[field.id]) {
      if (field.id === "inputPath") {
        params.sourceName = state.files[field.id].name;
        params.sourceMimeType = state.files[field.id].type;
      } else if (field.id === "subtitlePath") {
        params.subtitleName = state.files[field.id].name;
      } else if (field.multiple) {
        params.sourceNames = state.files[field.id].names || [];
        params.sourceMimeTypes = state.files[field.id].types || [];
      }
    }
	  });
	  if (serverType === "llama_chat") {
	    params.attachments = state.chatAttachments.map((attachment) => ({ ...attachment }));
	    const firstImage = state.chatAttachments.find((attachment) => attachment.attachmentType === "image");
	    const firstAudio = state.chatAttachments.find((attachment) => attachment.attachmentType === "audio");
	    const firstDocument = state.chatAttachments.find((attachment) => attachment.attachmentType === "document" || attachment.attachmentType === "video");
	    if (firstImage) params.imagePath = firstImage.path;
	    if (firstAudio) params.audioPath = firstAudio.path;
	    if (firstDocument) params.documentPath = firstDocument.path;
	  }
	  return params;
	}

function chatFieldDescriptors() {
  const base = [
	    { id: "providerId" },
	    { id: "chatId" },
	    { id: "chatTitle" },
	    { id: "message" },
	    { id: "contextTokens", type: "number" },
	    { id: "maxTokens", type: "number" },
	    { id: "maxOutputTokens", type: "number" },
    { id: "temperature", type: "number" },
    { id: "topP", type: "number" },
    { id: "topK", type: "number" },
	    { id: "repeatPenalty", type: "number" },
	    { id: "thinkingEnabled", type: "checkbox" }
	  ];
  const toolFields = chatToolSections().flatMap((section) => section.fields.map((field) => ({ id: field.id, type: field.type })));
  return [...base, ...toolFields];
	}

async function uploadField(fieldId) {
  const picker = $(`file_${fieldId}`);
  const files = Array.from(picker?.files || []);
  if (!files.length) return;
  const paths = [];
  const names = [];
  const types = [];
  const button = document.querySelector(`.upload-button[data-target="${fieldId}"]`);
  if (button) button.disabled = true;
  try {
    for (const file of files) {
      const uploaded = await uploadOneFile(file, fieldId);
      paths.push(uploaded.path);
      names.push(file.name);
      types.push(file.type);
    }
    $(fieldId).value = paths.join("\n");
    state.files[fieldId] = files.length > 1
      ? { names, types }
      : { name: names[0], type: types[0] };
    showToast(t("uploaded"));
    if (serverType === "video_upscale" && fieldId === "inputPath") {
      await refreshVideoInfo();
    }
  } catch (error) {
    showToast(error.message);
  } finally {
    if (button) button.disabled = false;
  }
}

function uploadOneFile(file, fieldId) {
  return new Promise((resolve, reject) => {
    const form = new FormData();
    form.append("file", file, file.name);
    const xhr = new XMLHttpRequest();
    xhr.open("POST", "/api/upload");
    xhr.withCredentials = true;
    xhr.upload.onprogress = (event) => {
      if (!event.lengthComputable) return;
      setUploadProgress(fieldId, event.loaded / event.total);
    };
    xhr.onload = () => {
      setUploadProgress(fieldId, 1);
      const json = JSON.parse(xhr.responseText || "{}");
      if (xhr.status < 200 || xhr.status >= 300 || json.ok === false) {
        reject(new Error(json.error || xhr.statusText));
      } else {
        resolve(json);
      }
    };
    xhr.onerror = () => reject(new Error(t("uploadFailed")));
    xhr.send(form);
  });
}

function setUploadProgress(fieldId, value) {
  const host = $(`upload_progress_${fieldId}`);
  if (!host) return;
  host.classList.remove("hidden");
  const percent = Math.round(value * 100);
  host.querySelector("div").style.width = `${percent}%`;
  host.querySelector("span").textContent = `${t("uploading")} ${percent}%`;
  if (percent >= 100) setTimeout(() => host.classList.add("hidden"), 900);
}

async function uploadChatFiles(files) {
  if (!files.length) return;
  try {
    for (const file of files) {
      const uploaded = await uploadOneFile(file, "chat");
      state.chatAttachments.push({
        path: uploaded.path,
        name: file.name || uploaded.name,
        mimeType: file.type || guessMimeType(file.name || uploaded.path),
        sizeBytes: file.size || 0,
        attachmentType: attachmentTypeFor(file.type || "", file.name || uploaded.path)
      });
    }
    renderControls();
    showToast(t("uploaded"));
  } catch (error) {
    showToast(error.message);
  }
}

function removeChatAttachment(index) {
  state.chatAttachments.splice(index, 1);
  renderControls();
}

async function toggleAudioRecording() {
  if (state.mediaRecorder) {
    state.mediaRecorder.stop();
    return;
  }
  try {
    const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
    state.recordingStream = stream;
    state.recordingChunks = [];
    const recorder = new MediaRecorder(stream);
    state.mediaRecorder = recorder;
    recorder.ondataavailable = (event) => {
      if (event.data?.size) state.recordingChunks.push(event.data);
    };
    recorder.onstop = async () => {
      const blob = new Blob(state.recordingChunks, { type: "audio/webm" });
      const file = new File([blob], `recording_${Date.now()}.webm`, { type: "audio/webm" });
      state.mediaRecorder = null;
      state.recordingChunks = [];
      state.recordingStream?.getTracks().forEach((track) => track.stop());
      state.recordingStream = null;
      await uploadChatFiles([file]);
    };
    recorder.start();
    showToast(t("recording"));
    renderControls();
  } catch (error) {
    showToast(t("micPermissionDenied"));
  }
}

function guessMimeType(path) {
  const lower = String(path || "").toLowerCase();
  if (/\.(png|jpg|jpeg|webp)$/.test(lower)) return "image/*";
  if (/\.(wav|mp3|m4a|ogg|webm)$/.test(lower)) return "audio/*";
  if (/\.(mp4|mov|mkv|avi)$/.test(lower)) return "video/*";
  if (/\.pdf$/.test(lower)) return "application/pdf";
  return "application/octet-stream";
}

function attachmentTypeFor(mimeType, name) {
  const lower = String(name || "").toLowerCase();
  if (String(mimeType).startsWith("image/") || /\.(png|jpg|jpeg|webp)$/.test(lower)) return "image";
  if (String(mimeType).startsWith("audio/") || /\.(wav|mp3|m4a|ogg|webm)$/.test(lower)) return "audio";
  if (String(mimeType).startsWith("video/") || /\.(mp4|mov|mkv|avi)$/.test(lower)) return "video";
  return "document";
}

async function refreshVideoInfo() {
  const path = fieldValue("inputPath");
  if (!path) return;
  try {
    state.mediaInfo.inputPath = await requestJson(`/api/media/info?path=${encodeURIComponent(path)}`);
    renderVideoInfoPanel();
  } catch (error) {
    showToast(error.message);
  }
}

function renderVideoInfoPanel() {
  const host = $("videoInfoPanel");
  if (!host) return;
  const info = state.mediaInfo.inputPath;
  if (!info?.ok) {
    host.innerHTML = "";
    return;
  }
  const scale = Number(fieldValue("scale") || selectedUpscalerModel()?.scales?.[0] || 1);
  const finalWidth = info.width ? info.width * scale : 0;
  const finalHeight = info.height ? info.height * scale : 0;
  host.innerHTML = `
    <div class="media-info">
      <strong>${t("videoInfo")}</strong>
      <span>${info.width}x${info.height} · ${formatDuration(info.durationSeconds)} · ${formatBytes(info.sizeBytes)}</span>
      <strong>${t("finalSize")}</strong>
      <span>${finalWidth}x${finalHeight}</span>
    </div>
  `;
}

async function createChat() {
  try {
    const created = await requestJson("/api/chat/create", {
      method: "POST",
      body: JSON.stringify({ title: t("webChatName"), providerId: state.activeProviderId || fieldValue("providerId") })
    });
    if (created.chat?.id) {
      state.activeChatId = String(created.chat.id);
      state.formCache.chatId = String(created.chat.id);
    }
    state.options = await requestJson("/api/options");
    state.chat = await requestJson(`/api/chat${state.activeChatId ? `?chatId=${encodeURIComponent(state.activeChatId)}` : ""}`);
    syncActiveChatState(false);
    setupControls();
    renderAll();
  } catch (error) {
    showToast(error.message);
  }
}

async function renameChat() {
  try {
    await requestJson("/api/chat/rename", {
      method: "POST",
      body: JSON.stringify({ chatId: state.activeChatId || fieldValue("chatId"), title: fieldValue("chatTitle") })
    });
    state.chat = await requestJson(`/api/chat?chatId=${encodeURIComponent(state.activeChatId || fieldValue("chatId"))}`);
    syncActiveChatState(false);
    setupControls();
  } catch (error) {
    showToast(error.message);
  }
}

async function deleteChat() {
  try {
    await requestJson("/api/chat/delete", {
      method: "POST",
      body: JSON.stringify({ chatId: state.activeChatId || fieldValue("chatId") })
    });
    state.activeChatId = null;
    delete state.formCache.chatId;
    state.chat = await requestJson("/api/chat");
    syncActiveChatState(true);
    setupControls();
  } catch (error) {
    showToast(error.message);
  }
}

async function loadChat(chatId) {
  if (!chatId) return;
  snapshotFormState();
  state.activeChatId = String(chatId);
  state.formCache.chatId = String(chatId);
  state.loadingChatId = String(chatId);
  document.querySelectorAll(".chat-list-item").forEach((button) => {
    button.classList.toggle("active", String(button.dataset.chatId) === String(chatId));
  });
  renderChatHistory();
  try {
    state.chat = await requestJson(`/api/chat?chatId=${encodeURIComponent(chatId)}`);
    syncActiveChatState(false);
    state.loadingChatId = null;
    renderControls();
  } catch (error) {
    state.loadingChatId = null;
    showToast(error.message);
  }
}

function messageById(messageId) {
  return (state.chat?.messages || []).find((message) => String(message.id) === String(messageId));
}

function copyMessage(messageId) {
  const message = messageById(messageId);
  if (!message) return;
  copyText(message.content || "");
}

async function editMessage(messageId) {
  const message = messageById(messageId);
  if (!message) return;
  const content = window.prompt(t("editMessage"), message.content || "");
  if (content === null) return;
  try {
    await requestJson("/api/chat/message/edit", {
      method: "POST",
      body: JSON.stringify({ messageId, content })
    });
    await refreshDynamic();
  } catch (error) {
    showToast(error.message);
  }
}

async function deleteMessage(messageId) {
  if (!window.confirm(t("confirmRemoveMessage"))) return;
  try {
    await requestJson("/api/chat/message/delete", {
      method: "POST",
      body: JSON.stringify({ messageId })
    });
    await refreshDynamic();
  } catch (error) {
    showToast(error.message);
  }
}

async function regenerateMessage(messageId) {
  try {
    const response = await requestJson("/api/chat/message/regenerate", {
      method: "POST",
      body: JSON.stringify({
        messageId,
        chatId: state.activeChatId || fieldValue("chatId"),
        providerId: state.activeProviderId || fieldValue("providerId"),
        params: collectChatGenerationParams()
      })
    });
    state.activeChatJobId = response.job?.id || null;
    state.forceChatScrollToBottom = true;
    await refreshDynamic();
  } catch (error) {
    showToast(error.message);
  }
}

async function continueChatFromMessage() {
  try {
    const response = await requestJson("/api/chat/continue", {
      method: "POST",
      body: JSON.stringify({
        chatId: state.activeChatId || fieldValue("chatId"),
        providerId: state.activeProviderId || fieldValue("providerId"),
        params: collectChatGenerationParams()
      })
    });
    state.activeChatJobId = response.job?.id || null;
    state.forceChatScrollToBottom = true;
    await refreshDynamic();
  } catch (error) {
    showToast(error.message);
  }
}

async function clearToolUsage() {
  const chatId = state.activeChatId || fieldValue("chatId");
  if (!chatId) return;
  try {
    await requestJson("/api/chat/tool-events/clear", {
      method: "POST",
      body: JSON.stringify({ chatId })
    });
    await refreshDynamic();
  } catch (error) {
    showToast(error.message);
  }
}

async function saveProvider() {
  try {
    const engine = normalizeProviderEngine(fieldValue("providerDraftEngine") || "ollama");
    const selectedProviderId = Number(fieldValue("providerId"));
    const providerPayload = {
      id: Number.isFinite(selectedProviderId) && selectedProviderId > 0 ? selectedProviderId : undefined,
      name: fieldValue("providerDraftName") || t("webProviderName"),
      engine,
      supportsVision: fieldValue("providerDraftSupportsVision"),
      supportsAudio: fieldValue("providerDraftSupportsAudio"),
      params: collectChatGenerationParams()
    };
    if (engine === "litert-lm") {
      const liteRtModelId = Number(fieldValue("providerDraftLiteRtModelId"));
      providerPayload.liteRtModelId = Number.isFinite(liteRtModelId) && liteRtModelId > 0 ? liteRtModelId : null;
      providerPayload.liteRtBackend = fieldValue("providerDraftLiteRtBackend") || "auto";
      providerPayload.liteRtMtpEnabled = fieldValue("providerDraftLiteRtMtpEnabled");
      providerPayload.modelName = null;
      providerPayload.baseUrl = "";
    } else {
      providerPayload.baseUrl = fieldValue("providerDraftBaseUrl");
      providerPayload.modelName = fieldValue("providerDraftModelName");
      providerPayload.liteRtModelId = null;
    }
    const saved = await requestJson("/api/chat/provider", {
      method: "POST",
      body: JSON.stringify(providerPayload)
    });
    if (saved.provider?.id) {
      state.activeProviderId = String(saved.provider.id);
      state.formCache.providerId = String(saved.provider.id);
    }
    clearProviderEditorCache();
    state.options = await requestJson("/api/options");
    state.chat = await requestJson(`/api/chat${state.activeChatId ? `?chatId=${encodeURIComponent(state.activeChatId)}` : ""}`);
    syncActiveChatState(false);
    setupControls();
  } catch (error) {
    showToast(error.message);
  }
}

function currentAction() {
  return $("mode").value || state.options?.modes?.[0]?.id || "";
}

function currentMode() {
  const action = currentAction();
  return (state.options?.modes || []).find((mode) => mode.id === action);
}

function currentModeHint() {
  const mode = currentMode();
  return mode?.hint ? labelText(mode.hint) : t("ready");
}

function fieldsForAction(action) {
  return state.options?.fields?.[action] || [];
}

function fieldValue(id) {
  const node = $(id);
  if (!node) return "";
  return node.type === "checkbox" ? node.checked : node.value;
}

function selectedUpscalerModel() {
  const engine = currentUpscalerEngine();
  const candidates = (state.options?.models?.upscalers || []).filter((model) => !model.engine || model.engine === engine);
  if (!candidates.length) return null;
  const selectedName = String(resolveSimpleValue("model", candidates[0]?.name || ""));
  const model = candidates.find((candidate) => String(candidate.name) === selectedName) || candidates[0];
  state.formCache.model = model?.name || "";
  return model || null;
}

function labelText(value) {
  if (typeof value === "string") return value;
  return value?.[state.lang] || value?.en || "";
}

function activateTab(name) {
  document.querySelectorAll(".tab").forEach((button) => button.classList.toggle("active", button.dataset.tab === name));
  document.querySelectorAll(".tab-panel").forEach((panel) => panel.classList.remove("active"));
  $(`${name}Tab`).classList.add("active");
}

function statusText(status) {
  if (status === "QUEUED") return t("taskQueued");
  if (status === "RUNNING") return t("taskRunning");
  if (status === "COMPLETED" || status === "READY") return t("taskComplete");
  if (status === "FAILED") return t("taskFailed");
  if (status === "CANCELLED") return t("taskCancelled");
  return status || "";
}

function statusClass(status) {
  return String(status || "").toLowerCase();
}

function copyText(value) {
  navigator.clipboard.writeText(value).then(() => showToast(t("copyUrl")));
}

function qrDataUrl(value) {
  return `/api/qr?data=${encodeURIComponent(value)}`;
}

function mediaUrl(path) {
  return `/api/media?path=${encodeURIComponent(path)}`;
}

function formatBytes(bytes) {
  const value = Number(bytes || 0);
  if (value < 1024) return `${value} B`;
  if (value < 1024 * 1024) return `${(value / 1024).toFixed(1)} KB`;
  if (value < 1024 * 1024 * 1024) return `${(value / 1024 / 1024).toFixed(1)} MB`;
  return `${(value / 1024 / 1024 / 1024).toFixed(1)} GB`;
}

function formatDuration(seconds) {
  const total = Math.round(Number(seconds || 0));
  const mins = Math.floor(total / 60);
  const secs = total % 60;
  return `${mins}:${String(secs).padStart(2, "0")}`;
}

function formatTimestamp(value) {
  const millis = Number(value || 0);
  if (!Number.isFinite(millis) || millis <= 0) return "";
  return new Date(millis).toLocaleString(state.lang === "es" ? "es-ES" : "en-US");
}

function showToast(message) {
  const toast = $("toast");
  toast.textContent = message;
  toast.classList.remove("hidden");
  clearTimeout(showToast.timer);
  showToast.timer = setTimeout(() => toast.classList.add("hidden"), 2400);
}

function normalizeProviderEngine(value) {
  const engine = String(value || "").trim().toLowerCase();
  if (engine === "llama-server" || engine === "llama-swap" || engine === "litert-lm") return engine;
  return "ollama";
}

function resolveSimpleValue(id, fallback) {
  const current = currentValue(id);
  if (current !== undefined) return current;
  if (Object.prototype.hasOwnProperty.call(state.formCache, id)) return state.formCache[id];
  return fallback;
}

function resolveFieldValue(field, descriptorDefault) {
  const current = currentValue(field.id);
  if (current !== undefined) {
    if (!(isResettablePrompt(field) && current === "" && field.default !== undefined)) return current;
  }
  if (Object.prototype.hasOwnProperty.call(state.formCache, field.id)) {
    const cached = state.formCache[field.id];
    if (!(isResettablePrompt(field) && cached === "" && field.default !== undefined)) return cached;
  }
  if (field.default !== undefined) return field.default;
  if (descriptorDefault !== undefined) return descriptorDefault;
  return field.type === "checkbox" ? false : "";
}

function currentValue(id) {
  const node = $(id);
  if (!node) return undefined;
  return node.type === "checkbox" ? node.checked : node.value;
}

function snapshotFormState() {
  const fields = document.querySelectorAll("#jobForm input[id], #jobForm select[id], #jobForm textarea[id]");
  fields.forEach((field) => {
    state.formCache[field.id] = field.type === "checkbox" ? field.checked : field.value;
  });
}

function normalizeSelectionValue(currentValue, validValues, required) {
  const values = (validValues || []).map((value) => String(value));
  const raw = currentValue === undefined || currentValue === null ? "" : String(currentValue);
  if (values.includes(raw)) return raw;
  if (required && values.length) return values[0];
  return raw;
}

function currentUpscalerEngine() {
  const engine = resolveSimpleValue("engine", "REALSR");
  return String(engine || "REALSR");
}

function isNearBottom(node, threshold = 56) {
  return (node.scrollHeight - node.scrollTop - node.clientHeight) <= threshold;
}

function clearProviderEditorCache() {
  [
    "providerDraftName",
    "providerDraftEngine",
    "providerDraftBaseUrl",
    "providerDraftModelName",
    "providerDraftLiteRtModelId",
    "providerDraftLiteRtBackend",
    "providerDraftLiteRtMtpEnabled",
    "providerDraftSupportsVision",
    "providerDraftSupportsAudio"
  ].forEach((key) => {
    delete state.formCache[key];
  });
}

function hydrateProviderEditor() {
  const providerId = state.activeProviderId || resolveSimpleValue("providerId", "");
  const provider = (state.chat?.providers || []).find((item) => String(item.id) === String(providerId));
  if (!provider) return;
  state.formCache.providerDraftName = state.formCache.providerDraftName ?? provider.name ?? "";
  state.formCache.providerDraftEngine = state.formCache.providerDraftEngine ?? normalizeProviderEngine(provider.engine);
  state.formCache.providerDraftBaseUrl = state.formCache.providerDraftBaseUrl ?? provider.baseUrl ?? "";
  state.formCache.providerDraftModelName = state.formCache.providerDraftModelName ?? provider.modelName ?? "";
  state.formCache.providerDraftLiteRtModelId = state.formCache.providerDraftLiteRtModelId ?? (provider.liteRtModelId ?? "");
  state.formCache.providerDraftLiteRtBackend = state.formCache.providerDraftLiteRtBackend ?? (provider.liteRtBackend || "auto");
  state.formCache.providerDraftSupportsVision = state.formCache.providerDraftSupportsVision ?? Boolean(provider.supportsVision);
  state.formCache.providerDraftSupportsAudio = state.formCache.providerDraftSupportsAudio ?? Boolean(provider.supportsAudio);
}

function collectChatGenerationParams() {
  const numberOr = (value, fallback) => {
    const number = Number(value);
    return Number.isFinite(number) ? number : fallback;
  };
  const params = {
    contextTokens: numberOr(fieldValue("contextTokens"), 8192),
    maxTokens: numberOr(fieldValue("maxTokens"), 2048),
    maxOutputTokens: numberOr(fieldValue("maxOutputTokens"), numberOr(fieldValue("maxTokens"), 1024)),
    temperature: numberOr(fieldValue("temperature"), 0.7),
    topP: numberOr(fieldValue("topP"), 0.95),
    topK: numberOr(fieldValue("topK"), 40),
    repeatPenalty: numberOr(fieldValue("repeatPenalty"), 1.1),
    thinkingEnabled: Boolean(fieldValue("thinkingEnabled"))
  };
  chatFieldDescriptors().forEach((field) => {
    if (!field.id.includes("_") && field.id !== "max_tool_rounds") return;
    const node = $(field.id);
    if (!node) return;
    if (field.type === "checkbox") {
      params[field.id] = node.checked;
    } else if (field.type === "number") {
      params[field.id] = numberOr(node.value, params[field.id] ?? 4);
    } else {
      params[field.id] = node.value;
    }
  });
  return params;
}

function providerModelsCacheKey(providerId, engine, baseUrl) {
  return `${String(providerId || "new")}|${normalizeProviderEngine(engine)}|${String(baseUrl || "").trim()}`;
}

function providerDraftCacheId(providerId) {
  return `draft:${String(providerId || "new")}`;
}

function getCachedProviderModels(providerId, engine, baseUrl) {
  const key = providerModelsCacheKey(providerId, engine, baseUrl);
  return state.providerModelsCache[key] || [];
}

function getProviderModelsStatus(providerId, engine, baseUrl) {
  return state.providerModelsStatus[providerModelsCacheKey(providerId, engine, baseUrl)] || null;
}

function clearCurrentProviderModelList() {
  const key = providerModelsCacheKey(providerDraftCacheId(state.activeProviderId || fieldValue("providerId")), fieldValue("providerDraftEngine"), fieldValue("providerDraftBaseUrl"));
  state.providerModelsCache[key] = [];
  state.providerModelsStatus[key] = { loading: true };
}

function renderProviderModelOptions(models, currentValue) {
  const current = String(currentValue || "");
  const hasCurrent = (models || []).some((model) => String(model) === current);
  const leading = current
    ? (hasCurrent ? "" : `<option value="${escapeAttr(current)}" selected>${escapeHtml(current)}</option>`)
    : `<option value="" selected>${escapeHtml(t("manualModel"))}</option>`;
  const options = (models || []).map((model) => {
    const selected = String(model) === current ? "selected" : "";
    return `<option value="${escapeAttr(model)}" ${selected}>${escapeHtml(model)}</option>`;
  }).join("");
  if (!options) {
    return `<option value="${escapeAttr(current)}" selected>${escapeHtml(current || t("manualModel"))}</option>`;
  }
  return leading + options;
}

async function refreshProviderModels() {
  snapshotFormState();
  const engine = normalizeProviderEngine(fieldValue("providerDraftEngine"));
  const baseUrl = fieldValue("providerDraftBaseUrl");
  const providerId = providerDraftCacheId(state.activeProviderId || fieldValue("providerId"));
  const key = providerModelsCacheKey(providerId, engine, baseUrl);
  state.providerModelsCache[key] = [];
  state.providerModelsStatus[key] = { loading: true };
  renderControls();
  try {
    const payload = await requestJson(`/api/chat/provider/models?engine=${encodeURIComponent(engine)}&baseUrl=${encodeURIComponent(baseUrl || "")}`);
    const models = payload.models || [];
    state.providerModelsCache[key] = models;
    state.providerModelsStatus[key] = payload.warning ? { warning: payload.warning } : {};
    renderControls();
  } catch (error) {
    state.providerModelsCache[key] = [];
    state.providerModelsStatus[key] = { warning: error.message };
    renderControls();
  }
}

function escapeHtml(value) {
  return String(value ?? "").replace(/[&<>"']/g, (char) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[char]));
}

function escapeAttr(value) {
  return escapeHtml(value).replaceAll("`", "&#96;");
}
