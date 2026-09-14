#include "LiteRtAbi.h"

#include <android/log.h>
#include <jni.h>

#include <algorithm>
#include <atomic>
#include <cctype>
#include <chrono>
#include <cmath>
#include <cerrno>
#include <cstdio>
#include <cstdint>
#include <cstring>
#include <fcntl.h>
#include <fstream>
#include <iterator>
#include <limits>
#include <memory>
#include <numeric>
#include <random>
#include <sstream>
#include <stdexcept>
#include <string>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unordered_map>
#include <unordered_set>
#include <utility>
#include <vector>
#include <unistd.h>

namespace stable_audio {
namespace {

constexpr char kLogTag[] = "StableAudio3Native";
constexpr int kSampleRate = 44100;
constexpr int kSamplesPerLatent = 4096;
constexpr int kConditioningTokens = 256;
constexpr int kConditioningDimension = 768;
constexpr float kMinSigma = 0.01f;
constexpr float kPi = 3.14159265358979323846f;

std::atomic<bool> g_cancelled{false};

class NativeError final : public std::runtime_error {
 public:
  explicit NativeError(const char* code) : std::runtime_error(code), code_(code) {}
  explicit NativeError(const std::string& code) : std::runtime_error(code), code_(code) {}
  const std::string& code() const { return code_; }

 private:
  std::string code_;
};

void check_litert(litert::Status status, const litert::Api& api, const char* operation) {
  if (status == litert::kOk) return;
  if (status == litert::kCancelled || g_cancelled.load(std::memory_order_relaxed)) {
    throw NativeError("cancelled");
  }
  // Error strings from LiteRT can include model paths. Only retain a stable
  // operation code; the foreground service does not expose native diagnostics
  // containing private paths or prompt text.
  __android_log_print(ANDROID_LOG_ERROR, kLogTag, "LiteRT operation failed: %s (%d)",
                      operation, status);
  const char* code = nullptr;
  switch (status) {
    case litert::kMemoryAllocationFailure:
      code = "litert_memory_failed";
      break;
    case litert::kFileIO:
      code = "litert_file_io_failed";
      break;
    case litert::kInvalidFlatbuffer:
      code = "litert_invalid_data_failed";
      break;
    default:
      break;
  }
  throw NativeError(std::string(code == nullptr ? "litert_" : code) +
                    (code == nullptr ? std::string(operation) + "_failed" : "") +
                    ":" + std::to_string(status));
}

/**
 * Classify file-system failures before LiteRT opens a graph. Only a bounded
 * probe is performed; the full payload is still owned by LiteRT. Error codes
 * intentionally contain no path because they cross the isolated-process
 * boundary and are persisted in user-visible diagnostics.
 */
void preflight_model_file(const std::string& path) {
  const int fd = open(path.c_str(), O_RDONLY | O_CLOEXEC);
  if (fd < 0) throw NativeError("litert_file_io_failed:500");

  struct stat info {};
  if (fstat(fd, &info) != 0) {
    close(fd);
    throw NativeError("litert_file_io_failed:500");
  }
  if (!S_ISREG(info.st_mode) || info.st_size <= 0) {
    close(fd);
    throw NativeError("litert_invalid_data_failed:501");
  }

  std::uint8_t probe[4096];
  const ssize_t count = read(fd, probe, sizeof(probe));
  if (count <= 0) {
    close(fd);
    throw NativeError("litert_file_io_failed:500");
  }

  const std::size_t map_length = static_cast<std::size_t>(
      std::min<off_t>(info.st_size, static_cast<off_t>(sizeof(probe))));
  void* mapped = mmap(nullptr, map_length, PROT_READ, MAP_PRIVATE, fd, 0);
  if (mapped == MAP_FAILED) {
    close(fd);
    throw NativeError("litert_file_io_failed:500");
  }
  munmap(mapped, map_length);
  close(fd);
}

bool check_cancelled(void*) {
  return g_cancelled.load(std::memory_order_relaxed);
}

template <typename T>
void check_not_null(T* value, const char* code) {
  if (value == nullptr) throw NativeError(code);
}

std::vector<uint8_t> bytes_from_float(const std::vector<float>& values) {
  std::vector<uint8_t> bytes(values.size() * sizeof(float));
  if (!bytes.empty()) std::memcpy(bytes.data(), values.data(), bytes.size());
  return bytes;
}

std::vector<uint8_t> bytes_from_int32(const std::vector<int32_t>& values) {
  std::vector<uint8_t> bytes(values.size() * sizeof(int32_t));
  if (!bytes.empty()) std::memcpy(bytes.data(), values.data(), bytes.size());
  return bytes;
}

std::size_t element_size(litert::ElementType type) {
  switch (type) {
    case litert::kFloat32:
      return sizeof(float);
    case litert::kFloat16:
      return sizeof(uint16_t);
    case litert::kInt32:
      return sizeof(int32_t);
    default:
      throw NativeError("unsupported_tensor_type");
  }
}

std::size_t num_elements(const litert::RankedTensorType& type) {
  if (type.layout.rank > 8) throw NativeError("invalid_tensor_rank");
  std::size_t result = 1;
  for (unsigned int i = 0; i < type.layout.rank; ++i) {
    const int32_t dimension = type.layout.dimensions[i];
    if (dimension < 0) throw NativeError("dynamic_output_shape_unresolved");
    const std::size_t next = result * static_cast<std::size_t>(dimension);
    if (dimension != 0 && next / static_cast<std::size_t>(dimension) != result) {
      throw NativeError("tensor_size_overflow");
    }
    result = next;
  }
  return result;
}

struct TensorInput {
  std::vector<uint8_t> bytes;
  std::vector<int32_t> shape;
};

struct TensorOutput {
  std::vector<uint8_t> bytes;
  litert::RankedTensorType type{};
};

class LiteRtGraph final {
 public:
  LiteRtGraph(litert::Api* api, const std::string& path, int threads) : api_(api) {
    check_not_null(api_, "litert_api_unavailable");
    if (threads < 1) throw NativeError("invalid_thread_count");
    check_litert(api_->create_environment(0, nullptr, &environment_), *api_,
                 "create_environment");
    try {
      check_litert(api_->create_options(&options_), *api_, "create_options");
      check_litert(api_->set_options_hardware_accelerators(options_, litert::kCpu), *api_,
                   "set_cpu_options");
      std::string cpu_options_error;
      if (!api_->set_cpu_threads(options_, threads, &cpu_options_error)) {
        throw NativeError(cpu_options_error.empty() ? "set_cpu_threads" : cpu_options_error);
      }
      preflight_model_file(path);
      const litert::Status file_status =
          api_->create_model_from_file(environment_, path.c_str(), &model_);
      if (file_status == litert::kFileIO && api_->create_model_from_buffer != nullptr) {
        // Some Android file providers expose a readable path but LiteRT's
        // file loader still returns status 500. A complete read-only mapping
        // gives the same bytes to LiteRT without copying multi-gigabyte DiTs.
        check_litert(mapped_model_.map(path), *api_, "map_model_for_buffer");
        if (model_ != nullptr) {
          api_->destroy_model(model_);
          model_ = nullptr;
        }
        const litert::Status buffer_status = api_->create_model_from_buffer(
            environment_, mapped_model_.address(), mapped_model_.size(), &model_);
        if (buffer_status != litert::kOk) {
          check_litert(buffer_status, *api_, "load_model_from_buffer");
        }
        __android_log_print(ANDROID_LOG_INFO, kLogTag,
                            "LiteRT model loaded through buffer fallback after file status 500");
      } else {
        check_litert(file_status, *api_, "load_model");
      }
      check_litert(api_->create_compiled_model(environment_, model_, options_, &compiled_),
                   *api_, "compile_model");
      check_litert(api_->set_compiled_model_cancellation_function(
                       compiled_, nullptr, &check_cancelled),
                   *api_, "set_cancellation");
      discover_signatures();
    } catch (...) {
      destroy();
      throw;
    }
  }

  LiteRtGraph(const LiteRtGraph&) = delete;
  LiteRtGraph& operator=(const LiteRtGraph&) = delete;

  ~LiteRtGraph() { destroy(); }

  struct Signature {
    std::size_t index = 0;
    std::string key;
  };

  struct InputDescriptor {
    std::size_t index = 0;
    std::string name;
    litert::RankedTensorType type{};
  };

  const std::vector<Signature>& signatures() const { return signatures_; }

  std::size_t default_signature() const {
    if (signatures_.empty()) throw NativeError("model_has_no_signatures");
    for (const Signature& signature : signatures_) {
      if (signature.key.empty() || signature.key == "serving_default") {
        return signature.index;
      }
    }
    return signatures_.front().index;
  }

  std::vector<InputDescriptor> inputs(std::size_t signature_index) const {
    void* signature_handle = signature(signature_index);
    std::size_t count = 0;
    check_litert(api_->get_num_signature_inputs(signature_handle, &count), *api_,
                 "query_inputs");
    std::vector<InputDescriptor> result;
    result.reserve(count);
    for (std::size_t i = 0; i < count; ++i) {
      const char* name = nullptr;
      void* tensor = nullptr;
      check_litert(api_->get_signature_input_name(signature_handle, i, &name), *api_,
                   "query_input_name");
      check_litert(api_->get_signature_input_tensor_by_index(signature_handle, i, &tensor),
                   *api_, "query_input_tensor");
      InputDescriptor descriptor;
      descriptor.index = i;
      descriptor.name = name == nullptr ? "" : name;
      check_litert(api_->get_ranked_tensor_type(tensor, &descriptor.type), *api_,
                   "query_input_type");
      result.push_back(std::move(descriptor));
    }
    return result;
  }

  std::size_t signature_for_rung(int rung) const {
    const std::string key = "s" + std::to_string(rung);
    for (const Signature& signature : signatures_) {
      if (signature.key == key) return signature.index;
    }
    throw NativeError("requested_codec_rung_unavailable");
  }

