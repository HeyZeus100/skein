// Vocabulary/template evidence for an already hash-verified PUBLIC GGUF.
// No context, decode, sampling, GPU offload, answer-quality or speed claim.
#include "llama.h"
#include "llama-vocab.h"

#include <cstdint>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <memory>
#include <sstream>
#include <stdexcept>
#include <string>
#include <utility>
#include <vector>

#ifndef SKEIN_LLAMA_CPP_COMMIT
#error "Build with the verified llama.cpp commit in SKEIN_LLAMA_CPP_COMMIT"
#endif
#ifndef SKEIN_TOKENIZER_OVERLAY_SHA256
#error "Build with the tokenizer PINS.txt SHA-256 in SKEIN_TOKENIZER_OVERLAY_SHA256"
#endif

using Turns = std::vector<std::pair<std::string, std::string>>;
using Ranges = std::vector<std::pair<size_t, size_t>>;

static void require(bool condition) {
    if (!condition) throw std::runtime_error("probe failed");
}

static std::string quote(const std::string &value) {
    std::ostringstream out;
    out << '"';
    const char *hex = "0123456789abcdef";
    for (unsigned char c : value) {
        if (c == '"' || c == '\\') out << '\\' << c;
        else if (c < 0x20) out << "\\u00" << hex[c >> 4] << hex[c & 15];
        else out << c;
    }
    return out.str() + '"';
}

static const char *boolean(bool value) { return value ? "true" : "false"; }

static std::string metadata(const llama_model *model, const char *key) {
    const int32_t size = llama_model_meta_val_str(model, key, nullptr, 0);
    if (size < 0) return "null";
    std::vector<char> buffer(static_cast<size_t>(size) + 1);
    require(llama_model_meta_val_str(model, key, buffer.data(), buffer.size()) == size);
    return quote(std::string(buffer.data(), static_cast<size_t>(size)));
}

static std::string tokens(const std::vector<llama_token> &ids) {
    std::ostringstream out;
    out << '[';
    for (size_t i = 0; i < ids.size(); ++i) {
        if (i) out << ',';
        out << ids[i];
    }
    return out.str() + ']';
}

static std::string classification(const llama_vocab *vocab, llama_token id) {
    std::ostringstream out;
    out << "{\"token_id\":" << id;
    if (id < 0 || id >= llama_vocab_n_tokens(vocab)) {
        out << ",\"present\":false,\"is_eog\":null}";
    } else {
        out << ",\"present\":true,\"text\":" << quote(llama_vocab_get_text(vocab, id))
            << ",\"is_control\":" << boolean(llama_vocab_is_control(vocab, id))
            << ",\"is_eog\":" << boolean(llama_vocab_is_eog(vocab, id)) << '}';
    }
    return out.str();
}

static std::string control(const llama_vocab *vocab, const std::string &spelling) {
    const auto ids = vocab->tokenize(spelling, false, true);
    std::ostringstream out;
    out << "{\"spelling\":" << quote(spelling) << ",\"token_ids\":" << tokens(ids)
        << ",\"singleton\":" << boolean(ids.size() == 1) << ",\"classifications\":[";
    for (size_t i = 0; i < ids.size(); ++i) {
        if (i) out << ',';
        out << classification(vocab, ids[i]);
    }
    return out.str() + "]}";
}

struct Layout {
    std::string text;
    Ranges scaffold;
};

// Fixed ChatML fixtures only: a mismatch is a failed observation, never a
// fallback. Android's parity test separately runs production ChatTemplating's
// placeholder-based boundary proof against the same benign synthetic turns.
static Layout chatml(const Turns &turns) {
    Layout result;
    const auto append_scaffold = [&](const std::string &text) {
        const auto start = result.text.size();
        result.text += text;
        result.scaffold.emplace_back(start, result.text.size());
    };
    for (const auto &turn : turns) {
        append_scaffold("<|im_start|>" + turn.first + "\n");
        result.text += turn.second;
        append_scaffold("<|im_end|>\n");
    }
    append_scaffold("<|im_start|>assistant\n");
    return result;
}

static std::string render(const char *chat_template, const Turns &turns) {
    std::vector<llama_chat_message> messages;
    for (const auto &turn : turns) messages.push_back({turn.first.c_str(), turn.second.c_str()});
    std::vector<char> buffer(4096);
    int32_t length = llama_chat_apply_template(chat_template, messages.data(), messages.size(), true,
                                              buffer.data(), static_cast<int32_t>(buffer.size()));
    if (length > static_cast<int32_t>(buffer.size())) {
        buffer.resize(static_cast<size_t>(length));
        length = llama_chat_apply_template(chat_template, messages.data(), messages.size(), true,
                                           buffer.data(), static_cast<int32_t>(buffer.size()));
    }
    require(length >= 0 && length <= static_cast<int32_t>(buffer.size()));
    return std::string(buffer.data(), static_cast<size_t>(length));
}

