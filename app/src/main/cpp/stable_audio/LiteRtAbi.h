#pragma once

// Minimal ABI declarations for the public LiteRT C API used by Stable Audio.
// The application deliberately resolves these symbols with dlsym from the
// LiteRT AAR already shipped by the app; no second LiteRT shared library is
// linked into the APK. Keep this header limited to ABI-stable public structs.

#include <cstddef>
#include <cstdint>
#include <string>
#include <type_traits>

namespace stable_audio::litert {

using Status = int;
constexpr Status kOk = 0;
constexpr Status kCancelled = 100;
// Public LiteRT status values. Keep these here so Java receives a stable
// repair class even when the dynamic ABI omits a status-to-string export.
constexpr Status kMemoryAllocationFailure = 2;
constexpr Status kFileIO = 500;
constexpr Status kInvalidFlatbuffer = 501;
constexpr int kCpu = 1;
constexpr int kHostMemory = 1;

struct Layout {
  unsigned int rank : 7;
  unsigned int has_strides : 1;
  int32_t dimensions[8];
  uint32_t strides[8];
};

enum ElementType : int {
  kFloat32 = 1,
  kInt32 = 2,
  kFloat16 = 10,
};

struct RankedTensorType {
  ElementType element_type;
  Layout layout;
};

/** Owns a read-only model mapping for LiteRtCreateModelFromBuffer. */
class ReadOnlyModelMapping final {
 public:
  ReadOnlyModelMapping() = default;
  ReadOnlyModelMapping(const ReadOnlyModelMapping&) = delete;
  ReadOnlyModelMapping& operator=(const ReadOnlyModelMapping&) = delete;
  ~ReadOnlyModelMapping();

  Status map(const std::string& path);
  void reset();
  const void* address() const { return address_; }
  std::size_t size() const { return size_; }

 private:
  void* address_ = nullptr;
  std::size_t size_ = 0;
  bool mapped_ = false;
};

// This mirrors LiteRT's public litert/c/litert_layout.h and
// litert/c/litert_model_types.h ABI. The app's 0.12.0 AAR is loaded at
// runtime; these checks make a compiler/platform layout drift fail at build
// time rather than corrupting a model invocation. The LiteRT layout contract
// is documented at the pinned public source revision
// d846569552c71f0c0b05f89476f865f3979e0a12.
static_assert(sizeof(ElementType) == sizeof(int32_t), "LiteRT element type ABI changed");
static_assert(sizeof(Layout) == 68, "LiteRT layout ABI changed");
static_assert(offsetof(Layout, dimensions) == 4, "LiteRT layout dimensions ABI changed");
static_assert(offsetof(Layout, strides) == 36, "LiteRT layout strides ABI changed");
static_assert(sizeof(RankedTensorType) == 72, "LiteRT ranked type ABI changed");
static_assert(offsetof(RankedTensorType, layout) == 4,
              "LiteRT ranked type layout ABI changed");

struct Api {
  Api() = default;
  Api(const Api&) = delete;
  Api& operator=(const Api&) = delete;
  ~Api();

  void* library = nullptr;