  std::vector<TensorOutput> invoke(std::size_t signature_index,
                                   const std::vector<TensorInput>& inputs) {
    if (g_cancelled.load(std::memory_order_relaxed)) throw NativeError("cancelled");
    std::size_t input_count = 0;
    std::size_t output_count = 0;
    check_litert(api_->get_num_signature_inputs(signature(signature_index), &input_count),
                 *api_, "query_inputs");
    check_litert(api_->get_num_signature_outputs(signature(signature_index), &output_count),
                 *api_, "query_outputs");
    if (input_count != inputs.size() || output_count == 0) {
      throw NativeError("graph_signature_shape_mismatch");
    }

    // Resize only a dynamic signature. LiteRT rejects resize requests for
    // fixed-shape T5 and rung graphs, even when the requested shape already
    // equals the baked shape.
    std::vector<litert::RankedTensorType> input_types(input_count);
    for (std::size_t i = 0; i < inputs.size(); ++i) {
      if (inputs[i].shape.empty()) continue;
      void* tensor = nullptr;
      check_litert(api_->get_signature_input_tensor_by_index(
                       signature(signature_index), i, &tensor),
                   *api_, "query_input_tensor");
      check_litert(api_->get_ranked_tensor_type(tensor, &input_types[i]), *api_,
                   "query_input_type");
      if (input_types[i].layout.rank != inputs[i].shape.size()) {
        throw NativeError("input_tensor_rank_mismatch");
      }
      bool dynamic = false;
      bool same = true;
      for (std::size_t dimension = 0; dimension < inputs[i].shape.size(); ++dimension) {
        const int32_t current = input_types[i].layout.dimensions[dimension];
        dynamic = dynamic || current < 0;
        same = same && current == inputs[i].shape[dimension];
      }
      if (dynamic) {
        check_litert(api_->resize_input_tensor_non_strict(
                         compiled_, signature_index, i, inputs[i].shape.data(),
                         inputs[i].shape.size()),
                     *api_, "resize_input");
      } else if (!same) {
        throw NativeError("fixed_input_tensor_shape_mismatch");
      }
    }

    std::vector<void*> input_tensors(input_count, nullptr);
    for (std::size_t i = 0; i < input_count; ++i) {
      check_litert(api_->get_signature_input_tensor_by_index(
                       signature(signature_index), i, &input_tensors[i]),
                   *api_, "query_input_tensor");
      check_litert(api_->get_ranked_tensor_type(input_tensors[i], &input_types[i]), *api_,
                   "query_input_type");
      // A variable-length signature keeps -1 in the model tensor type. The
      // compiled-model layout is the authoritative post-resize shape.
      check_litert(api_->get_compiled_model_input_tensor_layout(
                       compiled_, signature_index, i, &input_types[i].layout),
                   *api_, "query_input_layout");
      const std::size_t required =
          num_elements(input_types[i]) * element_size(input_types[i].element_type);
      if (required != inputs[i].bytes.size()) {
        throw NativeError("input_tensor_byte_size_mismatch");
      }
    }

    std::vector<litert::RankedTensorType> output_types(output_count);
    std::vector<litert::Layout> output_layouts(output_count);
    check_litert(api_->get_compiled_model_output_tensor_layouts(
                     compiled_, signature_index, output_count, output_layouts.data(), true),
                 *api_, "query_output_layouts");
    std::vector<void*> output_tensors(output_count, nullptr);
    std::vector<std::size_t> output_sizes(output_count);
    for (std::size_t i = 0; i < output_count; ++i) {
      check_litert(api_->get_signature_output_tensor_by_index(
                       signature(signature_index), i, &output_tensors[i]),
                   *api_, "query_output_tensor");
      check_litert(api_->get_ranked_tensor_type(output_tensors[i], &output_types[i]), *api_,
                   "query_output_type");
      output_types[i].layout = output_layouts[i];
      const std::size_t required =
          num_elements(output_types[i]) * element_size(output_types[i].element_type);
      output_sizes[i] = required;
    }

    std::vector<void*> input_buffers(input_count, nullptr);
    std::vector<void*> output_buffers(output_count, nullptr);
    try {
      for (std::size_t i = 0; i < input_count; ++i) {
        // LiteRT host buffers require 64-byte alignment and XNNPACK tail
        // padding. std::vector does not guarantee either; let LiteRT own the
        // allocation and release it through destroy_buffers on every path.
        check_litert(api_->create_managed_tensor_buffer(
                         environment_, litert::kHostMemory, &input_types[i],
                         inputs[i].bytes.size(), &input_buffers[i]),
                     *api_, "create_input_buffer");
        void* memory = nullptr;
        check_litert(api_->get_tensor_buffer_host_memory(input_buffers[i], &memory),
                     *api_, "map_input_buffer");
        check_not_null(memory, "input_buffer_unavailable");
        std::memcpy(memory, inputs[i].bytes.data(), inputs[i].bytes.size());
      }
      for (std::size_t i = 0; i < output_count; ++i) {
        check_litert(api_->create_managed_tensor_buffer(
                         environment_, litert::kHostMemory, &output_types[i],
                         output_sizes[i], &output_buffers[i]),
                     *api_, "create_output_buffer");
      }
      check_litert(api_->run_compiled_model(compiled_, signature_index, input_buffers.size(),
                                            input_buffers.data(), output_buffers.size(),
                                            output_buffers.data()),
                   *api_, "run_model");
      std::vector<TensorOutput> result(output_count);
      for (std::size_t i = 0; i < output_count; ++i) {
        void* memory = nullptr;
        check_litert(api_->get_tensor_buffer_host_memory(output_buffers[i], &memory), *api_,
                     "read_output");
        result[i].type = output_types[i];
        check_not_null(memory, "output_buffer_unavailable");
        result[i].bytes.resize(output_sizes[i]);
        std::memcpy(result[i].bytes.data(), memory, result[i].bytes.size());
      }
      destroy_buffers(input_buffers, output_buffers);
      return result;
    } catch (...) {
      destroy_buffers(input_buffers, output_buffers);
      throw;
    }
  }

 private:
  void* signature(std::size_t index) const {
    for (const Signature& candidate : signatures_) {
      if (candidate.index == index) {
        void* value = nullptr;
        check_litert(api_->get_model_signature(model_, index, &value), *api_,
                     "query_signature");
        return value;
      }
    }
    throw NativeError("signature_unavailable");
  }

  void discover_signatures() {
    std::size_t count = 0;
    check_litert(api_->get_num_model_signatures(model_, &count), *api_, "query_signatures");
    if (count == 0) throw NativeError("model_has_no_signatures");
    signatures_.reserve(count);
    for (std::size_t i = 0; i < count; ++i) {
      void* signature_handle = nullptr;
      check_litert(api_->get_model_signature(model_, i, &signature_handle), *api_,
                   "query_signature");
      const char* key = nullptr;
      check_litert(api_->get_signature_key(signature_handle, &key), *api_,
                   "query_signature_key");
      signatures_.push_back(Signature{i, key == nullptr ? "" : key});
    }
  }

  void destroy_buffers(const std::vector<void*>& input_buffers,
                       const std::vector<void*>& output_buffers) {
    for (void* buffer : input_buffers) {
      if (buffer != nullptr) api_->destroy_tensor_buffer(buffer);
    }
    for (void* buffer : output_buffers) {
      if (buffer != nullptr) api_->destroy_tensor_buffer(buffer);
    }
  }

  void destroy() {
    if (compiled_ != nullptr) api_->destroy_compiled_model(compiled_);
    if (model_ != nullptr) api_->destroy_model(model_);
    if (options_ != nullptr) api_->destroy_options(options_);
    if (environment_ != nullptr) api_->destroy_environment(environment_);
    compiled_ = nullptr;
    model_ = nullptr;
    options_ = nullptr;
    environment_ = nullptr;
    // LiteRT may retain the model buffer through compilation and graph
    // invocation. Unmap only after both model handles have been destroyed.
    mapped_model_.reset();
  }

  litert::Api* api_ = nullptr;
  void* environment_ = nullptr;
  void* options_ = nullptr;
  void* model_ = nullptr;
  void* compiled_ = nullptr;
  litert::ReadOnlyModelMapping mapped_model_;
  std::vector<Signature> signatures_;
};

struct TokenPiece {
  std::string text;
  float score = -std::numeric_limits<float>::infinity();
  int id = 0;
  bool usable = false;
};

class SentencePieceTokenizer final {
 public:
  explicit SentencePieceTokenizer(const std::string& path) {
    std::ifstream input(path, std::ios::binary);
    if (!input) throw NativeError("tokenizer_file_unavailable");
    std::string bytes((std::istreambuf_iterator<char>(input)),
                      std::istreambuf_iterator<char>());
    parse(bytes);
    if (pieces_.empty() || unknown_id_ < 0) {
      throw NativeError("tokenizer_model_invalid");
    }
    if (model_type_ != 2) throw NativeError("tokenizer_model_type_unsupported");
    if (precompiled_charsmap_) throw NativeError("tokenizer_normalizer_unsupported");
  }

  std::vector<int32_t> encode(const std::string& prompt) const {
    const std::string normalized = normalize(prompt);
    if (normalized.empty()) return {};

    // This follows sentencepiece's BPE model: start with UTF-8 code points
    // (or longest user-defined symbols), repeatedly merge the highest-scoring
    // adjacent pair, and resegment UNUSED merge pieces recursively.
    struct Symbol {
      std::string text;
      bool frozen = false;
    };
    std::vector<Symbol> symbols;
    std::unordered_map<std::string, std::pair<std::string, std::string>> merge_parts;
    for (std::size_t offset = 0; offset < normalized.size();) {
      std::size_t matched = 0;
      for (const std::string& candidate : user_defined_) {
        if (candidate.size() <= matched || candidate.size() > normalized.size() - offset) continue;
        if (normalized.compare(offset, candidate.size(), candidate) == 0) matched = candidate.size();
      }
      if (matched > 0) {
        symbols.push_back(Symbol{normalized.substr(offset, matched), true});
        offset += matched;
        continue;
      }
      const std::size_t width = utf8_width(static_cast<unsigned char>(normalized[offset]));
      if (width == 0 || offset + width > normalized.size()) {
        throw NativeError("tokenizer_invalid_utf8");
      }
      symbols.push_back(Symbol{normalized.substr(offset, width), false});
      offset += width;
    }

    while (symbols.size() > 1) {
      int best_index = -1;
      float best_score = -std::numeric_limits<float>::infinity();
      for (std::size_t index = 0; index + 1 < symbols.size(); ++index) {
        if (symbols[index].frozen || symbols[index + 1].frozen) continue;
        const std::string merged = symbols[index].text + symbols[index + 1].text;
        const auto found = piece_by_text_.find(merged);
        if (found == piece_by_text_.end()) continue;
        const float score = pieces_[static_cast<std::size_t>(found->second)].score;
        if (best_index < 0 || score > best_score) {
          best_index = static_cast<int>(index);
          best_score = score;
        }
      }
      if (best_index < 0) break;
      const std::string left = symbols[static_cast<std::size_t>(best_index)].text;
      const std::string right = symbols[static_cast<std::size_t>(best_index + 1)].text;
      Symbol merged;
      merged.text = left + right;
      const auto merged_piece = piece_by_text_.find(merged.text);
      if (merged_piece != piece_by_text_.end() &&
          unused_ids_.count(merged_piece->second) != 0) {
        merge_parts[merged.text] = std::make_pair(left, right);
      }
      symbols[static_cast<std::size_t>(best_index)] = std::move(merged);
      symbols.erase(symbols.begin() + best_index + 1);
    }

    std::vector<int32_t> ids;
    ids.reserve(symbols.size());
    for (const Symbol& symbol : symbols) append_piece(symbol.text, &ids, &merge_parts);
    if (ids.size() > kConditioningTokens) ids.resize(kConditioningTokens);
    return ids;
  }

