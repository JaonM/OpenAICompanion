#include <jni.h>
#include <llama.h>
#include <algorithm>
#include <atomic>
#include <cstdint>
#include <limits>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

namespace {
struct Engine {
    llama_model *model = nullptr;
    std::string path;
    std::atomic_bool cancelled{false};
    ~Engine() { if (model) llama_model_free(model); }
};
std::once_flag backend_once;
Engine *engine(jlong handle) { return reinterpret_cast<Engine *>(handle); }
jstring failure(JNIEnv *env, const char *message) { return env->NewStringUTF(message); }

std::string utf8(JNIEnv *env, jstring value) {
    const jchar *chars = env->GetStringChars(value, nullptr);
    if (!chars) return {};
    const jsize length = env->GetStringLength(value);
    std::string text;
    text.reserve(length * 3);
    for (jsize i = 0; i < length; ++i) {
        uint32_t codepoint = chars[i];
        if (codepoint >= 0xD800 && codepoint <= 0xDBFF && i + 1 < length &&
            chars[i + 1] >= 0xDC00 && chars[i + 1] <= 0xDFFF) {
            codepoint = 0x10000 + ((codepoint - 0xD800) << 10) + (chars[++i] - 0xDC00);
        }
        if (codepoint >= 0xD800 && codepoint <= 0xDFFF) codepoint = 0xFFFD;
        if (codepoint < 0x80) text.push_back(static_cast<char>(codepoint));
        else if (codepoint < 0x800) {
            text.push_back(static_cast<char>(0xC0 | (codepoint >> 6)));
            text.push_back(static_cast<char>(0x80 | (codepoint & 0x3F)));
        } else if (codepoint < 0x10000) {
            text.push_back(static_cast<char>(0xE0 | (codepoint >> 12)));
            text.push_back(static_cast<char>(0x80 | ((codepoint >> 6) & 0x3F)));
            text.push_back(static_cast<char>(0x80 | (codepoint & 0x3F)));
        } else {
            text.push_back(static_cast<char>(0xF0 | (codepoint >> 18)));
            text.push_back(static_cast<char>(0x80 | ((codepoint >> 12) & 0x3F)));
            text.push_back(static_cast<char>(0x80 | ((codepoint >> 6) & 0x3F)));
            text.push_back(static_cast<char>(0x80 | (codepoint & 0x3F)));
        }
    }
    env->ReleaseStringChars(value, chars);
    return text;
}

bool utf8_to_utf16(const std::string &text, std::u16string &output) {
    output.clear();
    for (size_t i = 0; i < text.size();) {
        const auto lead = static_cast<unsigned char>(text[i]);
        size_t width = lead < 0x80 ? 1 : lead >= 0xC2 && lead <= 0xDF ? 2
            : lead >= 0xE0 && lead <= 0xEF ? 3 : lead >= 0xF0 && lead <= 0xF4 ? 4 : 0;
        if (!width || i + width > text.size()) return false;
        uint32_t codepoint = lead & (width == 1 ? 0x7F : width == 2 ? 0x1F : width == 3 ? 0x0F : 0x07);
        for (size_t j = 1; j < width; ++j)
            if ((static_cast<unsigned char>(text[i + j]) & 0xC0) != 0x80) return false;
            else codepoint = (codepoint << 6) | (static_cast<unsigned char>(text[i + j]) & 0x3F);
        if ((width == 2 && codepoint < 0x80) || (width == 3 && codepoint < 0x800) ||
            (width == 4 && codepoint < 0x10000) ||
            (codepoint >= 0xD800 && codepoint <= 0xDFFF) || codepoint > 0x10FFFF) return false;
        if (codepoint < 0x10000) output.push_back(static_cast<char16_t>(codepoint));
        else {
            codepoint -= 0x10000;
            output.push_back(static_cast<char16_t>(0xD800 | (codepoint >> 10)));
            output.push_back(static_cast<char16_t>(0xDC00 | (codepoint & 0x3FF)));
        }
        i += width;
    }
    return true;
}
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_openai_companion_android_AndroidLlamaNative_create(JNIEnv *, jobject) {
    std::call_once(backend_once, llama_backend_init);
    return reinterpret_cast<jlong>(new Engine);
}

extern "C" JNIEXPORT void JNICALL
Java_com_openai_companion_android_AndroidLlamaNative_destroy(JNIEnv *, jobject, jlong handle) {
    delete engine(handle);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_openai_companion_android_AndroidLlamaNative_load(JNIEnv *env, jobject, jlong handle, jstring path) {
    Engine *state = engine(handle);
    if (!state || !path) return failure(env, "Invalid GGUF model path");
    const std::string requested = utf8(env, path);
    if (state->model && state->path == requested) return nullptr;
    if (state->model) { llama_model_free(state->model); state->model = nullptr; state->path.clear(); }
    auto params = llama_model_default_params();
    params.n_gpu_layers = 0;
    state->model = llama_model_load_from_file(requested.c_str(), params);
    if (!state->model) return failure(env, "llama.cpp could not load this GGUF model");
    if (llama_model_has_encoder(state->model)) {
        llama_model_free(state->model); state->model = nullptr;
        return failure(env, "Only decoder-only chat GGUF models are supported");
    }
    state->path = requested;
    return nullptr;
}

extern "C" JNIEXPORT void JNICALL
Java_com_openai_companion_android_AndroidLlamaNative_cancel(JNIEnv *, jobject, jlong handle) {
    if (auto *state = engine(handle)) state->cancelled.store(true);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_openai_companion_android_AndroidLlamaNative_generate(
    JNIEnv *env, jobject, jlong handle, jobjectArray roles, jobjectArray contents, jint max_tokens,
    jboolean tool_mode, jobject sink) {
    Engine *state = engine(handle);
    if (!state || !state->model) return failure(env, "Import a GGUF model first");
    if (!roles || !contents || !sink || env->GetArrayLength(roles) != env->GetArrayLength(contents))
        return failure(env, "Invalid Harness messages");
    state->cancelled.store(false);
    const jsize count = env->GetArrayLength(roles);
    std::vector<std::string> role_storage, content_storage;
    role_storage.reserve(count); content_storage.reserve(count);
    for (jsize i = 0; i < count; ++i) {
        auto role = static_cast<jstring>(env->GetObjectArrayElement(roles, i));
        auto content = static_cast<jstring>(env->GetObjectArrayElement(contents, i));
        if (!role || !content) return failure(env, "Invalid Harness message");
        role_storage.push_back(utf8(env, role)); content_storage.push_back(utf8(env, content));
        env->DeleteLocalRef(role); env->DeleteLocalRef(content);
    }
    std::vector<llama_chat_message> messages;
    for (size_t i = 0; i < role_storage.size(); ++i)
        messages.push_back({role_storage[i].c_str(), content_storage[i].c_str()});
    const char *chat_template = llama_model_chat_template(state->model, nullptr);
    if (!chat_template) return failure(env, "GGUF has no chat template");
    std::vector<char> prompt_buffer(8192);
    int32_t prompt_length = llama_chat_apply_template(chat_template, messages.data(), messages.size(),
        true, prompt_buffer.data(), static_cast<int32_t>(prompt_buffer.size()));
    if (prompt_length < 0) return failure(env, "Unsupported GGUF chat template");
    if (static_cast<size_t>(prompt_length) >= prompt_buffer.size()) {
        prompt_buffer.resize(static_cast<size_t>(prompt_length) + 1);
        prompt_length = llama_chat_apply_template(chat_template, messages.data(), messages.size(),
            true, prompt_buffer.data(), static_cast<int32_t>(prompt_buffer.size()));
        if (prompt_length < 0) return failure(env, "GGUF chat template failed");
    }
    std::string prompt(prompt_buffer.data(), prompt_length);
    if (prompt.size() > static_cast<size_t>(std::numeric_limits<int32_t>::max() - 16))
        return failure(env, "Conversation context is too long");
    const llama_vocab *vocab = llama_model_get_vocab(state->model);
    std::vector<llama_token> tokens(prompt.size() + 16);
    int32_t token_count = llama_tokenize(vocab, prompt.data(), prompt.size(), tokens.data(), tokens.size(), true, true);
    if (token_count < 0) {
        if (token_count == std::numeric_limits<int32_t>::min()) return failure(env, "Token count overflow");
        tokens.resize(-token_count);
        token_count = llama_tokenize(vocab, prompt.data(), prompt.size(), tokens.data(), tokens.size(), true, true);
    }
    if (token_count <= 0) return failure(env, "Could not tokenize the prompt");
    auto context_params = llama_context_default_params();
    context_params.n_ctx = 4096; context_params.n_batch = 512;
    context_params.n_threads = std::max(1u, std::min(4u, std::thread::hardware_concurrency()));
    context_params.n_threads_batch = context_params.n_threads;
    std::unique_ptr<llama_context, decltype(&llama_free)> context(
        llama_init_from_model(state->model, context_params), llama_free);
    if (!context) return failure(env, "Could not create llama.cpp context");
    const int32_t output_limit = std::max(1, std::min(static_cast<int>(max_tokens), 512));
    if (token_count + output_limit > static_cast<int32_t>(llama_n_ctx(context.get())))
        return failure(env, "LOCAL_MODEL_CONTEXT_EXCEEDED");
    const int32_t batch_size = llama_n_batch(context.get());
    for (int32_t offset = 0; offset < token_count; offset += batch_size) {
        if (state->cancelled.load()) return failure(env, "Generation cancelled");
        llama_batch batch = llama_batch_get_one(tokens.data() + offset, std::min(batch_size, token_count - offset));
        if (llama_decode(context.get(), batch) != 0) return failure(env, "Could not decode prompt");
    }
    std::unique_ptr<llama_sampler, decltype(&llama_sampler_free)> sampler(
        llama_sampler_chain_init(llama_sampler_chain_default_params()), llama_sampler_free);
    if (!sampler) return failure(env, "Could not create sampler");
    llama_sampler_chain_add(sampler.get(), llama_sampler_init_top_k(40));
    llama_sampler_chain_add(sampler.get(), llama_sampler_init_top_p(0.9f, 1));
    llama_sampler_chain_add(sampler.get(), llama_sampler_init_temp(tool_mode ? 0.2f : 0.7f));
    llama_sampler_chain_add(sampler.get(), llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
    jclass sink_class = env->GetObjectClass(sink);
    jmethodID on_token = env->GetMethodID(sink_class, "onToken", "(Ljava/lang/String;)V");
    if (!on_token) return failure(env, "Missing token callback");
    std::string pending;
    std::u16string decoded;
    for (int32_t i = 0; i < output_limit; ++i) {
        if (state->cancelled.load()) return failure(env, "Generation cancelled");
        llama_token token = llama_sampler_sample(sampler.get(), context.get(), -1);
        if (llama_vocab_is_eog(vocab, token)) break;
        std::vector<char> piece(256);
        int32_t length = llama_token_to_piece(vocab, token, piece.data(), piece.size(), 0, false);
        if (length < 0) {
            piece.resize(-length);
            length = llama_token_to_piece(vocab, token, piece.data(), piece.size(), 0, false);
        }
        if (length > 0) {
            pending.append(piece.data(), length);
            if (utf8_to_utf16(pending, decoded)) {
                jstring text = env->NewString(reinterpret_cast<const jchar *>(decoded.data()),
                    static_cast<jsize>(decoded.size()));
                env->CallVoidMethod(sink, on_token, text);
                env->DeleteLocalRef(text);
                if (env->ExceptionCheck()) return nullptr;
                pending.clear();
            }
        }
        if (i + 1 == output_limit) break;
        llama_batch batch = llama_batch_get_one(&token, 1);
        if (llama_decode(context.get(), batch) != 0) return failure(env, "Could not decode generated token");
    }
    if (!pending.empty()) return failure(env, "Model emitted invalid UTF-8");
    env->DeleteLocalRef(sink_class);
    return nullptr;
}
