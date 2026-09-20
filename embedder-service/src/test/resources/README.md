# Test fixtures

`identity.onnx` is a minimal 1x1 float32 identity model (`output = Identity(input)`),
used by `OrtSmokeTest` to prove ONNX Runtime loads a model and runs inference on the
JVM test classpath without needing a real embedding/NER model checked into the repo.

It was generated once with the Python `onnx` package (no `onnxruntime` needed, since
we only construct the protobuf, we don't run it):

```bash
pip install onnx
python3 - <<'EOF'
import onnx
from onnx import helper, TensorProto

input_tensor = helper.make_tensor_value_info("input", TensorProto.FLOAT, [1, 1])
output_tensor = helper.make_tensor_value_info("output", TensorProto.FLOAT, [1, 1])
node = helper.make_node("Identity", inputs=["input"], outputs=["output"], name="identity_node")
graph = helper.make_graph([node], "identity_graph", [input_tensor], [output_tensor])
model = helper.make_model(graph, producer_name="skein-embedder-service-fixture",
                           opset_imports=[helper.make_opsetid("", 17)])
model.ir_version = 8

onnx.checker.check_model(model)
onnx.save(model, "identity.onnx")
EOF
```

Regenerate only if the fixture needs to change; do not hand-edit the binary file.