 private:
  enum : uint64_t { kNormal = 1, kUnknown = 2, kControl = 3, kUserDefined = 4,
                    kUnused = 5, kByte = 6 };

  static std::size_t utf8_width(unsigned char value) {
    if (value < 0x80) return 1;
    if ((value & 0xE0) == 0xC0) return 2;
    if ((value & 0xF0) == 0xE0) return 3;
    if ((value & 0xF8) == 0xF0) return 4;
    return 0;
  }

  static bool read_varint(const std::string& bytes, std::size_t* offset, uint64_t* value) {
    uint64_t result = 0;
    for (int shift = 0; shift < 64 && *offset < bytes.size(); shift += 7) {
      const uint8_t byte = static_cast<uint8_t>(bytes[(*offset)++]);
      result |= static_cast<uint64_t>(byte & 0x7f) << shift;
      if ((byte & 0x80) == 0) {
        *value = result;
        return true;
      }
    }
    return false;
  }

  static bool read_length_delimited(const std::string& bytes, std::size_t* offset,
                                    std::string* value) {
    uint64_t length = 0;
    if (!read_varint(bytes, offset, &length) || length > bytes.size() - *offset) return false;
    *value = bytes.substr(*offset, static_cast<std::size_t>(length));
    *offset += static_cast<std::size_t>(length);
    return true;
  }

  static bool skip_field(const std::string& bytes, std::size_t* offset, int wire_type) {
    switch (wire_type) {
      case 0: {
        uint64_t ignored = 0;
        return read_varint(bytes, offset, &ignored);
      }
      case 1:
        if (bytes.size() - *offset < 8) return false;
        *offset += 8;
        return true;
      case 2: {
        uint64_t length = 0;
        if (!read_varint(bytes, offset, &length) || length > bytes.size() - *offset) return false;
        *offset += static_cast<std::size_t>(length);
        return true;
      }
      case 5:
        if (bytes.size() - *offset < 4) return false;
        *offset += 4;
        return true;
      default:
        return false;
    }
  }

  static uint64_t read_bool_or_enum(const std::string& bytes, std::size_t* offset) {
    uint64_t value = 0;
    if (!read_varint(bytes, offset, &value)) throw NativeError("tokenizer_model_invalid");
    return value;
  }

  static std::string byte_piece(unsigned int value) {
    char buffer[8] = {};
    std::snprintf(buffer, sizeof(buffer), "<0x%02X>", value & 0xffu);
    return std::string(buffer);
  }

  void parse_piece(const std::string& bytes) {
    TokenPiece piece;
    uint64_t type = kNormal;
    std::size_t offset = 0;
    while (offset < bytes.size()) {
      uint64_t tag = 0;
      if (!read_varint(bytes, &offset, &tag)) throw NativeError("tokenizer_model_invalid");
      const int field = static_cast<int>(tag >> 3);
      const int wire = static_cast<int>(tag & 7);
      if (field == 1 && wire == 2) {
        if (!read_length_delimited(bytes, &offset, &piece.text)) {
          throw NativeError("tokenizer_model_invalid");
        }
      } else if (field == 2 && wire == 5) {
        if (bytes.size() - offset < 4) throw NativeError("tokenizer_model_invalid");
        std::memcpy(&piece.score, bytes.data() + offset, sizeof(float));
        offset += sizeof(float);
      } else if (field == 3 && wire == 0) {
        type = read_bool_or_enum(bytes, &offset);
      } else if (!skip_field(bytes, &offset, wire)) {
        throw NativeError("tokenizer_model_invalid");
      }
    }
    if (piece.text.empty()) throw NativeError("tokenizer_model_invalid");
    if (!std::isfinite(piece.score)) piece.score = -10.0f;
    piece.id = static_cast<int>(pieces_.size());
    piece.usable = type == kNormal || type == kUserDefined || type == kUnused;
    pieces_.push_back(piece);
    if (type == kUnknown) unknown_id_ = piece.id;
    if (type == kUnused) unused_ids_.insert(piece.id);
    if (type == kByte && piece.text.size() == 6 && piece.text[0] == '<' &&
        piece.text[1] == '0' && piece.text[2] == 'x' && piece.text[5] == '>') {
      unsigned int value = 0;
      if (std::sscanf(piece.text.c_str(), "<0x%02X>", &value) == 1 && value < 256) {
        byte_ids_[value] = piece.id;
      }
    }
    if (piece.usable) {
      piece_by_text_.emplace(piece.text, piece.id);
      max_piece_bytes_ = std::max(max_piece_bytes_, piece.text.size());
      if (type == kUserDefined) user_defined_.push_back(piece.text);
    }
  }

  void parse_trainer_spec(const std::string& bytes) {
    std::size_t offset = 0;
    while (offset < bytes.size()) {
      uint64_t tag = 0;
      if (!read_varint(bytes, &offset, &tag)) throw NativeError("tokenizer_model_invalid");
      const int field = static_cast<int>(tag >> 3);
      const int wire = static_cast<int>(tag & 7);
      if (wire == 0 && field == 3) model_type_ = static_cast<int>(read_bool_or_enum(bytes, &offset));
      else if (wire == 0 && field == 35) byte_fallback_ = read_bool_or_enum(bytes, &offset) != 0;
      else if (wire == 0 && field == 40) unknown_id_ = static_cast<int>(read_bool_or_enum(bytes, &offset));
      else if (!skip_field(bytes, &offset, wire)) throw NativeError("tokenizer_model_invalid");
    }
  }

  void parse_normalizer_spec(const std::string& bytes) {
    std::size_t offset = 0;
    while (offset < bytes.size()) {
      uint64_t tag = 0;
      if (!read_varint(bytes, &offset, &tag)) throw NativeError("tokenizer_model_invalid");
      const int field = static_cast<int>(tag >> 3);
      const int wire = static_cast<int>(tag & 7);
      if (field == 2 && wire == 2) {
        std::string charsmap;
        if (!read_length_delimited(bytes, &offset, &charsmap)) {
          throw NativeError("tokenizer_model_invalid");
        }
        precompiled_charsmap_ = !charsmap.empty();
      } else if (wire == 0 && field == 3) add_dummy_prefix_ = read_bool_or_enum(bytes, &offset) != 0;
      else if (wire == 0 && field == 4) remove_extra_whitespaces_ = read_bool_or_enum(bytes, &offset) != 0;
      else if (wire == 0 && field == 5) escape_whitespaces_ = read_bool_or_enum(bytes, &offset) != 0;
      else if (!skip_field(bytes, &offset, wire)) throw NativeError("tokenizer_model_invalid");
    }
  }

  std::string normalize(const std::string& input) const {
    // The pinned SA3 tokenizer has no precompiled charsmap. SentencePiece's
    // normalizer replaces literal U+0020 only; tabs/newlines remain distinct
    // symbols and may be represented by user-defined or byte pieces.
    std::string result;
    result.reserve(input.size() + 3);
    const std::string space = "\xE2\x96\x81";
    if (add_dummy_prefix_ && !input.empty()) {
      result += escape_whitespaces_ ? space : " ";
    }
    bool previous_space = remove_extra_whitespaces_;
    for (std::size_t offset = 0; offset < input.size();) {
      const unsigned char value = static_cast<unsigned char>(input[offset]);
      const std::size_t width = utf8_width(value);
      if (width == 0 || offset + width > input.size()) throw NativeError("tokenizer_invalid_utf8");
      if (value == ' ') {
        if (!remove_extra_whitespaces_ || !previous_space) {
          result += escape_whitespaces_ ? space : " ";
        }
        previous_space = true;
      } else {
        result.append(input, offset, width);
        previous_space = false;
      }
      offset += width;
    }
    if (remove_extra_whitespaces_) {
      const std::string trailing = escape_whitespaces_ ? space : " ";
      while (result.size() >= trailing.size() &&
             result.compare(result.size() - trailing.size(), trailing.size(), trailing) == 0) {
        result.resize(result.size() - trailing.size());
      }
    }
    return result;
  }

  void append_piece(
      const std::string& text, std::vector<int32_t>* output,
      const std::unordered_map<std::string, std::pair<std::string, std::string>>* merge_parts) const {
    const auto found = piece_by_text_.find(text);
    if (found != piece_by_text_.end()) {
      const TokenPiece& piece = pieces_[static_cast<std::size_t>(found->second)];
      if (piece.usable && piece.id >= 0 && piece.id < static_cast<int>(pieces_.size())) {
        // UNUSED pieces are merge artifacts. Reproduce SentencePiece's
        // recursive resegmentation by splitting them into the known symbols
        // that formed the merge. A dynamic programming fallback handles old
        // models whose merge pieces are not marked UNUSED.
        if (unused_ids_.count(piece.id) == 0) {
          output->push_back(piece.id);
          return;
        }
        if (merge_parts != nullptr) {
          const auto split = merge_parts->find(text);
          if (split != merge_parts->end()) {
            append_piece(split->second.first, output, merge_parts);
            append_piece(split->second.second, output, merge_parts);
            return;
          }
        }
      }
    }
    if (!text.empty()) {
      bool matched = false;
      std::vector<int32_t> best;
      for (std::size_t split = 1; split < text.size(); ++split) {
        const std::string left = text.substr(0, split);
        const std::string right = text.substr(split);
        if (piece_by_text_.find(left) != piece_by_text_.end() &&
            piece_by_text_.find(right) != piece_by_text_.end()) {
          best.clear();
          append_piece(left, &best, merge_parts);
          append_piece(right, &best, merge_parts);
          matched = true;
          break;
        }
      }
      if (matched) {
        output->insert(output->end(), best.begin(), best.end());
        return;
      }
    }
    if (byte_fallback_) {
      for (unsigned char value : text) {
        if (byte_ids_[value] < 0) throw NativeError("tokenizer_byte_piece_missing");
        output->push_back(byte_ids_[value]);
      }
      return;
    }
    output->push_back(unknown_id_);
  }