  using CreateEnvironment = Status (*)(int, const void*, void**);
  using DestroyEnvironment = void (*)(void*);
  using CreateOptions = Status (*)(void**);
  using DestroyOptions = void (*)(void*);
  using SetOptionsHardwareAccelerators = Status (*)(void*, int);
  using CreateCpuOptions = Status (*)(void**);
  using DestroyCpuOptions = void (*)(void*);
  using SetCpuOptionsNumThread = Status (*)(void*, int);
  using GetOpaqueCpuOptionsData = Status (*)(const void*, const char**, void**,
                                             void (**)(void*));
  using CreateOpaqueOptions = Status (*)(const char*, void*, void (*)(void*), void**);
  using DestroyOpaqueOptions = void (*)(void*);
  using AddOpaqueOptions = Status (*)(void*, void*);
  using CreateModelFromFile = Status (*)(void*, const char*, void**);
  // Optional in older LiteRT-LM AARs. The buffer API is used only as a
  // recovery path when the file API reports its public file-I/O status.
  using CreateModelFromBuffer = Status (*)(void*, const void*, std::size_t, void**);
  using DestroyModel = void (*)(void*);
  using CreateCompiledModel = Status (*)(void*, void*, void*, void**);
  using DestroyCompiledModel = void (*)(void*);
  using SetCompiledModelCancellationFunction = Status (*)(
      void*, void*, bool (*)(void*));
  using GetNumModelSignatures = Status (*)(void*, std::size_t*);
  using GetModelSignature = Status (*)(void*, std::size_t, void**);
  using GetSignatureKey = Status (*)(void*, const char**);
  using GetNumSignatureInputs = Status (*)(void*, std::size_t*);
  using GetNumSignatureOutputs = Status (*)(void*, std::size_t*);
  using GetSignatureInputTensorByIndex = Status (*)(void*, std::size_t, void**);
  using GetSignatureOutputTensorByIndex = Status (*)(void*, std::size_t, void**);
  using GetSignatureInputName = Status (*)(void*, std::size_t, const char**);
  using GetSignatureOutputName = Status (*)(void*, std::size_t, const char**);
  using GetRankedTensorType = Status (*)(void*, RankedTensorType*);
  using GetCompiledModelInputTensorLayout = Status (*)(
      void*, std::size_t, std::size_t, Layout*);
  using GetCompiledModelOutputTensorLayouts = Status (*)(
      void*, std::size_t, std::size_t, Layout*, bool);
  using ResizeInputTensorNonStrict = Status (*)(
      void*, std::size_t, std::size_t, const int32_t*, std::size_t);
  using CreateManagedTensorBuffer = Status (*)(
      void*, int, const RankedTensorType*, std::size_t, void**);
  using DestroyTensorBuffer = void (*)(void*);
  using GetTensorBufferHostMemory = Status (*)(void*, void**);
  using RunCompiledModel = Status (*)(
      void*, std::size_t, std::size_t, void**, std::size_t, void**);
  using GetStatusString = const char* (*)(Status);

  CreateEnvironment create_environment = nullptr;
  DestroyEnvironment destroy_environment = nullptr;
  CreateOptions create_options = nullptr;
  DestroyOptions destroy_options = nullptr;
  SetOptionsHardwareAccelerators set_options_hardware_accelerators = nullptr;
  CreateCpuOptions create_cpu_options = nullptr;
  DestroyCpuOptions destroy_cpu_options = nullptr;
  SetCpuOptionsNumThread set_cpu_options_num_thread = nullptr;
  GetOpaqueCpuOptionsData get_opaque_cpu_options_data = nullptr;
  CreateOpaqueOptions create_opaque_options = nullptr;
  DestroyOpaqueOptions destroy_opaque_options = nullptr;
  AddOpaqueOptions add_opaque_options = nullptr;
  CreateModelFromFile create_model_from_file = nullptr;
  CreateModelFromBuffer create_model_from_buffer = nullptr;
  DestroyModel destroy_model = nullptr;
  CreateCompiledModel create_compiled_model = nullptr;
  DestroyCompiledModel destroy_compiled_model = nullptr;
  SetCompiledModelCancellationFunction set_compiled_model_cancellation_function = nullptr;
  GetNumModelSignatures get_num_model_signatures = nullptr;
  GetModelSignature get_model_signature = nullptr;
  GetSignatureKey get_signature_key = nullptr;
  GetNumSignatureInputs get_num_signature_inputs = nullptr;
  GetNumSignatureOutputs get_num_signature_outputs = nullptr;
  GetSignatureInputTensorByIndex get_signature_input_tensor_by_index = nullptr;
  GetSignatureOutputTensorByIndex get_signature_output_tensor_by_index = nullptr;
  GetSignatureInputName get_signature_input_name = nullptr;
  GetSignatureOutputName get_signature_output_name = nullptr;
  GetRankedTensorType get_ranked_tensor_type = nullptr;
  GetCompiledModelInputTensorLayout get_compiled_model_input_tensor_layout = nullptr;
  GetCompiledModelOutputTensorLayouts get_compiled_model_output_tensor_layouts = nullptr;
  ResizeInputTensorNonStrict resize_input_tensor_non_strict = nullptr;
  CreateManagedTensorBuffer create_managed_tensor_buffer = nullptr;
  DestroyTensorBuffer destroy_tensor_buffer = nullptr;
  GetTensorBufferHostMemory get_tensor_buffer_host_memory = nullptr;
  RunCompiledModel run_compiled_model = nullptr;
  GetStatusString get_status_string = nullptr;

  bool load(std::string* error);
  void unload();

  // Adds the public LiteRT CPU options to a compilation options object. Older
  // LiteRT-LM AARs omit the Lrt*CpuOptions exports but still expose the stable
  // opaque-options C ABI; in that case the pinned CPU parser accepts the same
  // TOML payload through the public opaque-options API.
  bool set_cpu_threads(void* options, int threads, std::string* error) const;
};

}  // namespace stable_audio::litert
