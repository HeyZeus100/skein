"""Read only existing ONNX protobuf metadata; no runtime session or external data load."""
import argparse
import hashlib
import json
from pathlib import Path
import onnx
from google.protobuf.descriptor import FieldDescriptor

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--model', type=Path, required=True)
parser.add_argument('--output', type=Path, required=True)
args = parser.parse_args()
assert not args.output.exists(), 'preserve existing evidence'
raw = args.model.read_bytes()
assert len(raw) == 137296292
assert hashlib.sha256(raw).hexdigest() == 'b4342336debaea79de872370664b0aaeb67dea4605513d00ee236ea871a81f27'
model = onnx.load_model_from_string(raw)
external = []
tensor_counts = {}
message_count = 0

def walk(message, path):
    global message_count
    message_count += 1
    if isinstance(message, onnx.TensorProto):
        name = onnx.TensorProto.DataType.Name(message.data_type)
        tensor_counts[name] = tensor_counts.get(name, 0) + 1
        if message.external_data or message.data_location == onnx.TensorProto.EXTERNAL:
            external.append({'path': path, 'name': message.name,
                             'external_data': [{'key': value.key, 'value': value.value} for value in message.external_data]})
    for field, value in message.ListFields():
        if field.type != FieldDescriptor.TYPE_MESSAGE:
            continue
        if field.is_repeated:
            for index, child in enumerate(value):
                walk(child, f'{path}.{field.name}[{index}]')
        else:
            walk(value, f'{path}.{field.name}')
walk(model, 'model')
def interface(value):
    return {'name': value.name, 'type': onnx.TensorProto.DataType.Name(value.type.tensor_type.elem_type),
            'shape': [d.dim_param or d.dim_value for d in value.type.tensor_type.shape.dim]}
report = {'mode': 'protobuf only; no inference; all recursively reachable TensorProto fields inspected',
          'model': {'bytes': len(raw), 'sha256': hashlib.sha256(raw).hexdigest()},
          'onnx_parser': onnx.__version__, 'protobuf_messages_inspected': message_count,
          'ir_version': model.ir_version, 'producer': [model.producer_name, model.producer_version],
          'opsets': [{'domain': op.domain, 'version': op.version} for op in model.opset_import],
          'inputs': [interface(value) for value in model.graph.input],
          'outputs': [interface(value) for value in model.graph.output],
          'top_level_nodes': len(model.graph.node), 'top_level_initializers': len(model.graph.initializer),
          'all_tensor_fields_by_type': tensor_counts, 'external_tensor_references': external,
          'limitations': ['Model parser/schema inspection does not prove inference correctness, export provenance or reference parity.',
                         'No ONNX Runtime session was constructed and no model bytes downloaded.']}
args.output.write_text(json.dumps(report, indent=2) + '\n')
print(json.dumps({'tensor_fields': tensor_counts, 'external_references': len(external),
                  'messages': message_count, 'inputs': report['inputs'], 'outputs': report['outputs']}, indent=2))
