#ifndef OPENAI_COMPANION_LOCAL_LLAMA_C_BRIDGE_H
#define OPENAI_COMPANION_LOCAL_LLAMA_C_BRIDGE_H

#ifdef __cplusplus
extern "C" {
#endif

/* Stable C ABI shared by the Objective-C++ llama.cpp adapter and Kotlin/Native. */
typedef void *OCLlamaHandle;
typedef void (*OCLlamaOnChunk)(const char *chunk_json, void *context);

OCLlamaHandle oc_llama_create(void);
void oc_llama_destroy(OCLlamaHandle handle);
char *oc_llama_load(OCLlamaHandle handle, const char *path_utf8);
char *oc_llama_generate(OCLlamaHandle handle, const char *request_json_utf8,
                        int max_tokens, OCLlamaOnChunk on_chunk, void *context);
void oc_llama_cancel(OCLlamaHandle handle);
void oc_llama_free_string(char *value);

/* MLX Swift adapter; errors use the same malloc/free ownership as llama.cpp. */
char *oc_mlx_generate(const char *model_directory, const char *request_json,
                      int max_tokens, OCLlamaOnChunk on_chunk, void *context);
char *oc_mlx_download(const char *model_directory);
char *oc_model_download(const char *url, const char *path, const char *sha256, long long bytes);
void oc_mlx_cancel(void);
void oc_mlx_unload(void);

#ifdef __cplusplus
}
#endif

#endif
