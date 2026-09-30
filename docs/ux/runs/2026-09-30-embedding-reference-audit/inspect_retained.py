"""Read retained bytes and postprocessing arithmetic only; never load an inference session."""
import argparse
import hashlib
import json
from pathlib import Path
import statistics
import numpy as np

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--experiment', type=Path, required=True)
parser.add_argument('--output', type=Path, required=True)
args = parser.parse_args()
base = args.experiment
assert not args.output.exists(), 'preserve existing evidence'
args.output.mkdir(parents=True)
def receipt(path):
    raw = path.read_bytes()
    return {'bytes': len(raw), 'sha256': hashlib.sha256(raw).hexdigest()}
manifest_path = base / 'functional-attempt-1-artifacts.json'
manifest = json.loads(manifest_path.read_text())['artifacts']
for record in manifest:
    assert receipt(base / record['path']) == {key: record[key] for key in ('bytes', 'sha256')}, record['path']
model_path = base / 'models/onnx/model_int8.onnx'
model = receipt(model_path)
assert model == {'bytes': 137296292, 'sha256': 'b4342336debaea79de872370664b0aaeb67dea4605513d00ee236ea871a81f27'}
rows_path = base / 'functional-attempt-1/rows.jsonl'
rows = [json.loads(line) for line in rows_path.read_text().splitlines()]
assert len(rows) == 262 and len({(r['id'], r['role']) for r in rows}) == 131
stages = ['ids.i32le', 'hidden.f32le', 'mean.f32le', 'layer.f32le', 'norm256.f32le', 'int8']
repeat_pairs = 0
summary = {'rows': len(rows), 'pairs': 131, 'stage_files': len(rows) * 6, 'manifest_records': len(manifest),
           'raw_stage_repeat_pairs': 0, 'model': model, 'manifest': receipt(manifest_path),
           'rows_receipt': receipt(rows_path), 'stage_checks': {}, 'max_abs_residual_float64': {},
           'all_floats_finite': True, 'cls_sep_all': True, 'rows_at_512_tokens': 0}
residuals = {name: 0.0 for name in ('mean', 'layer', 'norm256')}
checks = {name: 0 for name in ('sequential_f32_mean_exact', 'recorded_mean_to_layer_exact',
                               'recorded_layer_to_norm256_exact', 'recorded_norm256_to_int8_exact')}
norms = []
for row in rows:
    stem = f"{row['id']}-{row['role']}-{row['repeat']}"
    def data(stage): return (base / 'functional-attempt-1' / f'{stem}.{stage}').read_bytes()
    if row['repeat'] == 0:
        for stage in stages:
            assert data(stage) == (base / 'functional-attempt-1' / f"{row['id']}-{row['role']}-1.{stage}").read_bytes()
            repeat_pairs += 1
    ids = np.frombuffer(data('ids.i32le'), dtype='<i4')
    assert len(ids) == row['tokens'] and 6 <= len(ids) <= 512
    summary['cls_sep_all'] &= bool(ids[0] == 101 and ids[-1] == 102)
    summary['rows_at_512_tokens'] += int(len(ids) == 512)
    hidden = np.frombuffer(data('hidden.f32le'), dtype='<f4').reshape(len(ids), 768)
    mean = np.frombuffer(data('mean.f32le'), dtype='<f4')
    layer = np.frombuffer(data('layer.f32le'), dtype='<f4')
    norm = np.frombuffer(data('norm256.f32le'), dtype='<f4')
    assert len(mean) == len(layer) == 768 and len(norm) == len(data('int8')) == 256
    assert all(np.isfinite(v).all() for v in (hidden, mean, layer, norm))
    # Source establishes all-one masks for these unpadded, batch-one encodings.
    # Actual masks/types were not retained; this is conditional arithmetic consistency.
    sequential_mean = (np.add.accumulate(hidden, axis=0, dtype=np.float32)[-1] / np.float32(len(ids))).astype('<f4')
    checks['sequential_f32_mean_exact'] += int(sequential_mean.tobytes() == data('mean.f32le'))
    dmean = mean.astype(np.float64)
    denominator = np.sqrt(np.mean((dmean - np.mean(dmean)) ** 2) + float(np.float32(1e-5)))
    reconstructed_layer = ((dmean - np.mean(dmean)) / denominator).astype('<f4')
    checks['recorded_mean_to_layer_exact'] += int(reconstructed_layer.tobytes() == data('layer.f32le'))
    denominator = np.float32(np.linalg.norm(layer[:256].astype(np.float64)))
    reconstructed_norm = (layer[:256] / denominator).astype('<f4')
    checks['recorded_layer_to_norm256_exact'] += int(reconstructed_norm.tobytes() == data('norm256.f32le'))
    scaled = np.clip(norm * np.float32(127), -127, 127)
    quantized = np.floor(scaled.astype(np.float64) + 0.5).astype(np.int8)
    checks['recorded_norm256_to_int8_exact'] += int(quantized.tobytes() == data('int8'))
    assert hashlib.sha256(data('int8')).hexdigest() == row['vector_sha256']
    # A higher-precision arithmetic comparison is descriptive; this is NOT a model reference.
    mean64 = np.mean(hidden.astype(np.float64), axis=0)
    layer64 = (mean64 - np.mean(mean64)) / np.sqrt(np.mean((mean64 - np.mean(mean64)) ** 2) + 1e-5)
    norm64 = layer64[:256] / np.linalg.norm(layer64[:256])
    for name, measured, arithmetic in (('mean', mean, mean64), ('layer', layer, layer64), ('norm256', norm, norm64)):
        residuals[name] = max(residuals[name], float(np.max(np.abs(measured - arithmetic))))
    norms.append(float(np.linalg.norm(norm.astype(np.float64))))
summary['raw_stage_repeat_pairs'] = repeat_pairs
summary['stage_checks'] = checks
summary['max_abs_residual_float64'] = residuals
summary['norm_range'] = [min(norms), max(norms)]
summary['timing_descriptive_ms'] = {'median': statistics.median(r['run_ns'] / 1e6 for r in rows),
                                   'p95_nearest_rank': sorted(r['run_ns'] / 1e6 for r in rows)[248],
                                   'max': max(r['run_ns'] / 1e6 for r in rows)}
summary['classpath_receipts'] = []
for record in json.loads((base / 'compiled-classpath-receipt.json').read_text())['jars']:
    path = Path(record['path'])
    actual = receipt(path) if path.is_file() else None
    summary['classpath_receipts'].append({'path': str(path), 'expected_sha256': record['sha256'],
                                         'actual': actual, 'matches': actual is not None and actual['sha256'] == record['sha256']})
summary['limits'] = ['No model inference/session/native code was executed.',
                     'Retained-stage arithmetic is not independent HF/FP32/quantized-export numerical parity.',
                     'Masks and type IDs are source-inferred, not retained runtime tensor bytes.',
                     'No new semantic/retrieval/held-out score or threshold was calculated.',
                     'The scratch runner class was not in the original 1583-record manifest; its source was.']
summary['numpy_version'] = np.__version__
(args.output / 'retained-stage-review.json').write_text(json.dumps(summary, indent=2) + '\n')
print(json.dumps({k: summary[k] for k in ('manifest_records', 'rows', 'stage_files', 'raw_stage_repeat_pairs', 'stage_checks', 'max_abs_residual_float64', 'norm_range', 'rows_at_512_tokens')}, indent=2))