int main(int argc, char **argv) {
    if (argc != 3) {
        std::cerr << "usage: model-readiness PUBLIC_MODEL.gguf NEW_TEMPLATE_BYTES_FILE\n";
        return 2;
    }
    llama_log_set([](ggml_log_level, const char *, void *) {}, nullptr);
    llama_backend_init();
    try {
        require(!std::filesystem::exists(argv[2]));
        auto params = llama_model_default_params();
        params.vocab_only = true;
        params.n_gpu_layers = 0;
        params.load_mode = LLAMA_LOAD_MODE_MMAP;
        std::vector<ggml_backend_dev_t> cpu_only;
        for (size_t i = 0; i < ggml_backend_dev_count(); ++i) {
            auto *device = ggml_backend_dev_get(i);
            if (device != nullptr && ggml_backend_dev_type(device) == GGML_BACKEND_DEVICE_TYPE_CPU) {
                cpu_only.push_back(device);
            }
        }
        require(!cpu_only.empty());
        cpu_only.push_back(nullptr);
        params.devices = cpu_only.data();
        std::unique_ptr<llama_model, decltype(&llama_model_free)> model(
            llama_model_load_from_file(argv[1], params), llama_model_free);
        require(model != nullptr);
        const auto *vocab = llama_model_get_vocab(model.get());
        const char *chat_template = llama_model_chat_template(model.get(), nullptr);
        require(chat_template != nullptr);
        const std::string raw_template(chat_template);
        require(metadata(model.get(), "tokenizer.chat_template") == quote(raw_template));
        std::ofstream template_file(argv[2], std::ios::binary);
        require(template_file.is_open());
        template_file.write(raw_template.data(), static_cast<std::streamsize>(raw_template.size()));
        template_file.close();
        require(!template_file.fail());

        const std::vector<std::pair<std::string, Turns>> cases = {
            {"empty-system", {{"system", ""}, {"user", "Hello world"}}},
            {"role-word", {{"user", "user"}}},
            {"unicode-whitespace", {{"system", "Be precise."}, {"user", u8"  Café 日本語 🧶\n\n"}}},
            {"repeated-turns", {{"user", "same"}, {"assistant", "same"}, {"user", "same"}}},
        };
        bool all_passed = true;
        std::ostringstream rows;
        rows << '[';
        for (size_t i = 0; i < cases.size(); ++i) {
            if (i) rows << ',';
            const auto &entry = cases[i];
            const auto expected = chatml(entry.second);
            const auto native = render(chat_template, entry.second);
            const bool render_equal = native == expected.text;
            const auto actual = vocab->tokenize_scaffold(expected.text, true, expected.scaffold);
            const auto reference = vocab->tokenize(native, true, true);
            const bool parity = render_equal && actual == reference;
            all_passed = all_passed && parity;
            rows << "{\"case_id\":" << quote(entry.first) << ",\"status\":" << quote(parity ? "ok" : "mismatch")
                 << ",\"native_render_equal\":" << boolean(render_equal)
                 << ",\"actual_ids\":" << tokens(actual) << ",\"reference_ids\":" << tokens(reference) << '}';
        }
        rows << ']';
        std::cout << "{\"schema_version\":1,\"qualification\":\"native-vocabulary-template-only\","
                  << "\"engine_path\":\"host-vocab-only\",\"llama_cpp_commit\":" << quote(SKEIN_LLAMA_CPP_COMMIT)
                  << ",\"tokenizer_overlay_sha256\":" << quote(SKEIN_TOKENIZER_OVERLAY_SHA256)
                  << ",\"model_size\":" << std::filesystem::file_size(argv[1])
                  << ",\"model_sha256_provenance\":\"external full-file verification required\""
                  << ",\"architecture\":" << metadata(model.get(), "general.architecture")
                  << ",\"tokenizer_model\":" << metadata(model.get(), "tokenizer.ggml.model")
                  << ",\"tokenizer_pre\":" << metadata(model.get(), "tokenizer.ggml.pre")
                  << ",\"template_bytes\":" << raw_template.size()
                  << ",\"template_sha256_provenance\":\"hash the exact emitted template bytes externally\""
                  << ",\"native_eog\":{\"scope\":\"classification-only\",\"declared_eos_metadata\":"
                  << metadata(model.get(), "tokenizer.ggml.eos_token_id")
                  << ",\"eos\":" << classification(vocab, llama_vocab_eos(vocab))
                  << ",\"eot\":" << classification(vocab, llama_vocab_eot(vocab)) << ",\"controls\":["
                  << control(vocab, "<|im_end|>") << ',' << control(vocab, "<|endoftext|>") << ','
                  << control(vocab, "</s>") << "]},\"cases\":" << rows.str()
                  << ",\"parity_passed\":" << boolean(all_passed)
                  << ",\"generated_stop_behavior\":\"unmeasured\",\"answer_quality\":\"unmeasured\"}\n";
        model.reset();
        llama_backend_free();
        return all_passed ? 0 : 1;
    } catch (...) {
        llama_backend_free();
        std::cout << "{\"status\":\"error\",\"error_code\":\"PROBE_FAILED\"}\n";
        return 3;
    }
}
