# Chat template reference fixtures

`chat-template-reference.tsv` was generated directly by the legacy renderer in
llama.cpp **b29c606e28a01b1bc8c1351026a0fa6e616bf6c4**, the revision in
`native/llama/PINNED_COMMIT`. `llm_chat_detect_template` and
`llm_chat_apply_template` are the same implementation reached by Skein's
`llama_chat_apply_template` JNI wrapper. No model or private text is used.

Each row contains a case name, template name, comma-separated roles,
comma-separated hexadecimal UTF-8 content strings, and hexadecimal UTF-8
rendered output. Empty content is an empty field between commas. Every case
requests the assistant prefix. Fixtures include ChatML header/content collisions,
empty system content, Unicode, whitespace, repeated turns, injected control-token
text, and Gemma's merged system and trimmed-content behavior.

Regenerate from the repository root after verifying the submodule pin:

```sh
mkdir -p inference-service/build/template-reference
c++ -std=c++17 -I third_party/llama.cpp/src -I third_party/llama.cpp/include \
  -I third_party/llama.cpp/ggml/include \
  inference-service/src/test/fixtures/template_reference.cpp \
  third_party/llama.cpp/src/llama-chat.cpp \
  -o inference-service/build/template-reference/generate
inference-service/build/template-reference/generate > \
  inference-service/src/test/resources/templating/chat-template-reference.tsv
```

The JVM test checks structural compatibility against these upstream results; its
small renderer doubles provide the additional placeholder renders. It does not
claim tokenizer or inference parity. The real-vocabulary instrumented tests in
`LlamaNativeTest` cover tokenization separately and require the pinned tiny GGUF.
The user's exact Qwen artifact and phone-quality comparison remain device work.