  void parse(const std::string& bytes) {
    std::fill(std::begin(byte_ids_), std::end(byte_ids_), -1);
    std::size_t offset = 0;
    while (offset < bytes.size()) {
      uint64_t tag = 0;
      if (!read_varint(bytes, &offset, &tag)) throw NativeError("tokenizer_model_invalid");
      const int field = static_cast<int>(tag >> 3);
      const int wire = static_cast<int>(tag & 7);
      if (field == 1 && wire == 2) {
        std::string piece;
        if (!read_length_delimited(bytes, &offset, &piece)) throw NativeError("tokenizer_model_invalid");
        parse_piece(piece);
      } else if (field == 2 && wire == 2) {
        std::string spec;
        if (!read_length_delimited(bytes, &offset, &spec)) throw NativeError("tokenizer_model_invalid");
        parse_trainer_spec(spec);
      } else if (field == 3 && wire == 2) {
        std::string spec;
        if (!read_length_delimited(bytes, &offset, &spec)) throw NativeError("tokenizer_model_invalid");
        parse_normalizer_spec(spec);
      } else if (!skip_field(bytes, &offset, wire)) {
        throw NativeError("tokenizer_model_invalid");
      }
    }
    std::sort(user_defined_.begin(), user_defined_.end(),
              [](const std::string& left, const std::string& right) {
                return left.size() > right.size();
              });
    if (unknown_id_ < 0 || unknown_id_ >= static_cast<int>(pieces_.size())) {
      unknown_id_ = -1;
      for (const TokenPiece& piece : pieces_) {
        if (piece.id >= 0 && !piece.usable) {
          unknown_id_ = piece.id;
          break;
        }
      }
    }
  }

  std::vector<TokenPiece> pieces_;
  std::unordered_map<std::string, int> piece_by_text_;
  std::vector<std::string> user_defined_;
  std::unordered_set<int> unused_ids_;
  std::size_t max_piece_bytes_ = 1;
  int unknown_id_ = -1;
  int model_type_ = 1;
  bool byte_fallback_ = false;
  bool add_dummy_prefix_ = true;
  bool remove_extra_whitespaces_ = true;
  bool escape_whitespaces_ = true;
  bool precompiled_charsmap_ = false;
  int byte_ids_[256] = {};
};

struct TokenBatch {
  std::vector<int32_t> ids;
  std::vector<int32_t> mask;
};

TokenBatch tokenize(const SentencePieceTokenizer& tokenizer, const std::string& text) {
  TokenBatch result;
  result.ids.assign(kConditioningTokens, 0);
  result.mask.assign(kConditioningTokens, 0);
  const std::vector<int32_t> pieces = tokenizer.encode(text);
  const std::size_t count = std::min<std::size_t>(pieces.size(), kConditioningTokens);
  std::copy(pieces.begin(), pieces.begin() + count, result.ids.begin());
  std::fill(result.mask.begin(), result.mask.begin() + count, 1);
  return result;
}

struct StereoAudio {
  std::vector<float> samples;  // planar: left[0..N), right[N..2N)
  std::size_t frames = 0;
};

uint16_t read_u16(const std::vector<uint8_t>& bytes, std::size_t offset) {
  if (offset + 2 > bytes.size()) throw NativeError("input_audio_truncated");
  return static_cast<uint16_t>(bytes[offset]) |
      (static_cast<uint16_t>(bytes[offset + 1]) << 8);
}

uint32_t read_u32(const std::vector<uint8_t>& bytes, std::size_t offset) {
  if (offset + 4 > bytes.size()) throw NativeError("input_audio_truncated");
  return static_cast<uint32_t>(bytes[offset]) |
      (static_cast<uint32_t>(bytes[offset + 1]) << 8) |
      (static_cast<uint32_t>(bytes[offset + 2]) << 16) |
      (static_cast<uint32_t>(bytes[offset + 3]) << 24);
}

StereoAudio read_pcm16_wav(const std::string& path) {
  std::ifstream input(path, std::ios::binary);
  if (!input) throw NativeError("input_audio_unavailable");
  std::vector<uint8_t> bytes((std::istreambuf_iterator<char>(input)),
                             std::istreambuf_iterator<char>());
  if (bytes.size() < 12 || std::memcmp(bytes.data(), "RIFF", 4) != 0 ||
      std::memcmp(bytes.data() + 8, "WAVE", 4) != 0) {
    throw NativeError("input_audio_not_wav");
  }
  uint16_t channels = 0;
  uint16_t bits = 0;
  uint32_t sample_rate = 0;
  std::size_t data_offset = 0;
  std::size_t data_size = 0;
  std::size_t cursor = 12;
  while (cursor + 8 <= bytes.size()) {
    const uint32_t chunk_size = read_u32(bytes, cursor + 4);
    const std::size_t payload = cursor + 8;
    if (chunk_size > bytes.size() - payload) throw NativeError("input_audio_truncated");
    if (std::memcmp(bytes.data() + cursor, "fmt ", 4) == 0) {
      if (chunk_size < 16) throw NativeError("input_audio_format_invalid");
      const uint16_t format = read_u16(bytes, payload);
      channels = read_u16(bytes, payload + 2);
      sample_rate = read_u32(bytes, payload + 4);
      bits = read_u16(bytes, payload + 14);
      if (format != 1 || (channels != 1 && channels != 2) || sample_rate != kSampleRate ||
          bits != 16) {
        throw NativeError("input_audio_requires_pcm16_44100");
      }
    } else if (std::memcmp(bytes.data() + cursor, "data", 4) == 0) {
      data_offset = payload;
      data_size = chunk_size;
      break;
    }
    cursor = payload + chunk_size + (chunk_size & 1u);
  }
  if (channels == 0 || data_offset == 0 || data_size == 0 || data_size % (channels * 2) != 0) {
    throw NativeError("input_audio_missing_data");
  }
  const std::size_t frames = data_size / (channels * 2);
  StereoAudio result;
  result.frames = frames;
  result.samples.assign(frames * 2, 0.0f);
  for (std::size_t frame = 0; frame < frames; ++frame) {
    const std::size_t base = data_offset + frame * channels * 2;
    const auto read_sample = [&](std::size_t channel) {
      const int16_t value = static_cast<int16_t>(read_u16(bytes, base + channel * 2));
      return static_cast<float>(value) / 32768.0f;
    };
    const float left = read_sample(0);
    result.samples[frame] = left;
    result.samples[frames + frame] = channels == 1 ? left : read_sample(1);
  }
  return result;
}

std::vector<uint8_t> planar_audio_bytes(const StereoAudio& audio, std::size_t frames) {
  std::vector<float> planar(frames * 2, 0.0f);
  const std::size_t copied = std::min(frames, audio.frames);
  if (copied > 0) {
    std::copy(audio.samples.begin(), audio.samples.begin() + copied, planar.begin());
    std::copy(audio.samples.begin() + audio.frames,
              audio.samples.begin() + audio.frames + copied,
              planar.begin() + frames);
    if (copied < frames) {
      const float left = planar[copied - 1];
      const float right = planar[frames + copied - 1];
      std::fill(planar.begin() + copied, planar.begin() + frames, left);
      std::fill(planar.begin() + frames + copied, planar.end(), right);
    }
  }
  return bytes_from_float(planar);
}

void write_pcm16_wav(const std::string& path, const std::vector<float>& planar,
                     std::size_t frames) {
  if (planar.size() < frames * 2) throw NativeError("output_audio_shape_invalid");
  std::ofstream output(path, std::ios::binary | std::ios::trunc);
  if (!output) throw NativeError("output_audio_unavailable");
  const uint32_t data_size = static_cast<uint32_t>(frames * 2 * sizeof(int16_t));
  const uint32_t riff_size = 36 + data_size;
  auto put16 = [&](uint16_t value) {
    output.put(static_cast<char>(value & 0xff));
    output.put(static_cast<char>((value >> 8) & 0xff));
  };
  auto put32 = [&](uint32_t value) {
    output.put(static_cast<char>(value & 0xff));
    output.put(static_cast<char>((value >> 8) & 0xff));
    output.put(static_cast<char>((value >> 16) & 0xff));
    output.put(static_cast<char>((value >> 24) & 0xff));
  };
  output.write("RIFF", 4);
  put32(riff_size);
  output.write("WAVEfmt ", 8);
  put32(16);
  put16(1);       // PCM
  put16(2);       // stereo
  put32(kSampleRate);
  put32(kSampleRate * 2 * sizeof(int16_t));
  put16(2 * sizeof(int16_t));
  put16(16);
  output.write("data", 4);
  put32(data_size);
  for (std::size_t frame = 0; frame < frames; ++frame) {
    for (int channel = 0; channel < 2; ++channel) {
      const float value = std::clamp(planar[channel * frames + frame], -1.0f, 1.0f);
      const int16_t sample = static_cast<int16_t>(std::lrint(value * 32767.0f));
      put16(static_cast<uint16_t>(sample));
    }
  }
  if (!output) throw NativeError("output_audio_write_failed");
}

float half_to_float(uint16_t bits) {
  const uint32_t sign = static_cast<uint32_t>(bits & 0x8000u) << 16;
  const uint32_t exponent = (bits >> 10) & 0x1fu;
  const uint32_t mantissa = bits & 0x03ffu;
  uint32_t value = 0;
  if (exponent == 0) {
    if (mantissa == 0) {
      value = sign;
    } else {
      // Normalize the subnormal mantissa before converting to the fp32 form.
      uint32_t m = mantissa;
      int e = -1;
      do {
        m <<= 1;
        --e;
      } while ((m & 0x0400u) == 0);
      m &= 0x03ffu;
      value = sign | static_cast<uint32_t>(127 - 15 + 1 + e) << 23 | m << 13;
    }
  } else if (exponent == 0x1fu) {
    value = sign | 0x7f800000u | (mantissa << 13);
  } else {
    value = sign | ((exponent + 112u) << 23) | (mantissa << 13);
  }
  float result = 0.0f;
  std::memcpy(&result, &value, sizeof(result));
  return result;
}

std::vector<float> floats_from_output(const TensorOutput& output) {
  if (output.type.element_type == litert::kFloat32 &&
      output.bytes.size() % sizeof(float) == 0) {
    std::vector<float> result(output.bytes.size() / sizeof(float));
    if (!result.empty()) std::memcpy(result.data(), output.bytes.data(), output.bytes.size());
    return result;
  }
  if (output.type.element_type == litert::kFloat16 &&
      output.bytes.size() % sizeof(uint16_t) == 0) {
    std::vector<float> result(output.bytes.size() / sizeof(uint16_t));
    for (std::size_t i = 0; i < result.size(); ++i) {
      uint16_t value = 0;
      std::memcpy(&value, output.bytes.data() + i * sizeof(value), sizeof(value));
      result[i] = half_to_float(value);
    }
    return result;
  }
  throw NativeError("graph_float_output_required");
}

class RungCodec final {
 public:
  RungCodec(litert::Api* api, const std::string& path, bool encoder, int trim, int max_rung,
            int threads)
      : graph_(std::make_unique<LiteRtGraph>(api, path, threads)), encoder_(encoder), trim_(trim) {
    for (const auto& signature : graph_->signatures()) {
      if (signature.key.size() <= 1 || signature.key[0] != 's') continue;
      try {
        const int rung = std::stoi(signature.key.substr(1));
        if (rung > 0) sizes_.push_back(rung);
      } catch (...) {
        // Ignore auxiliary signatures; the shipped codec uses s<N> rungs.
      }
    }
    std::sort(sizes_.begin(), sizes_.end());
    sizes_.erase(std::unique(sizes_.begin(), sizes_.end()), sizes_.end());
    if (sizes_.empty()) throw NativeError("codec_has_no_rungs");
    if (max_rung > 0) {
      std::vector<int> capped;
      for (int size : sizes_) if (size <= max_rung) capped.push_back(size);
      sizes_ = capped.empty() ? std::vector<int>{sizes_.front()} : std::move(capped);
    }
  }

