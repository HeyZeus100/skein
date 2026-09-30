"""Rehash retained local metadata/source only; no fetching, builds or inference."""
import argparse
from datetime import datetime
import gzip
import hashlib
import json
from pathlib import Path
import zipfile

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--experiment', type=Path, required=True)
parser.add_argument('--decision', type=Path, required=True)
parser.add_argument('--repo', type=Path, required=True)
parser.add_argument('--output', type=Path, required=True)
args = parser.parse_args()
assert not args.output.exists(), 'preserve existing evidence'
args.output.mkdir(parents=True)
base, decision, repo = args.experiment, args.decision, args.repo

def receipt(path):
    raw = path.read_bytes()
    return {'bytes': len(raw), 'sha256': hashlib.sha256(raw).hexdigest()}

def git_blob(path):
    raw = path.read_bytes()
    return hashlib.sha1(b'blob ' + str(len(raw)).encode() + b'\0' + raw).hexdigest()

acquisition = json.loads((base / 'model-acquisition-receipt.json').read_text())
tree = {row['path']: row for row in json.loads((base / 'models/upstream-tree.json').read_text())}
onnx_tree = {row['path']: row for row in json.loads((decision / 'nomic-onnx-tree.json').read_text())}
pool_tree = {row['path']: row for row in json.loads((base / 'models/pooling-tree.json').read_text())}
verified = []
for row in acquisition['artifacts']:
    relative = row['path'].split('embedder-experiment-20260930/', 1)[1]
    path = base / relative
    actual = receipt(path)
    assert actual == {key: row[key] for key in ('bytes', 'sha256')}, relative
    record = {'path': relative, 'source_url': row['source_url'], **actual}
    if 'git_blob_sha1' in row:
        blob = git_blob(path)
        assert blob == row['git_blob_sha1'] == row['declared_git_oid'] == tree[path.name]['oid']
        assert len(path.read_bytes()) == tree[path.name]['size']
        record['git_blob_matches_pinned_tree'] = blob
    if relative == 'models/onnx/model_int8.onnx':
        declared = onnx_tree['onnx/model_int8.onnx']['lfs']
        assert actual == {'bytes': declared['size'], 'sha256': declared['oid']}
        record['lfs_declaration_matches_actual_bytes'] = True
    verified.append(record)

graph = json.loads((base / 'graph-inspection.json').read_text())
pool = base / 'models/pooling-config.json'
pool_receipt = graph['pooling_companion']
assert receipt(pool) == {key: pool_receipt[key] for key in ('bytes', 'sha256')}
assert git_blob(pool) == pool_receipt['git_blob_sha1'] == pool_receipt['declared_oid'] == pool_tree['1_Pooling/config.json']['oid']
assert json.loads(pool.read_text()) == pool_receipt['data']
verified.append({'path': 'models/pooling-config.json', **receipt(pool),
                 'source_url': pool_receipt['source_url'], 'git_blob_matches_pinned_tree': git_blob(pool)})

gz = repo / 'core/rag/src/test/resources/tokenizers/nomic-embed-text-v1.5.tokenizer.json.gz'
raw_tokenizer = gzip.decompress(gz.read_bytes())
assert raw_tokenizer == (base / 'models/tokenizer.json').read_bytes()
gold_path = repo / 'core/rag/src/test/resources/tokenizers/golden/nomic-embed-text-v1.5.golden.json'
gold = json.loads(gold_path.read_text())
assert receipt(gold_path)['sha256'] == '829d328fd2351078360954aed77e360e2282386d8d608cc59bf1608380f8e372'

freeze = json.loads((base / 'freeze.json').read_text())
for row in freeze['files']:
    assert receipt(base / Path(row['path']).name) == {key: row[key] for key in ('bytes', 'sha256')}
execution = json.loads((base / 'functional-attempt-1.log').read_text().splitlines()[0])
times = [freeze['frozen_at_utc'], acquisition['started_at_utc'], acquisition['completed_at_utc'], execution['started_at']]
assert list(map(datetime.fromisoformat, times)) == sorted(map(datetime.fromisoformat, times))
assert (base / 'functional-attempt-1.exit').read_text().strip() == '0'
manifest = json.loads((base / 'functional-attempt-1-artifacts.json').read_text())['artifacts']
assert 'HostNomicExperiment.java' in {row['path'] for row in manifest}
assert not any(row['path'].endswith('.class') for row in manifest)
scratch_class = base / 'classes/HostNomicExperiment.class'
published = repo / 'docs/ux/runs/2026-09-30-six-priorities/host-embedder'
published_matches = []
for path in sorted(published.iterdir()):
    if not path.is_file():
        continue
    original = base / path.name
    if not original.is_file():
        original = base / 'functional-attempt-1' / path.name
    assert path.read_bytes() == original.read_bytes(), path
    published_matches.append({'path': str(path.relative_to(repo)), **receipt(path)})

