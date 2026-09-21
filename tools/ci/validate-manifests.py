#!/usr/bin/env python3
"""Validate Skein model manifests against model-manifest.schema.json.

E0.I15 (skein-3v9), coordinator decision skein-cqiu 2026-09-21.

Why this script exists at all. The runtime enforcement point for a manifest is
`app.skein.core.inference.models.ModelManifest.parse` in Kotlin, and we
deliberately do NOT add a JSON-schema library to the app: the schema file is
documentation plus a CI contract, not a runtime dependency. That leaves an
obvious failure mode — the documented schema and the Kotlin parser drifting
apart — which is what this script closes. It reads the SAME fixture
directories that `ModelManifestFixtureTest` feeds to the Kotlin parser and
demands the same verdict on every one of them:

    core/inference/src/test/resources/manifests/valid/    -> must all pass
    core/inference/src/test/resources/manifests/invalid/  -> must all fail

So a schema edit that the two readers disagree about fails one of the two
suites immediately.

It also validates whatever is shipped in the APK:

    app/src/main/assets/models/*.skein.json

with one extra rule beyond the schema: a shipped manifest MUST declare
`blake3`. MODEL_STORE.md §3 requires the post-mmap expectation to exist before
any byte is mapped, and a default model has no import pass in which to compute
it. That directory is empty today (skein-bxk / E0.I4 fills it), and an empty
glob is a pass, not a failure.

Standard library only, by design: CI runs this on a runner with no pip install
step, and a validator that needs its own dependency tree to check a supply-chain
artifact is a poor trade.
"""

import json
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[2]
SCHEMA_PATH = ROOT / "core/model/src/main/resources/schema/model-manifest.schema.json"
SHIPPED_DIR = ROOT / "app/src/main/assets/models"
FIXTURE_DIR = ROOT / "core/inference/src/test/resources/manifests"

JSON_TYPES = {"object": dict, "array": list, "string": str, "integer": int, "boolean": bool}


def check(node, schema, path, errors):
    """Append a message to `errors` for each way `node` violates `schema`.

    A focused subset of JSON Schema draft 2020-12 — exactly the keywords
    model-manifest.schema.json uses. Anything else in the schema is ignored
    rather than silently treated as satisfied, so adding a keyword to the
    schema without teaching it here is caught by the fixture round-trip above.
    """
    expected = schema.get("type")
    if expected is not None:
        want = JSON_TYPES[expected]
        # bool is a subclass of int in Python; JSON says they are distinct.
        ok = isinstance(node, want) and not (isinstance(node, bool) and expected == "integer")
        if not ok:
            errors.append("%s: expected %s" % (path, expected))
            return
    if "const" in schema and node != schema["const"]:
        errors.append("%s: must be %r" % (path, schema["const"]))
    if "enum" in schema and node not in schema["enum"]:
        errors.append("%s: %r is not one of %s" % (path, node, schema["enum"]))
    if isinstance(node, str):
        if "pattern" in schema and not re.search(schema["pattern"], node):
            errors.append("%s: %r does not match %s" % (path, node, schema["pattern"]))
        if len(node) < schema.get("minLength", 0):
            errors.append("%s: shorter than minLength" % path)
        if "maxLength" in schema and len(node) > schema["maxLength"]:
            errors.append("%s: longer than maxLength" % path)
    if isinstance(node, int) and not isinstance(node, bool):
        if "minimum" in schema and node < schema["minimum"]:
            errors.append("%s: below minimum %s" % (path, schema["minimum"]))
    if isinstance(node, list):
        if len(node) < schema.get("minItems", 0):
            errors.append("%s: fewer than minItems" % path)
        if schema.get("uniqueItems") and len({json.dumps(i, sort_keys=True) for i in node}) != len(node):
            errors.append("%s: entries must be unique" % path)
        for i, item in enumerate(node):
            check(item, schema.get("items", {}), "%s[%d]" % (path, i), errors)
    if isinstance(node, dict):
        properties = schema.get("properties", {})
        for key in schema.get("required", []):
            if key not in node:
                errors.append("%s: missing required '%s'" % (path, key))
        if schema.get("additionalProperties") is False:
            for key in node:
                if key not in properties:
                    errors.append("%s: unknown property '%s'" % (path, key))
        for key, value in node.items():
            if key in properties:
                check(value, properties[key], "%s.%s" % (path, key), errors)


def beyond_schema(document, name, shipped):
    """Constraints the Kotlin parser enforces that JSON Schema cannot express.

    Draft 2020-12's `uniqueItems` compares whole array entries, so it cannot
    say "no two companions share a `role`" — two entries differing only by
    file name are unique as values yet name the same slot. These rules are
    stated in the schema's `description` fields and enforced here and in
    `ModelManifest.parse`; the fixture round-trip keeps the three in step.
    """
    errors = []
    companions = document.get("companions") or []
    if not isinstance(companions, list):
        return errors
    roles = [c.get("role") for c in companions if isinstance(c, dict)]
    for role in sorted({r for r in roles if roles.count(r) > 1}):
        errors.append("%s: duplicate companion role '%s'" % (name, role))
    names = [document.get("file")] + [c.get("file") for c in companions if isinstance(c, dict)]
    names = [n for n in names if n is not None]
    for duplicate in sorted({n for n in names if names.count(n) > 1}):
        errors.append("%s: duplicate file name '%s'" % (name, duplicate))
    if shipped and "blake3" not in document:
        # MODEL_STORE.md §3: a shipped default has no import pass in which to
        # compute the post-mmap expectation, so it must declare one.
        errors.append("%s: a shipped manifest must declare 'blake3'" % name)
    return errors


def validate(path, schema, shipped):
    """Return a list of problems with the manifest at `path` (empty == valid)."""
    try:
        document = json.loads(path.read_text())
    except (ValueError, UnicodeDecodeError) as exc:
        return ["%s: not parseable as JSON (%s)" % (path.name, exc)]
    errors = []
    check(document, schema, path.name, errors)
    if isinstance(document, dict):
        errors += beyond_schema(document, path.name, shipped)
    return errors


def main():
    schema = json.loads(SCHEMA_PATH.read_text())
    failures = []
    checked = 0

    shipped = sorted(SHIPPED_DIR.glob("*.skein.json")) if SHIPPED_DIR.is_dir() else []
    for manifest in shipped:
        checked += 1
        failures += validate(manifest, schema, shipped=True)

    for manifest in sorted((FIXTURE_DIR / "valid").glob("*.skein.json")):
        checked += 1
        failures += validate(manifest, schema, shipped=False)

    for manifest in sorted((FIXTURE_DIR / "invalid").glob("*.skein.json")):
        checked += 1
        if not validate(manifest, schema, shipped=False):
            failures.append("%s: expected to be REJECTED, but it validated" % manifest.name)

    print("validate-manifests: checked %d manifest(s) (%d shipped)" % (checked, len(shipped)))
    for failure in failures:
        print("  FAIL %s" % failure, file=sys.stderr)
    if failures:
        print("validate-manifests: %d problem(s)" % len(failures), file=sys.stderr)
        return 1
    print("validate-manifests: OK")
    return 0


if __name__ == "__main__":
    sys.exit(main())
