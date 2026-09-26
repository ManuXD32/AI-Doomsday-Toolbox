// Implementation fragment included inside the native pipeline namespace.
// Uses its bounded errors, cancellation flag, and tensor helpers.
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
          api_->create_model_from_file(path.c_str(), &model_);
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
            mapped_model_.address(), mapped_model_.size(), &model_);
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
                                   const std::vector<TensorInput>& inputs,
                                   const std::vector<litert::Layout>& expected_outputs = {}) {
    if (g_cancelled.load(std::memory_order_relaxed)) throw NativeError("cancelled");
    std::size_t input_count = 0;
    std::size_t output_count = 0;
    check_litert(api_->get_num_signature_inputs(signature(signature_index), &input_count),
                 *api_, "query_inputs");
    check_litert(api_->get_num_signature_outputs(signature(signature_index), &output_count),
                 *api_, "query_outputs");
    if (input_count != inputs.size() || output_count == 0 ||
        (!expected_outputs.empty() && expected_outputs.size() != output_count)) {
      throw NativeError("graph_signature_shape_mismatch");
    }

    // Only resize dynamic signatures. T5 and codec rungs have fixed contracts;
    // reject mismatched caller dimensions rather than mutating their graphs.
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
      if (!expected_outputs.empty()) {
        // DiT outputs resolve at invocation, so pre-run layouts can still hold
        // the export's 320-frame size. Reserve both that hint and the known
        // [batch, 256, requested_length] contract, including longer requests.
        output_types[i].layout = expected_outputs[i];
        output_sizes[i] = std::max(required, num_elements(output_types[i]) *
                                           element_size(output_types[i].element_type));
      }
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
      check_litert(api_->get_compiled_model_output_tensor_layouts(
                       compiled_, signature_index, output_count, output_layouts.data(), false),
                   *api_, "query_output_layouts");
      std::vector<TensorOutput> result(output_count);
      for (std::size_t i = 0; i < output_count; ++i) {
        void* memory = nullptr;
        check_litert(api_->get_tensor_buffer_host_memory(output_buffers[i], &memory), *api_,
                     "read_output");
        result[i].type = output_types[i];
        result[i].type.layout = output_layouts[i];
        if (!expected_outputs.empty()) {
          const auto& expected = expected_outputs[i];
          if (expected.rank != output_layouts[i].rank ||
              !std::equal(expected.dimensions, expected.dimensions + expected.rank,
                          output_layouts[i].dimensions)) {
            throw NativeError("output_tensor_shape_mismatch");
          }
        }
        check_not_null(memory, "output_buffer_unavailable");
        const std::size_t actual_size = num_elements(result[i].type) *
                                       element_size(result[i].type.element_type);
        if (actual_size > output_sizes[i]) throw NativeError("output_tensor_capacity_exceeded");
        result[i].bytes.resize(actual_size);
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