  std::vector<float> encode(const std::vector<float>& audio, int length) {
    if (!encoder_) throw NativeError("codec_direction_invalid");
    if (audio.size() != static_cast<std::size_t>(2 * length * kSamplesPerLatent)) {
      throw NativeError("encoder_audio_shape_invalid");
    }
    const int window = best_rung(length);
    if (length <= window) return run_encode(audio, length, window);
    const int step = window - trim_ * 2;
    if (step <= 0) throw NativeError("encoder_rung_not_tileable");
    std::vector<float> output(static_cast<std::size_t>(256 * length), 0.0f);
    std::vector<int> starts;
    for (int start = 0; start < length - window; start += step) starts.push_back(start);
    if (starts.empty() || starts.back() != length - window) starts.push_back(length - window);
    for (std::size_t index = 0; index < starts.size(); ++index) {
      const int start = starts[index];
      std::vector<float> slice(static_cast<std::size_t>(2 * window * kSamplesPerLatent));
      // Audio is planar. A single contiguous copy would splice the tail of
      // the left channel into the beginning of the right channel.
      for (int channel = 0; channel < 2; ++channel) {
        const std::size_t source = static_cast<std::size_t>(channel * length * kSamplesPerLatent +
            start * kSamplesPerLatent);
        const std::size_t target = static_cast<std::size_t>(channel * window * kSamplesPerLatent);
        std::copy(audio.begin() + source,
                  audio.begin() + source + window * kSamplesPerLatent,
                  slice.begin() + target);
      }
      std::vector<float> encoded = run_encode(slice, window, window);
      const int low = index == 0 ? 0 : trim_;
      const int high = start + window >= length ? window : window - trim_;
      for (int channel = 0; channel < 256; ++channel) {
        std::copy(encoded.begin() + static_cast<std::size_t>(channel * window + low),
                  encoded.begin() + static_cast<std::size_t>(channel * window + high),
                  output.begin() + static_cast<std::size_t>(channel * length + start + low));
      }
    }
    return output;
  }

  std::vector<float> decode(const std::vector<float>& latents, int length) {
    if (encoder_) throw NativeError("codec_direction_invalid");
    if (latents.size() != static_cast<std::size_t>(256 * length)) {
      throw NativeError("decoder_latent_shape_invalid");
    }
    const int window = best_rung(length);
    if (length <= window) return run_decode(latents, length, window);
    const int step = window - trim_ * 2;
    if (step <= 0) throw NativeError("decoder_rung_not_tileable");
    std::vector<float> output(static_cast<std::size_t>(2 * length * kSamplesPerLatent), 0.0f);
    std::vector<int> starts;
    for (int start = 0; start < length - window; start += step) starts.push_back(start);
    if (starts.empty() || starts.back() != length - window) starts.push_back(length - window);
    for (std::size_t index = 0; index < starts.size(); ++index) {
      const int start = starts[index];
      std::vector<float> slice(static_cast<std::size_t>(256 * window));
      for (int channel = 0; channel < 256; ++channel) {
        std::copy(latents.begin() + static_cast<std::size_t>(channel * length + start),
                  latents.begin() + static_cast<std::size_t>(channel * length + start + window),
                  slice.begin() + static_cast<std::size_t>(channel * window));
      }
      std::vector<float> decoded = run_decode(slice, window, window);
      const int low = index == 0 ? 0 : trim_;
      const int high = start + window >= length ? window : window - trim_;
      for (int channel = 0; channel < 2; ++channel) {
        std::copy(decoded.begin() + static_cast<std::size_t>(channel * window * kSamplesPerLatent + low * kSamplesPerLatent),
                  decoded.begin() + static_cast<std::size_t>(channel * window * kSamplesPerLatent + high * kSamplesPerLatent),
                  output.begin() + static_cast<std::size_t>(channel * length * kSamplesPerLatent +
                      (start + low) * kSamplesPerLatent));
      }
    }
    return output;
  }

 private:
  bool tileable(int rung) const { return rung - 2 * trim_ > 0; }

  int best_rung(int length) const {
    std::pair<int, int> best{std::numeric_limits<int>::max(), std::numeric_limits<int>::max()};
    int best_size = -1;
    for (int rung : sizes_) {
      if (rung == length) {
        const std::pair<int, int> candidate{rung, 1};
        if (candidate < best) { best = candidate; best_size = rung; }
      } else if (rung < length && tileable(rung)) {
        const int step = rung - 2 * trim_;
        const int windows = step > 0 ? std::max(1, (length - rung + step - 1) / step) : 1'000'000;
        const std::pair<int, int> candidate{windows * rung, windows};
        if (candidate < best) { best = candidate; best_size = rung; }
      }
    }
    if (best_size > 0) return best_size;
    for (int rung : sizes_) if (rung >= length) return rung;
    return sizes_.back();
  }

  std::vector<float> run_encode(const std::vector<float>& audio, int actual_length, int rung) {
    std::vector<float> padded(static_cast<std::size_t>(2 * rung * kSamplesPerLatent), 0.0f);
    const std::size_t source = static_cast<std::size_t>(2 * actual_length * kSamplesPerLatent);
    std::copy(audio.begin(), audio.begin() + std::min(audio.size(), source), padded.begin());
    if (actual_length < rung && actual_length > 0) {
      for (int channel = 0; channel < 2; ++channel) {
        const float* last = padded.data() + channel * rung * kSamplesPerLatent +
            (actual_length - 1) * kSamplesPerLatent;
        for (int frame = actual_length; frame < rung; ++frame) {
          std::copy(last, last + kSamplesPerLatent,
                    padded.data() + channel * rung * kSamplesPerLatent + frame * kSamplesPerLatent);
        }
      }
    }
    const auto result = graph_->invoke(graph_->signature_for_rung(rung), {
        TensorInput{bytes_from_float(padded), {1, 2, rung * kSamplesPerLatent}}});
    if (result.empty()) throw NativeError("encoder_output_missing");
    const std::vector<float> values = floats_from_output(result.front());
    if (values.size() < static_cast<std::size_t>(256 * rung)) {
      throw NativeError("encoder_output_shape_invalid");
    }
    return std::vector<float>(values.begin(), values.begin() + 256 * actual_length);
  }

  std::vector<float> run_decode(const std::vector<float>& latents, int actual_length, int rung) {
    std::vector<float> padded(static_cast<std::size_t>(256 * rung), 0.0f);
    std::copy(latents.begin(), latents.end(), padded.begin());
    if (actual_length < rung && actual_length > 0) {
      for (int channel = 0; channel < 256; ++channel) {
        const float value = padded[static_cast<std::size_t>(channel * rung + actual_length - 1)];
        std::fill(padded.begin() + channel * rung + actual_length,
                  padded.begin() + (channel + 1) * rung, value);
      }
    }
    const auto result = graph_->invoke(graph_->signature_for_rung(rung), {
        TensorInput{bytes_from_float(padded), {1, 256, rung}}});
    if (result.empty()) throw NativeError("decoder_output_missing");
    const std::vector<float> values = floats_from_output(result.front());
    const std::size_t required = static_cast<std::size_t>(2 * rung * kSamplesPerLatent);
    if (values.size() < required) throw NativeError("decoder_output_shape_invalid");
    return std::vector<float>(values.begin(), values.begin() +
        static_cast<std::size_t>(2 * actual_length * kSamplesPerLatent));
  }

  std::unique_ptr<LiteRtGraph> graph_;
  bool encoder_ = false;
  int trim_ = 12;
  std::vector<int> sizes_;
};

class ProgressReporter final {
 public:
  ProgressReporter(JNIEnv* env, jobject sink) : env_(env), sink_(sink) {
    if (env_ == nullptr || sink_ == nullptr) throw NativeError("progress_sink_missing");
    jclass local_class = env_->GetObjectClass(sink_);
    if (local_class == nullptr) throw NativeError("progress_sink_invalid");
    method_ = env_->GetMethodID(local_class, "onProgress", "(Ljava/lang/String;II)V");
    env_->DeleteLocalRef(local_class);
    if (method_ == nullptr) throw NativeError("progress_sink_invalid");
  }

  void emit(const char* stage, int completed, int total) {
    if (g_cancelled.load(std::memory_order_relaxed)) throw NativeError("cancelled");
    jstring value = env_->NewStringUTF(stage == nullptr ? "" : stage);
    if (value == nullptr) throw NativeError("progress_callback_failed");
    env_->CallVoidMethod(sink_, method_, value, completed, total);
    env_->DeleteLocalRef(value);
    if (env_->ExceptionCheck()) {
      env_->ExceptionClear();
      throw NativeError("progress_callback_failed");
    }
  }

 private:
  JNIEnv* env_ = nullptr;
  jobject sink_ = nullptr;
  jmethodID method_ = nullptr;
};

class TextEncoder final {
 public:
  TextEncoder(litert::Api* api, const std::string& path, int threads)
      : graph_(std::make_unique<LiteRtGraph>(api, path, threads)), threads_(threads) {}

  std::vector<float> encode(const std::vector<int32_t>& ids,
                            const std::vector<int32_t>& mask) {
    if (ids.size() != kConditioningTokens || mask.size() != kConditioningTokens) {
      throw NativeError("text_encoder_input_shape_invalid");
    }
    const std::size_t signature = graph_->default_signature();
    std::vector<LiteRtGraph::InputDescriptor> descriptors = graph_->inputs(signature);
    if (descriptors.size() != 2) throw NativeError("text_encoder_signature_invalid");
    std::sort(descriptors.begin(), descriptors.end(), [](const auto& left, const auto& right) {
      return left.name < right.name;
    });
    std::vector<TensorInput> inputs(2);
    // The upstream exported graph names args_0=input_ids and args_1=attention_mask.
    // Sorting by signature name matches its Python reference and avoids relying
    // on FlatBuffer tensor enumeration order.
    inputs[descriptors[0].index] =
        TensorInput{bytes_from_int32(ids), {1, kConditioningTokens}};
    inputs[descriptors[1].index] =
        TensorInput{bytes_from_int32(mask), {1, kConditioningTokens}};
    const std::vector<TensorOutput> outputs = graph_->invoke(signature, inputs);
    if (outputs.empty()) throw NativeError("text_encoder_output_missing");
    std::vector<float> hidden = floats_from_output(outputs.front());
    if (hidden.size() < static_cast<std::size_t>(kConditioningTokens * kConditioningDimension)) {
      throw NativeError("text_encoder_output_shape_invalid");
    }
    hidden.resize(static_cast<std::size_t>(kConditioningTokens * kConditioningDimension));
    return hidden;
  }