source_paths = [
    'docs/design/EMBEDDER_BACKEND_DECISION_20260930.md',
    'embedder-service/src/main/kotlin/app/skein/embedder/service/Pooling.kt',
    'embedder-service/src/main/kotlin/app/skein/embedder/service/onnx/OnnxSession.kt',
    'embedder-service/src/main/kotlin/app/skein/embedder/service/EmbedderService.kt',
    'embedder-service/src/main/kotlin/app/skein/embedder/service/EmbedderBackend.kt',
    'core/model/src/main/kotlin/app/skein/core/model/Int8Quantizer.kt',
    'core/rag/src/test/resources/tokenizers/README.md',
    'core/rag/src/test/resources/tokenizers/MANIFEST.md',
    'tools/tokenizers/gen_golden_fixtures.py',
]
source_paths += [str(path.relative_to(repo)) for path in sorted((repo / 'core/rag/src/main/kotlin/app/skein/core/rag/tokenizers').glob('*.kt'))]
sources = [{'path': path, **receipt(repo / path)} for path in source_paths]

# Preserve source metadata bytes publicly without duplicating weights or tokenizer.
members = {f'experiment/{name}': base / name for name in (
    'models/upstream-tree.json', 'models/README.md', 'models/config.json',
    'models/tokenizer_config.json', 'models/special_tokens_map.json',
    'models/modules.json', 'models/sentence_bert_config.json',
    'models/pooling-tree.json', 'models/pooling-config.json',
    'functional-attempt-1.log', 'functional-attempt-1.exit', 'javac.log', 'javac.exit',
)}
members['decision/nomic-onnx-tree.json'] = decision / 'nomic-onnx-tree.json'
members['decision/nomic-gguf-tree.json'] = decision / 'nomic-gguf-tree.json'
archive = args.output / 'retained-authorities.zip'
with zipfile.ZipFile(archive, 'x', compression=zipfile.ZIP_DEFLATED) as bundle:
    for name, path in sorted(members.items()):
        info = zipfile.ZipInfo(name, date_time=(2026, 9, 30, 0, 0, 0))
        info.compress_type = zipfile.ZIP_DEFLATED
        bundle.writestr(info, path.read_bytes())
with zipfile.ZipFile(archive) as bundle:
    assert bundle.testzip() is None
    for name, path in members.items():
        assert bundle.read(name) == path.read_bytes()

result = {
    'scope': 'Read-only local hash, Git blob, metadata and source inspection. No model inference or test execution.',
    'original_acquisition_records_verified': len(acquisition['artifacts']),
    'metadata_and_model': verified,
    'pooling_tree_receipt': receipt(base / 'models/pooling-tree.json'),
    'tokenizer': {'gzip': receipt(gz), 'raw_matches_acquired': True,
                  'gold': receipt(gold_path), 'gold_reference_version': gold['tokenizers_version'],
                  'gold_cases': len(gold['cases']), 'gold_truncations': len(gold['truncation']),
                  'limit': 'Fixture contents and provenance checked; historical test success not rerun or extended to the 131 host experiment inputs.'},
    'chronology': dict(zip(('freeze', 'acquire_start', 'acquire_finish', 'execution_start'), times)),
    'current_scratch_class': {'path': str(scratch_class), **receipt(scratch_class),
                             'limit': 'Current bytes only. This class was not sealed in the original 1583-record manifest; do not retroactively claim historical binary attestation.'},
    'published_evidence_byte_matches': published_matches,
    'reviewed_source_receipts': sources,
    'authority_archive': {**receipt(archive), 'members': [{'path': name, **receipt(path)} for name, path in sorted(members.items())],
                          'contents': 'Retained metadata/model card and original run/compiler logs only; no weights, native libraries or owner content.'},
}
(args.output / 'metadata-review.json').write_text(json.dumps(result, indent=2) + '\n')
print(json.dumps({'metadata_records': len(verified), 'source_receipts': len(sources),
                  'published_matches': len(published_matches), 'archive': receipt(archive)}))
