#include "LiteRtAbi.h"

#include <cstdlib>
#include <cstring>
#include <fcntl.h>
#include <limits>
#include <sstream>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>

#include <dlfcn.h>

namespace stable_audio::litert {

namespace {
template <typename T>
T symbol(void* library, const char* name) {
  return reinterpret_cast<T>(dlsym(library, name));
}

void free_payload(void* payload) { std::free(payload); }
}  // namespace

ReadOnlyModelMapping::~ReadOnlyModelMapping() { reset(); }

void ReadOnlyModelMapping::reset() {
  if (mapped_) munmap(address_, size_);
  address_ = nullptr;
  size_ = 0;
  mapped_ = false;
}

Status ReadOnlyModelMapping::map(const std::string& path) {
  reset();
  const int fd = open(path.c_str(), O_RDONLY | O_CLOEXEC);
  if (fd < 0) return kFileIO;
  struct stat info {};
  if (fstat(fd, &info) != 0 || !S_ISREG(info.st_mode) || info.st_size <= 0 ||
      static_cast<unsigned long long>(info.st_size) >
          static_cast<unsigned long long>(std::numeric_limits<std::size_t>::max())) {
    close(fd);
    return kInvalidFlatbuffer;
  }
  size_ = static_cast<std::size_t>(info.st_size);
  void* mapped = mmap(nullptr, size_, PROT_READ, MAP_PRIVATE, fd, 0);
  close(fd);
  if (mapped == MAP_FAILED) {
    size_ = 0;
    return kFileIO;
  }
  address_ = mapped;
  mapped_ = true;
  return kOk;
}

Api::~Api() { unload(); }

bool Api::load(std::string* error) {
  if (library != nullptr) return true;
  library = dlopen("libLiteRt.so", RTLD_NOW | RTLD_LOCAL);
  if (library == nullptr) {
    if (error != nullptr) *error = "litert_library_unavailable";
    return false;
  }

#define LOAD_REQUIRED(field, symbol_name)                                      \
  field = symbol<decltype(field)>(library, symbol_name);                       \
  if (field == nullptr) {                                                      \
    if (error != nullptr) *error = std::string("litert_symbol_missing:") +    \
        symbol_name;                                                           \
    unload();                                                                  \
    return false;                                                              \
  }

  LOAD_REQUIRED(create_environment, "LiteRtCreateEnvironment");
  LOAD_REQUIRED(destroy_environment, "LiteRtDestroyEnvironment");
  LOAD_REQUIRED(create_options, "LiteRtCreateOptions");
  LOAD_REQUIRED(destroy_options, "LiteRtDestroyOptions");
  LOAD_REQUIRED(set_options_hardware_accelerators,
                "LiteRtSetOptionsHardwareAccelerators");
  LOAD_REQUIRED(create_opaque_options, "LiteRtCreateOpaqueOptions");
  LOAD_REQUIRED(destroy_opaque_options, "LiteRtDestroyOpaqueOptions");
  LOAD_REQUIRED(add_opaque_options, "LiteRtAddOpaqueOptions");

  // These functions were added to the public CPU-options C API after some
  // LiteRT-LM Android AARs were cut. Keep them optional and use the public
  // opaque-options fallback in set_cpu_threads() for those runtimes.
  create_cpu_options = symbol<CreateCpuOptions>(library, "LrtCreateCpuOptions");
  destroy_cpu_options = symbol<DestroyCpuOptions>(library, "LrtDestroyCpuOptions");
  set_cpu_options_num_thread =
      symbol<SetCpuOptionsNumThread>(library, "LrtSetCpuOptionsNumThread");
  get_opaque_cpu_options_data =
      symbol<GetOpaqueCpuOptionsData>(library, "LrtGetOpaqueCpuOptionsData");
  LOAD_REQUIRED(create_model_from_file, "LiteRtCreateModelFromFile");
  // LiteRT 0.12 exports this recovery API, but keep it optional so the
  // worker remains compatible with older LiteRT-LM packages.
  create_model_from_buffer =
      symbol<CreateModelFromBuffer>(library, "LiteRtCreateModelFromBuffer");
  LOAD_REQUIRED(destroy_model, "LiteRtDestroyModel");
  LOAD_REQUIRED(create_compiled_model, "LiteRtCreateCompiledModel");
  LOAD_REQUIRED(destroy_compiled_model, "LiteRtDestroyCompiledModel");
  LOAD_REQUIRED(set_compiled_model_cancellation_function,
                "LiteRtSetCompiledModelCancellationFunction");
  LOAD_REQUIRED(get_num_model_signatures, "LiteRtGetNumModelSignatures");
  LOAD_REQUIRED(get_model_signature, "LiteRtGetModelSignature");
  LOAD_REQUIRED(get_signature_key, "LiteRtGetSignatureKey");
  LOAD_REQUIRED(get_num_signature_inputs, "LiteRtGetNumSignatureInputs");
  LOAD_REQUIRED(get_num_signature_outputs, "LiteRtGetNumSignatureOutputs");
  LOAD_REQUIRED(get_signature_input_tensor_by_index,
                "LiteRtGetSignatureInputTensorByIndex");
  LOAD_REQUIRED(get_signature_output_tensor_by_index,
                "LiteRtGetSignatureOutputTensorByIndex");
  LOAD_REQUIRED(get_signature_input_name, "LiteRtGetSignatureInputName");
  LOAD_REQUIRED(get_signature_output_name, "LiteRtGetSignatureOutputName");
  LOAD_REQUIRED(get_ranked_tensor_type, "LiteRtGetRankedTensorType");
  LOAD_REQUIRED(get_compiled_model_input_tensor_layout,
                "LiteRtGetCompiledModelInputTensorLayout");
  LOAD_REQUIRED(get_compiled_model_output_tensor_layouts,
                "LiteRtGetCompiledModelOutputTensorLayouts");
  LOAD_REQUIRED(resize_input_tensor_non_strict,
                "LiteRtCompiledModelResizeInputTensorNonStrict");
  LOAD_REQUIRED(create_managed_tensor_buffer, "LiteRtCreateManagedTensorBuffer");
  LOAD_REQUIRED(destroy_tensor_buffer, "LiteRtDestroyTensorBuffer");
  LOAD_REQUIRED(get_tensor_buffer_host_memory,
                "LiteRtGetTensorBufferHostMemory");
  LOAD_REQUIRED(run_compiled_model, "LiteRtRunCompiledModel");
  get_status_string = symbol<GetStatusString>(library, "LiteRtGetStatusString");
#undef LOAD_REQUIRED
  return true;
}

void Api::unload() {
  if (library != nullptr) {
    dlclose(library);
  }
  library = nullptr;
  create_environment = nullptr;
  destroy_environment = nullptr;
  create_options = nullptr;
  destroy_options = nullptr;
  set_options_hardware_accelerators = nullptr;
  create_cpu_options = nullptr;
  destroy_cpu_options = nullptr;
  set_cpu_options_num_thread = nullptr;
  get_opaque_cpu_options_data = nullptr;
  create_opaque_options = nullptr;
  destroy_opaque_options = nullptr;
  add_opaque_options = nullptr;
  create_model_from_file = nullptr;
  create_model_from_buffer = nullptr;
  destroy_model = nullptr;
  create_compiled_model = nullptr;
  destroy_compiled_model = nullptr;
  set_compiled_model_cancellation_function = nullptr;
  get_num_model_signatures = nullptr;
  get_model_signature = nullptr;
  get_signature_key = nullptr;
  get_num_signature_inputs = nullptr;
  get_num_signature_outputs = nullptr;
  get_signature_input_tensor_by_index = nullptr;
  get_signature_output_tensor_by_index = nullptr;
  get_signature_input_name = nullptr;
  get_signature_output_name = nullptr;
  get_ranked_tensor_type = nullptr;
  get_compiled_model_input_tensor_layout = nullptr;
  get_compiled_model_output_tensor_layouts = nullptr;
  resize_input_tensor_non_strict = nullptr;
  create_managed_tensor_buffer = nullptr;
  destroy_tensor_buffer = nullptr;
  get_tensor_buffer_host_memory = nullptr;
  run_compiled_model = nullptr;
  get_status_string = nullptr;
}

bool Api::set_cpu_threads(void* options, int threads, std::string* error) const {
  if (options == nullptr || threads < 1) {
    if (error != nullptr) *error = "invalid_cpu_thread_count";
    return false;
  }
  if (create_opaque_options == nullptr || add_opaque_options == nullptr) {
    if (error != nullptr) *error = "litert_opaque_options_unavailable";
    return false;
  }

  // Prefer the public typed CPU-options API whenever the runtime exports it.
  // The opaque payload is owned by the newly-created options node after
  // LiteRtCreateOpaqueOptions succeeds, and that node is owned by options
  // after LiteRtAddOpaqueOptions succeeds.
  const bool has_typed_cpu_api = create_cpu_options != nullptr &&
      destroy_cpu_options != nullptr && set_cpu_options_num_thread != nullptr &&
      get_opaque_cpu_options_data != nullptr;
  if (has_typed_cpu_api) {
    void* cpu_options = nullptr;
    Status status = create_cpu_options(&cpu_options);
    if (status != kOk || cpu_options == nullptr) {
      if (error != nullptr) *error = "create_cpu_options";
      return false;
    }
    status = set_cpu_options_num_thread(cpu_options, threads);
    if (status != kOk) {
      destroy_cpu_options(cpu_options);
      if (error != nullptr) *error = "set_cpu_options_threads";
      return false;
    }
    const char* identifier = nullptr;
    void* payload = nullptr;
    void (*payload_deleter)(void*) = nullptr;
    status = get_opaque_cpu_options_data(cpu_options, &identifier, &payload,
                                         &payload_deleter);
    destroy_cpu_options(cpu_options);
    if (status != kOk || identifier == nullptr || payload == nullptr ||
        payload_deleter == nullptr) {
      if (error != nullptr) *error = "serialize_cpu_options";
      return false;
    }
    void* opaque = nullptr;
    status = create_opaque_options(identifier, payload, payload_deleter, &opaque);
    if (status != kOk || opaque == nullptr) {
      // The opaque-options constructor did not take ownership on failure.
      payload_deleter(payload);
      if (error != nullptr) *error = "create_cpu_opaque_options";
      return false;
    }
    status = add_opaque_options(options, opaque);
    if (status != kOk) {
      // Ownership is transferred only on success.
      destroy_opaque_options(opaque);
      if (error != nullptr) *error = "add_cpu_opaque_options";
      return false;
    }
    return true;
  }

  // LiteRT-LM Android 0.12.0 exports the stable opaque-options C API and its
  // pinned runtime parses the documented CPU payload key `num_threads`, but
  // does not export the typed Lrt*CpuOptions helpers. This keeps the request
  // effective on that AAR without declaring or calling private symbols.
  std::ostringstream toml;
  toml << "num_threads = " << threads << "\n";
  const std::string serialized = toml.str();
  void* payload = std::malloc(serialized.size() + 1);
  if (payload == nullptr) {
    if (error != nullptr) *error = "allocate_cpu_options_payload";
    return false;
  }
  std::memcpy(payload, serialized.c_str(), serialized.size() + 1);
  void* opaque = nullptr;
  const Status status = create_opaque_options("xnnpack", payload, &free_payload, &opaque);
  if (status != kOk || opaque == nullptr) {
    std::free(payload);
    if (error != nullptr) *error = "create_cpu_opaque_options";
    return false;
  }
  if (add_opaque_options(options, opaque) != kOk) {
    destroy_opaque_options(opaque);
    if (error != nullptr) *error = "add_cpu_opaque_options";
    return false;
  }
  return true;
}

}  // namespace stable_audio::litert