 private:
  std::unique_ptr<LiteRtGraph> graph_;
  int threads_ = 1;
};

std::vector<float> repeat_vector(const std::vector<float>& source, int repeats) {
  if (repeats <= 0) throw NativeError("invalid_batch");
  std::vector<float> result(source.size() * static_cast<std::size_t>(repeats));
  for (int batch = 0; batch < repeats; ++batch) {
    std::copy(source.begin(), source.end(),
              result.begin() + static_cast<std::size_t>(batch) * source.size());
  }
  return result;
}

std::vector<int32_t> repeat_int_vector(const std::vector<int32_t>& source, int repeats) {
  if (repeats <= 0) throw NativeError("invalid_batch");
  std::vector<int32_t> result(source.size() * static_cast<std::size_t>(repeats));
  for (int batch = 0; batch < repeats; ++batch) {
    std::copy(source.begin(), source.end(),
              result.begin() + static_cast<std::size_t>(batch) * source.size());
  }
  return result;
}

class DiTRunner final {
 public:
  DiTRunner(litert::Api* api, const std::string& path, int threads,
            int latent_length, float duration_seconds)
      : graph_(std::make_unique<LiteRtGraph>(api, path, threads)), latent_length_(latent_length),
        duration_seconds_(duration_seconds), threads_(threads) {
    signature_ = graph_->default_signature();
    descriptors_ = graph_->inputs(signature_);
    if (descriptors_.size() != 6) throw NativeError("dit_signature_invalid");
  }

  std::vector<float> forward(const std::vector<float>& x, float time,
                             const std::vector<float>& cond,
                             const std::vector<float>& cond_mask,
                             const std::vector<float>& null_cond,
                             const std::vector<float>& null_mask,
                             const std::vector<float>& local_add_cond,
                             float cfg, float apg, bool cfg_batched) {
    if (x.size() != static_cast<std::size_t>(kConditioningTokens * latent_length_)) {
      throw NativeError("dit_latent_shape_invalid");
    }
    if (cond.size() != static_cast<std::size_t>(kConditioningTokens * kConditioningDimension) ||
        cond_mask.size() != kConditioningTokens) {
      throw NativeError("dit_conditioning_shape_invalid");
    }
    const bool guided = std::abs(cfg - 1.0f) > 1e-6f;
    if (!guided) {
      return invoke_batch(x, 1, time, cond, cond_mask, cond,
                          cond_mask, local_add_cond);
    }
    if (null_cond.size() != cond.size() || null_mask.size() != cond_mask.size()) {
      throw NativeError("dit_unconditional_shape_invalid");
    }
    if (cfg_batched) {
      const std::vector<float> both = invoke_batch(x, 2, time, cond, cond_mask,
                                                    null_cond, null_mask, local_add_cond);
      const std::size_t width = x.size();
      if (both.size() != width * 2) throw NativeError("dit_output_shape_invalid");
      std::vector<float> cond_velocity(both.begin(), both.begin() + width);
      std::vector<float> null_velocity(both.begin() + width, both.end());
      return apply_cfg(x, time, cond_velocity, null_velocity, cfg, apg);
    }
    const std::vector<float> cond_velocity = invoke_batch(x, 1, time, cond, cond_mask,
                                                           cond, cond_mask, local_add_cond);
    const std::vector<float> null_velocity = invoke_batch(x, 1, time, null_cond, null_mask,
                                                           null_cond, null_mask, local_add_cond);
    return apply_cfg(x, time, cond_velocity, null_velocity, cfg, apg);
  }

 private:
  enum class TensorKind { X, Local, Hidden, Mask, Time, Seconds };

  TensorKind classify(const LiteRtGraph::InputDescriptor& descriptor,
                      int scalar_index) const {
    const auto& layout = descriptor.type.layout;
    if (layout.rank == 3) {
      if (layout.dimensions[1] == 257) return TensorKind::Local;
      if (layout.dimensions[2] == kConditioningDimension) return TensorKind::Hidden;
      if (layout.dimensions[1] == kConditioningTokens) return TensorKind::X;
    } else if (layout.rank == 2) {
      return TensorKind::Mask;
    } else if (layout.rank == 1) {
      if (descriptor.name.find("args_4") != std::string::npos ||
          descriptor.name.find("second") != std::string::npos) {
        return TensorKind::Seconds;
      }
      if (descriptor.name.find("args_1") != std::string::npos ||
          descriptor.name.find("time") != std::string::npos || scalar_index == 0) {
        return TensorKind::Time;
      }
      return TensorKind::Seconds;
    }
    throw NativeError("dit_input_shape_invalid");
  }

  std::vector<float> invoke_batch(const std::vector<float>& x, int batch, float time,
                                  const std::vector<float>& cond,
                                  const std::vector<float>& cond_mask,
                                  const std::vector<float>& second_cond,
                                  const std::vector<float>& second_mask,
                                  const std::vector<float>& local) {
    const std::size_t width = x.size();
    const std::size_t local_width = static_cast<std::size_t>(257 * latent_length_);
    if (local.empty()) {
      // The baked graph still expects the local conditioning tensor. The
      // reference pipeline feeds zeros for text-only generation.
      throw NativeError("dit_local_conditioning_missing");
    }
    if (local.size() != local_width) throw NativeError("dit_local_conditioning_shape_invalid");

    std::vector<TensorInput> ordered(descriptors_.size());
    int scalar_index = 0;
    std::vector<float> x_batched = repeat_vector(x, batch);
    std::vector<float> local_batched = repeat_vector(local, batch);
    std::vector<float> cond_batched;
    std::vector<float> mask_batched;
    if (batch == 1) {
      cond_batched = cond;
      mask_batched = cond_mask;
    } else {
      cond_batched.reserve(cond.size() + second_cond.size());
      cond_batched.insert(cond_batched.end(), cond.begin(), cond.end());
      cond_batched.insert(cond_batched.end(), second_cond.begin(), second_cond.end());
      mask_batched.reserve(cond_mask.size() + second_mask.size());
      mask_batched.insert(mask_batched.end(), cond_mask.begin(), cond_mask.end());
      mask_batched.insert(mask_batched.end(), second_mask.begin(), second_mask.end());
    }
    for (const auto& descriptor : descriptors_) {
      const TensorKind kind = classify(descriptor, scalar_index);
      switch (kind) {
        case TensorKind::X:
          ordered[descriptor.index] = TensorInput{
              bytes_from_float(x_batched), {batch, kConditioningTokens, latent_length_}};
          break;
        case TensorKind::Local:
          ordered[descriptor.index] = TensorInput{
              bytes_from_float(local_batched), {batch, 257, latent_length_}};
          break;
        case TensorKind::Hidden:
          ordered[descriptor.index] = TensorInput{
              bytes_from_float(cond_batched), {batch, kConditioningTokens, kConditioningDimension}};
          break;
        case TensorKind::Mask:
          ordered[descriptor.index] = TensorInput{
              bytes_from_float(mask_batched), {batch, kConditioningTokens}};
          break;
        case TensorKind::Time: {
          ++scalar_index;
          std::vector<float> values(static_cast<std::size_t>(batch), time);
          ordered[descriptor.index] = TensorInput{bytes_from_float(values), {batch}};
          break;
        }
        case TensorKind::Seconds: {
          ++scalar_index;
          std::vector<float> values(static_cast<std::size_t>(batch), duration_seconds_);
          ordered[descriptor.index] = TensorInput{bytes_from_float(values), {batch}};
          break;
        }
      }
    }
    const auto outputs = graph_->invoke(signature_, ordered);
    if (outputs.empty()) throw NativeError("dit_output_missing");
    std::vector<float> values = floats_from_output(outputs.front());
    if (values.size() != width * static_cast<std::size_t>(batch)) {
      throw NativeError("dit_output_shape_invalid");
    }
    return values;
  }

  static std::vector<float> apply_cfg(const std::vector<float>& x, float time,
                                      const std::vector<float>& cond,
                                      const std::vector<float>& uncond,
                                      float cfg, float apg) {
    if (cond.size() != x.size() || uncond.size() != x.size()) {
      throw NativeError("dit_guidance_shape_invalid");
    }
    const float sigma = time;
    if (!std::isfinite(sigma) || sigma <= 1e-8f) throw NativeError("dit_invalid_sigma");
    std::vector<float> cond_d(x.size());
    std::vector<float> uncond_d(x.size());
    std::vector<float> diff(x.size());
    float norm_squared = 0.0f;
    for (std::size_t i = 0; i < x.size(); ++i) {
      cond_d[i] = x[i] - cond[i] * sigma;
      uncond_d[i] = x[i] - uncond[i] * sigma;
      diff[i] = cond_d[i] - uncond_d[i];
      norm_squared += cond_d[i] * cond_d[i];
    }
    std::vector<float> cfg_diff(x.size());
    if (apg <= 0.0f) {
      cfg_diff = diff;
    } else {
      const float inverse_norm = 1.0f / std::max(std::sqrt(norm_squared), 1e-8f);
      float dot = 0.0f;
      for (std::size_t i = 0; i < x.size(); ++i) dot += diff[i] * cond_d[i] * inverse_norm;
      for (std::size_t i = 0; i < x.size(); ++i) {
        const float parallel = dot * cond_d[i] * inverse_norm;
        const float orthogonal = diff[i] - parallel;
        cfg_diff[i] = apg >= 1.0f ? orthogonal : apg * orthogonal + (1.0f - apg) * diff[i];
      }
    }
    std::vector<float> velocity(x.size());
    for (std::size_t i = 0; i < x.size(); ++i) {
      const float denoised = cond_d[i] + (cfg - 1.0f) * cfg_diff[i];
      velocity[i] = (x[i] - denoised) / sigma;
    }
    return velocity;
  }

