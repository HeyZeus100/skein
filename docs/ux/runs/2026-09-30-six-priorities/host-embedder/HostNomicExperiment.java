import ai.onnxruntime.*;
import app.skein.core.rag.tokenizers.*;
import app.skein.core.model.Int8Quantizer;
import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Scratch functional precheck only. Does not select an Android backend or test held-out retrieval. */
public final class HostNomicExperiment {
  static final int DIM = 768, OUT = 256, MAX = 512;
  static final float LAYER_NORM_EPS = 1e-5f;
  static String sha(byte[] bytes) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
  }
  static void require(boolean condition, String fixed) {
    if (!condition) throw new IllegalStateException(fixed);
  }
  static byte[] floats(float[] values) {
    ByteBuffer bytes = ByteBuffer.allocate(values.length * 4).order(ByteOrder.LITTLE_ENDIAN);
    for (float value : values) bytes.putFloat(value);
    return bytes.array();
  }
  static byte[] ints(int[] values) {
    ByteBuffer bytes = ByteBuffer.allocate(values.length * 4).order(ByteOrder.LITTLE_ENDIAN);
    for (int value : values) bytes.putInt(value);
    return bytes.array();
  }
  static float[] layerNorm(float[] pooled) {
    double mean = 0, variance = 0;
    for (float value : pooled) mean += value;
    mean /= pooled.length;
    for (float value : pooled) variance += (value - mean) * (value - mean);
    variance /= pooled.length;
    double denominator = Math.sqrt(variance + LAYER_NORM_EPS);
    float[] result = new float[pooled.length];
    for (int i = 0; i < pooled.length; i++) result[i] = (float)((pooled[i] - mean) / denominator);
    return result;
  }
  static float[] truncateNormalize(float[] normalized) {
    float[] result = Arrays.copyOf(normalized, OUT);
    double squares = 0;
    for (float value : result) squares += value * (double)value;
    require(squares > 0 && Double.isFinite(squares), "invalid pooled vector");
    double norm = Math.sqrt(squares);
    for (int i = 0; i < OUT; i++) result[i] /= (float)norm;
    return result;
  }
  public static void main(String[] args) throws Exception {
    require(args.length == 4, "expected model, tokenizer, frozen input TSV, new output directory");
    Path output = Path.of(args[3]);
    require(!Files.exists(output), "output directory already exists");
    Files.createDirectories(output);
    byte[] model = Files.readAllBytes(Path.of(args[0]));
    require(model.length == 137296292 && sha(model).equals("b4342336debaea79de872370664b0aaeb67dea4605513d00ee236ea871a81f27"), "unqualified model");
    byte[] tokenizerBytes = Files.readAllBytes(Path.of(args[1]));
    require(sha(tokenizerBytes).equals("d241a60d5e8f04cc1b2b3e9ef7a4921b27bf526d9f6050ab90f9267a1f9e5c66"), "unqualified tokenizer");
    Tokenizer tokenizer = TokenizerFactory.INSTANCE.fromJson(new ByteArrayInputStream(tokenizerBytes)).truncate(MAX);
    List<String> rows = Files.readAllLines(Path.of(args[2]), StandardCharsets.UTF_8);
    require(rows.size() == 128, "unexpected frozen row count");
    OrtEnvironment env = OrtEnvironment.getEnvironment();
    long loadStart = System.nanoTime();
    try (OrtSession.SessionOptions options = new OrtSession.SessionOptions()) {
      options.setIntraOpNumThreads(1); options.setInterOpNumThreads(1);
      options.setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL);
      options.setMemoryPatternOptimization(false); options.setCPUArenaAllocator(false);
      try (OrtSession session = env.createSession(model, options);
           BufferedWriter report = Files.newBufferedWriter(output.resolve("rows.jsonl"), StandardCharsets.UTF_8)) {
        long loadNs = System.nanoTime() - loadStart;
        Files.writeString(output.resolve("configuration.json"), "{\"mode\":\"functional-precheck\",\"threads\":1,\"batch\":1,\"repeats\":2,\"max_tokens\":512,\"layer_norm_epsilon\":1e-5,\"load_ns\":"+loadNs+",\"reference_pipeline_parity\":\"UNMEASURED\",\"android_acceptance\":false}\n");
        for (String row : rows) {
          String[] parts = row.split("\\t", -1);
          require(parts.length == 3 && parts[0].matches("[a-z0-9-]+"), "invalid frozen input row");
          String id = parts[0]; boolean query = parts[1].equals("query");
          String raw = new String(Base64.getDecoder().decode(parts[2]), StandardCharsets.UTF_8);
          boolean probe = id.equals("dev-01-current") || id.equals("dev-01-question") || id.equals("edge-01");
          boolean[] roles = probe ? new boolean[] { query, !query } : new boolean[] { query };
          for (boolean runQuery : roles) {
          byte[] first = null;
          for (int repeat = 0; repeat < 2; repeat++) {
            String role = runQuery ? "query" : "document";
            long tokenStart = System.nanoTime();
            Encoding encoded = tokenizer.encode((runQuery ? "search_query: " : "search_document: ") + raw);
            long tokenizeNs = System.nanoTime() - tokenStart;
            int count = encoded.getSize();
            require(count >= 2 && count <= MAX, "unexpected token count");
            long[][] ids = new long[1][count], mask = new long[1][count], types = new long[1][count];
            for (int i = 0; i < count; i++) { ids[0][i] = encoded.getIds()[i]; mask[0][i] = encoded.getAttentionMask()[i]; types[0][i] = encoded.getTypeIds()[i]; }
            long runStart = System.nanoTime();
            try (OnnxTensor idsTensor = OnnxTensor.createTensor(env, ids);
                 OnnxTensor maskTensor = OnnxTensor.createTensor(env, mask);
                 OnnxTensor typesTensor = OnnxTensor.createTensor(env, types);
                 OrtSession.Result result = session.run(Map.of("input_ids", idsTensor, "attention_mask", maskTensor, "token_type_ids", typesTensor))) {
              long runNs = System.nanoTime() - runStart;
              float[][][] hidden = (float[][][])result.get("last_hidden_state").orElseThrow().getValue();
              require(hidden.length == 1 && hidden[0].length == count, "unexpected hidden shape");
              float[] pooled = new float[DIM], flat = new float[count * DIM];
              int kept = 0;
              for (int t = 0; t < count; t++) {
                require(hidden[0][t].length == DIM, "unexpected hidden dimension");
                if (mask[0][t] != 0) kept++;
                for (int d = 0; d < DIM; d++) {
                  float value = hidden[0][t][d]; require(Float.isFinite(value), "nonfinite hidden state");
                  flat[t * DIM + d] = value;
                  if (mask[0][t] != 0) pooled[d] += value;
                }
              }
              require(kept > 0, "empty mask");
              for (int d = 0; d < DIM; d++) pooled[d] /= kept;
              float[] layer = layerNorm(pooled), normalized = truncateNormalize(layer);
              byte[] quantized = Int8Quantizer.INSTANCE.quantize(normalized);
              boolean identical = first == null || Arrays.equals(first, quantized);
              if (first == null) first = quantized.clone();
              String stem = id + "-" + role + "-" + repeat;
              Files.write(output.resolve(stem + ".ids.i32le"), ints(encoded.getIds()));
              Files.write(output.resolve(stem + ".hidden.f32le"), floats(flat));
              Files.write(output.resolve(stem + ".mean.f32le"), floats(pooled));
              Files.write(output.resolve(stem + ".layer.f32le"), floats(layer));
              Files.write(output.resolve(stem + ".norm256.f32le"), floats(normalized));
              Files.write(output.resolve(stem + ".int8"), quantized);
              report.write("{\"id\":\""+id+"\",\"role\":\""+role+"\",\"repeat\":"+repeat+",\"tokens\":"+count+",\"tokenize_ns\":"+tokenizeNs+",\"run_ns\":"+runNs+",\"same_as_first\":"+identical+",\"vector_sha256\":\""+sha(quantized)+"\"}\n");
              report.flush();
              require(identical, "repeatability failure");
            }
          }
          }
        }
      }
    }
  }
}