  std::unique_ptr<LiteRtGraph> graph_;
  std::size_t signature_ = 0;
  std::vector<LiteRtGraph::InputDescriptor> descriptors_;
  int latent_length_ = 1;
  float duration_seconds_ = 1.0f;
  int threads_ = 1;
};

struct NativeGenerationResult {
  std::string output_path;
  int latent_length = 0;
  long long duration_ms = 0;
};

float logsnr_shift(float value) {
  constexpr float anchor = -6.2f;
  constexpr float end = 2.0f;
  if (value <= 0.0f) return 0.0f;
  if (value >= 1.0f) return 1.0f;
  const float logsnr = end - value * (end - anchor);
  return 1.0f / (1.0f + std::exp(logsnr));
}

std::vector<float> build_pingpong_schedule(int steps, float sigma_max) {
  if (steps <= 0) throw NativeError("invalid_sampling_steps");
  std::vector<float> result(static_cast<std::size_t>(steps + 1));
  for (int index = 0; index <= steps; ++index) {
    const float normalized = 1.0f - static_cast<float>(index) / static_cast<float>(steps);
    result[static_cast<std::size_t>(index)] = logsnr_shift(normalized) * sigma_max;
  }
  result.front() = sigma_max;
  result.back() = 0.0f;
  return result;
}

class DeterministicNoise final {
 public:
  explicit DeterministicNoise(uint64_t seed) : generator_(seed) {}

  float normal() {
    if (has_spare_) {
      has_spare_ = false;
      return spare_;
    }
    std::uniform_real_distribution<float> uniform(1.0e-7f, 1.0f);
    const float u1 = uniform(generator_);
    const float u2 = uniform(generator_);
    const float radius = std::sqrt(-2.0f * std::log(u1));
    const float angle = 2.0f * kPi * u2;
    spare_ = radius * std::sin(angle);
    has_spare_ = true;
    return radius * std::cos(angle);
  }

  std::vector<float> tensor(std::size_t size) {
    std::vector<float> result(size);
    for (float& value : result) value = normal();
    return result;
  }

 private:
  std::mt19937_64 generator_;
  bool has_spare_ = false;
  float spare_ = 0.0f;
};

std::string json_escape(const std::string& value) {
  std::string result;
  result.reserve(value.size() + 8);
  for (unsigned char character : value) {
    switch (character) {
      case '\\': result += "\\\\"; break;
      case '"': result += "\\\""; break;
      case '\n': result += "\\n"; break;
      case '\r': result += "\\r"; break;
      case '\t': result += "\\t"; break;
      default:
        if (character < 0x20) {
          result += "?";
        } else {
          result.push_back(static_cast<char>(character));
        }
    }
  }
  return result;
}

std::string build_result_json(const NativeGenerationResult& result) {
  std::ostringstream json;
  json << "{\"outputPath\":\"" << json_escape(result.output_path)
       << "\",\"sampleRate\":" << kSampleRate
       << ",\"channels\":2,\"durationMs\":" << result.duration_ms
       << ",\"latentLength\":" << result.latent_length
       << ",\"metadataJson\":\"{}\"}";
  return json.str();
}

void copy_or_zero_pad_audio(const StereoAudio& input, std::vector<float>* output,
                            std::size_t frames) {
  output->assign(frames * 2, 0.0f);
  const std::size_t copied = std::min(input.frames, frames);
  for (int channel = 0; channel < 2; ++channel) {
    const float* source = input.samples.data() + channel * input.frames;
    float* target = output->data() + channel * frames;
    if (copied > 0) std::copy(source, source + copied, target);
  }
}

std::vector<float> repeat_latent_last(const std::vector<float>& latent, int length,
                                      int new_length) {
  if (new_length < length || latent.size() != static_cast<std::size_t>(256 * length)) {
    throw NativeError("latent_padding_invalid");
  }
  std::vector<float> result(static_cast<std::size_t>(256 * new_length), 0.0f);
  for (int channel = 0; channel < 256; ++channel) {
    const float* source = latent.data() + static_cast<std::size_t>(channel * length);
    float* target = result.data() + static_cast<std::size_t>(channel * new_length);
    std::copy(source, source + length, target);
    if (new_length > length) std::fill(target + length, target + new_length, source[length - 1]);
  }
  return result;
}

std::string lower_ascii(std::string value) {
  std::transform(value.begin(), value.end(), value.begin(), [](unsigned char character) {
    return static_cast<char>(std::tolower(character));
  });
  return value;
}

void validate_precision_marker(const std::string& path, const std::string& requested,
                              const char* role) {
  const std::string name = lower_ascii(path);
  const std::string precision = lower_ascii(requested);
  if (precision.empty()) throw NativeError(std::string(role) + "_precision_missing");
  // Curated bundles encode precision in the filename (dit_fp32, dec_w8a8,
  // ...). A merged LoRA cache may use a sidecar marker, which lets the native
  // runner validate the effective graph without trusting a UI-only selector.
  bool has_marker = false;
  for (const char* marker : {"fp32", "w16a32", "w8a32", "w8a8", "w8a8-dyn"}) {
    if (name.find(marker) != std::string::npos) {
      has_marker = true;
      if (precision == marker ||
          (precision == "w8a8-dyn" && std::string(marker) == "w8a8")) return;
      throw NativeError(std::string(role) + "_precision_mismatch");
    }
  }
  std::ifstream marker(path + ".precision");
  if (marker) {
    std::string sidecar;
    std::getline(marker, sidecar);
    if (lower_ascii(sidecar) != precision) {
      throw NativeError(std::string(role) + "_precision_mismatch");
    }
    return;
  }
  if (!has_marker) throw NativeError(std::string(role) + "_precision_unverifiable");
}

NativeGenerationResult run_generation(
    const std::string& tokenizer_path, const std::string& text_encoder_path,
    const std::string& dit_path, const std::string& decoder_path,
    const std::string& encoder_path, const std::string& prompt,
    const std::string& negative_prompt, double duration_seconds, int steps,
    int64_t seed, float init_noise_level, float cfg, float apg, bool cfg_batched,
    const std::string& init_audio_path, double mask_start_seconds,
    double mask_end_seconds, int threads, bool free_models, const std::string& dit_precision,
    const std::string& decoder_precision, const std::string& encoder_precision,
    int max_rung, const std::string& codec_family, const std::vector<std::string>& lora_paths,
    const std::vector<float>& lora_strengths, const std::string& output_path,
    ProgressReporter* progress) {
  (void)lora_strengths;
  if (duration_seconds <= 0.0 || !std::isfinite(duration_seconds)) {
    throw NativeError("invalid_duration");
  }
  if (steps < 1 || steps > 64) throw NativeError("invalid_sampling_steps");
  if (!std::isfinite(init_noise_level) || init_noise_level < kMinSigma) {
    throw NativeError("invalid_noise_level");
  }
  if (!std::isfinite(cfg) || !std::isfinite(apg)) throw NativeError("invalid_guidance");
  if (threads < 1) throw NativeError("invalid_thread_count");
  if (output_path.empty()) throw NativeError("output_path_missing");
  if (!lora_paths.empty()) {
    // TFLite weights are immutable after model creation. A real LoRA merge
    // requires rebuilding the FlatBuffer (including quantization metadata), so
    // accepting this request and silently ignoring adapters would be unsafe.
    throw NativeError("lora_merge_unavailable_for_litert_graph");
  }
  validate_precision_marker(dit_path, dit_precision, "dit");
  validate_precision_marker(decoder_path, decoder_precision, "decoder");
  if (!encoder_path.empty()) validate_precision_marker(encoder_path, encoder_precision, "encoder");

  const int latent_length = std::max(
      1, static_cast<int>(std::ceil(duration_seconds * kSampleRate / kSamplesPerLatent)));
  const bool same_s = codec_family.find("same-l") == std::string::npos;
  const int trim = same_s ? 16 : 12;
  const bool has_init = !init_audio_path.empty();
  const bool has_mask = std::isfinite(mask_start_seconds) && std::isfinite(mask_end_seconds);
  if (has_mask && !has_init) throw NativeError("mask_requires_input_audio");

  litert::Api api;
  std::string api_error;
  if (!api.load(&api_error)) {
    // Symbol names are diagnostic constants, but keep the wire code bounded
    // and lowercase; dynamic-loader messages can contain private paths.
    throw NativeError(api_error.rfind("litert_symbol_missing:", 0) == 0
                          ? "litert_symbol_missing" : "litert_library_unavailable");
  }
  try {
    progress->emit("tokenizing", 0, 0);
    SentencePieceTokenizer tokenizer(tokenizer_path);
    const TokenBatch positive_tokens = tokenize(tokenizer, prompt);
    progress->emit("conditioning", 0, 0);
    std::vector<float> positive_hidden;
    std::unique_ptr<TextEncoder> retained_text_encoder;
    if (!free_models) {
      retained_text_encoder = std::make_unique<TextEncoder>(&api, text_encoder_path, threads);
      positive_hidden = retained_text_encoder->encode(positive_tokens.ids, positive_tokens.mask);
    } else {
      TextEncoder text_encoder(&api, text_encoder_path, threads);
      positive_hidden = text_encoder.encode(positive_tokens.ids, positive_tokens.mask);
    }
    std::vector<float> negative_hidden;
    std::vector<float> positive_mask(kConditioningTokens, 0.0f);
    std::vector<float> negative_mask(kConditioningTokens, 0.0f);
    for (int index = 0; index < kConditioningTokens; ++index) {
      positive_mask[static_cast<std::size_t>(index)] =
          static_cast<float>(positive_tokens.mask[static_cast<std::size_t>(index)]);
    }
    if (std::abs(cfg - 1.0f) > 1e-6f) {
      if (negative_prompt.empty()) {
        negative_hidden.assign(positive_hidden.size(), 0.0f);
      } else {
        const TokenBatch negative_tokens = tokenize(tokenizer, negative_prompt);
        for (int index = 0; index < kConditioningTokens; ++index) {
          negative_mask[static_cast<std::size_t>(index)] =
              static_cast<float>(negative_tokens.mask[static_cast<std::size_t>(index)]);
        }
        if (retained_text_encoder != nullptr) {
          negative_hidden = retained_text_encoder->encode(negative_tokens.ids, negative_tokens.mask);
        } else {
          TextEncoder text_encoder(&api, text_encoder_path, threads);
          negative_hidden = text_encoder.encode(negative_tokens.ids, negative_tokens.mask);
        }
      }
    }
    progress->emit("conditioning", 1, 1);

    std::vector<float> init_latents;
    std::unique_ptr<RungCodec> retained_encoder;
    if (has_init) {
      progress->emit("audio_encoding", 0, 0);
      if (encoder_path.empty()) throw NativeError("codec_encoder_missing");
      const StereoAudio input_audio = read_pcm16_wav(init_audio_path);
      const int encoder_length = same_s && (latent_length % 2 != 0) ? latent_length + 1 : latent_length;
      std::vector<float> audio;
      copy_or_zero_pad_audio(input_audio, &audio,
                             static_cast<std::size_t>(encoder_length) * kSamplesPerLatent);
      if (!free_models) {
        retained_encoder =
            std::make_unique<RungCodec>(&api, encoder_path, true, trim, max_rung, threads);
        init_latents = retained_encoder->encode(audio, encoder_length);
      } else {
        RungCodec encoder(&api, encoder_path, true, trim, max_rung, threads);
        init_latents = encoder.encode(audio, encoder_length);
      }
      if (static_cast<int>(init_latents.size()) != 256 * encoder_length) {
        throw NativeError("codec_encoder_output_shape_invalid");
      }
      if (encoder_length != latent_length) {
        init_latents = repeat_latent_last(init_latents, encoder_length, latent_length);
      }
      progress->emit("audio_encoding", 1, 1);
    }

    std::vector<float> keep_mask;
    std::vector<float> local_add_cond;
    if (has_mask) {
      const int start = std::max(0, std::min(latent_length,
          static_cast<int>(std::llround(mask_start_seconds * kSampleRate / kSamplesPerLatent))));
      const int end = std::max(0, std::min(latent_length,
          static_cast<int>(std::llround(mask_end_seconds * kSampleRate / kSamplesPerLatent))));
      if (end <= start) throw NativeError("mask_empty_after_latent_rounding");
      keep_mask.assign(static_cast<std::size_t>(latent_length), 1.0f);
      std::fill(keep_mask.begin() + start, keep_mask.begin() + end, 0.0f);
      std::vector<float> masked = init_latents;
      for (int channel = 0; channel < 256; ++channel) {
        for (int index = 0; index < latent_length; ++index) {
          masked[static_cast<std::size_t>(channel * latent_length + index)] *=
              keep_mask[static_cast<std::size_t>(index)];
        }
      }
      local_add_cond.reserve(static_cast<std::size_t>(257 * latent_length));
      local_add_cond.insert(local_add_cond.end(), keep_mask.begin(), keep_mask.end());
      local_add_cond.insert(local_add_cond.end(), masked.begin(), masked.end());
    } else {
      local_add_cond.assign(static_cast<std::size_t>(257 * latent_length), 0.0f);
    }

    progress->emit("sampling", 0, steps);
    DeterministicNoise noise(static_cast<uint64_t>(seed));
    const std::size_t latent_size = static_cast<std::size_t>(256 * latent_length);
    std::vector<float> latents = noise.tensor(latent_size);
    std::vector<std::vector<float>> step_noise;
    step_noise.reserve(static_cast<std::size_t>(steps));
    for (int step = 0; step < steps; ++step) step_noise.push_back(noise.tensor(latent_size));
    const float sigma = init_noise_level;
    if (has_init && !has_mask) {
      for (std::size_t index = 0; index < latent_size; ++index) {
        latents[index] = init_latents[index] * (1.0f - sigma) + latents[index] * sigma;
      }
    }
    const std::vector<float> schedule = build_pingpong_schedule(steps, sigma);
    std::unique_ptr<DiTRunner> retained_dit;
    std::unique_ptr<DiTRunner> scoped_dit;
    if (!free_models) {
      retained_dit = std::make_unique<DiTRunner>(&api, dit_path, threads, latent_length,
                                                 static_cast<float>(duration_seconds));
    } else {
      scoped_dit = std::make_unique<DiTRunner>(&api, dit_path, threads, latent_length,
                                               static_cast<float>(duration_seconds));
    }
    DiTRunner* dit = retained_dit != nullptr ? retained_dit.get() : scoped_dit.get();
    for (int step = 0; step < steps; ++step) {
        if (g_cancelled.load(std::memory_order_relaxed)) throw NativeError("cancelled");
        const float current = schedule[static_cast<std::size_t>(step)];
        const float next = schedule[static_cast<std::size_t>(step + 1)];
        const std::vector<float> velocity = dit->forward(
            latents, current, positive_hidden, positive_mask, negative_hidden,
            negative_mask, local_add_cond, cfg, apg, cfg_batched);
        std::vector<float> denoised(latent_size);
        for (std::size_t index = 0; index < latent_size; ++index) {
          denoised[index] = latents[index] - current * velocity[index];
        }
        if (step < steps - 1 && next > 0.0f) {
          for (std::size_t index = 0; index < latent_size; ++index) {
            latents[index] = (1.0f - next) * denoised[index] + next * step_noise[step][index];
          }
        } else {
          latents.swap(denoised);
        }
        if (has_mask) {
          for (int channel = 0; channel < 256; ++channel) {
            for (int index = 0; index < latent_length; ++index) {
              const std::size_t offset = static_cast<std::size_t>(channel * latent_length + index);
              latents[offset] = init_latents[offset] * keep_mask[static_cast<std::size_t>(index)] +
                  latents[offset] * (1.0f - keep_mask[static_cast<std::size_t>(index)]);
            }
          }
        }
        progress->emit("sampling", step + 1, steps);
    }
    if (free_models) scoped_dit.reset();

    progress->emit("decoding", 0, 0);
    const int decoder_length = same_s && (latent_length % 2 != 0) ? latent_length + 1 : latent_length;
    if (decoder_length != latent_length) {
      latents = repeat_latent_last(latents, latent_length, decoder_length);
    }
      RungCodec decoder(&api, decoder_path, false, trim, max_rung, threads);
    std::vector<float> audio = decoder.decode(latents, decoder_length);
    const std::size_t requested_frames = static_cast<std::size_t>(std::llround(
        duration_seconds * kSampleRate));
    if (audio.size() < requested_frames * 2) throw NativeError("decoder_audio_shape_invalid");
    write_pcm16_wav(output_path, audio, requested_frames);
    progress->emit("decoding", 1, 1);
    NativeGenerationResult result;
    result.output_path = output_path;
    result.latent_length = latent_length;
    result.duration_ms = static_cast<long long>(std::llround(duration_seconds * 1000.0));
    return result;
  } catch (...) {
    throw;
  }
}

class JStringUtf final {
 public:
  JStringUtf(JNIEnv* env, jstring value) : env_(env), value_(value) {
    if (value_ != nullptr) chars_ = env_->GetStringUTFChars(value_, nullptr);
  }
  ~JStringUtf() {
    if (chars_ != nullptr) env_->ReleaseStringUTFChars(value_, chars_);
  }
  std::string value() const { return chars_ == nullptr ? std::string() : std::string(chars_); }

 private:
  JNIEnv* env_ = nullptr;
  jstring value_ = nullptr;
  const char* chars_ = nullptr;
};

void throw_java(JNIEnv* env, const char* message) {
  jclass exception = env->FindClass("java/lang/IllegalStateException");
  if (exception != nullptr) {
    env->ThrowNew(exception, message == nullptr ? "stable_audio_native_failed" : message);
    env->DeleteLocalRef(exception);
  }
}

}  // namespace
}  // namespace stable_audio

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_llamadroid_audio_music_StableAudio3Native_nativeRun(
    JNIEnv* env, jclass /*clazz*/, jstring tokenizer_path, jstring text_encoder_path,
    jstring dit_path, jstring decoder_path, jstring encoder_path, jstring prompt,
    jstring negative_prompt, jdouble duration_seconds, jint steps, jlong seed,
    jfloat init_noise_level, jfloat cfg, jfloat apg, jboolean cfg_batched,
    jstring init_audio_path, jdouble mask_start_seconds, jdouble mask_end_seconds,
    jint threads, jboolean free_models, jstring dit_precision, jstring decoder_precision,
    jstring encoder_precision, jint max_rung, jstring codec_family,
    jobjectArray lora_paths, jfloatArray lora_strengths, jstring output_path,
    jobject progress_sink) {
  using namespace stable_audio;
  try {
    g_cancelled.store(false, std::memory_order_relaxed);
    ProgressReporter progress(env, progress_sink);
    JStringUtf tokenizer(env, tokenizer_path);
    JStringUtf text_encoder(env, text_encoder_path);
    JStringUtf dit(env, dit_path);
    JStringUtf decoder(env, decoder_path);
    JStringUtf encoder(env, encoder_path);
    JStringUtf prompt_text(env, prompt);
    JStringUtf negative_text(env, negative_prompt);
    JStringUtf init_audio(env, init_audio_path);
    JStringUtf dit_precision_text(env, dit_precision);
    JStringUtf decoder_precision_text(env, decoder_precision);
    JStringUtf encoder_precision_text(env, encoder_precision);
    JStringUtf codec_family_text(env, codec_family);
    JStringUtf output(env, output_path);

    std::vector<std::string> lora_names;
    if (lora_paths != nullptr) {
      const jsize count = env->GetArrayLength(lora_paths);
      lora_names.reserve(static_cast<std::size_t>(count));
      for (jsize index = 0; index < count; ++index) {
        jstring item = static_cast<jstring>(env->GetObjectArrayElement(lora_paths, index));
        JStringUtf item_text(env, item);
        lora_names.push_back(item_text.value());
        if (item != nullptr) env->DeleteLocalRef(item);
      }
    }
    std::vector<float> lora_values;
    if (lora_strengths != nullptr) {
      const jsize count = env->GetArrayLength(lora_strengths);
      lora_values.resize(static_cast<std::size_t>(count));
      if (count > 0) env->GetFloatArrayRegion(lora_strengths, 0, count, lora_values.data());
    }
    if (lora_values.size() != lora_names.size()) throw NativeError("lora_shape_invalid");

    const NativeGenerationResult result = run_generation(
        tokenizer.value(), text_encoder.value(), dit.value(), decoder.value(), encoder.value(),
        prompt_text.value(), negative_text.value(), duration_seconds, steps, seed,
        init_noise_level, cfg, apg, cfg_batched == JNI_TRUE, init_audio.value(),
        mask_start_seconds, mask_end_seconds, threads, free_models == JNI_TRUE,
        dit_precision_text.value(), decoder_precision_text.value(), encoder_precision_text.value(),
        max_rung, codec_family_text.value(), lora_names, lora_values, output.value(), &progress);
    const std::string json = build_result_json(result);
    return env->NewStringUTF(json.c_str());
  } catch (const NativeError& error) {
    throw_java(env, error.code().c_str());
    return nullptr;
  } catch (const std::bad_alloc&) {
    throw_java(env, "native_memory_exhausted");
    return nullptr;
  } catch (const std::exception& error) {
    throw_java(env, "stable_audio_native_failed");
    return nullptr;
  } catch (...) {
    throw_java(env, "stable_audio_native_failed");
    return nullptr;
  }
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_llamadroid_audio_music_StableAudio3Native_nativeCancel(
    JNIEnv* /*env*/, jclass /*clazz*/) {
  stable_audio::g_cancelled.store(true, std::memory_order_relaxed);
}
