"""Read-only source oracle for the two frozen short-note validation corpora.

This runs only after policy freeze, against retained measured results. It never
calls retrieval, influences source selection, or changes gold/report bytes.
"""
import hashlib
import struct


def check(value, label):
    if not value:
        raise ValueError(label)


def sha(data):
    return hashlib.sha256(data).hexdigest()


# Independent bounded BLAKE3 single-chunk implementation, used only for these
# short frozen bodies. Public empty/abc vectors guard compression framing.
def blake3_short(data):
    check(len(data) <= 1024, 'bounded BLAKE3 input')
    iv = [0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19]
    perm = [2, 6, 3, 10, 7, 0, 4, 13, 1, 11, 12, 5, 9, 14, 15, 8]
    cv = iv[:]
    blocks = [data[i:i+64] for i in range(0, len(data), 64)] or [b'']
    for bi, block in enumerate(blocks):
        end = bi == len(blocks) - 1
        flags = (1 if bi == 0 else 0) | (2 | 8 if end else 0)
        v = cv[:] + iv[:4] + [0, 0, len(block), flags]
        m = list(struct.unpack('<16I', block.ljust(64, b'\0')))
        def ror(x, n):
            return ((x >> n) | (x << (32-n))) & 0xffffffff
        def g(a,b,c,d,x,y):
            v[a] = (v[a] + v[b] + x) & 0xffffffff
            v[d] = ror(v[d] ^ v[a],16)
            v[c] = (v[c] + v[d]) & 0xffffffff
            v[b] = ror(v[b] ^ v[c],12)
            v[a] = (v[a] + v[b] + y) & 0xffffffff
            v[d] = ror(v[d] ^ v[a],8)
            v[c] = (v[c] + v[d]) & 0xffffffff
            v[b] = ror(v[b] ^ v[c],7)
        for _ in range(7):
            g(0,4,8,12,m[0],m[1]); g(1,5,9,13,m[2],m[3])
            g(2,6,10,14,m[4],m[5]); g(3,7,11,15,m[6],m[7])
            g(0,5,10,15,m[8],m[9]); g(1,6,11,12,m[10],m[11])
            g(2,7,8,13,m[12],m[13]); g(3,4,9,14,m[14],m[15])
            m = [m[i] for i in perm]
        cv = [v[i] ^ v[i+8] for i in range(8)]
    return struct.pack('<8I', *cv).hex()

check(blake3_short(b'') == 'af1349b9f5f9a1a6a0404dea36dcc9499bcb25c9adc112b7cc9a93cae41f3262', 'empty BLAKE3')
check(blake3_short(b'abc') == '6437b3ac38465133ffb63b75273a8db548c558465d79db03fd359c6cd5bd9d85', 'abc BLAKE3')

def revision(body):
    body = body.replace('\r\n','\n').replace('\r','\n').encode()
    return blake3_short(b'skein/revision/v1\0' + struct.pack('<Q',2) + b'{}' + body)

def fingerprint(hits, kinds):
    def bits(value):
        return str(struct.unpack('>q',struct.pack('>d',value))[0])
    def frame(value):
        return str(len(value.encode('utf-16-le')) // 2) + ':' + value
    encoded = ''
    for hit, kind in zip(hits, kinds):
        # Every reserved source is a short one-paragraph ordinal-zero chunk.
        loc = f"Locator(byteStart={hit['byte_start']}, byteEnd={hit['byte_end']}, chunkOrd=0)"
        enumorder = ['VECTOR','LEXICAL','GRAPH']
        scores = ','.join(k+':'+bits(hit['recall_scores'][k]) for k in enumorder if k in hit['recall_scores'])
        values = [str(hit['chunk_id']), hit['doc_id'], hit['doc_title'], kind, hit['revision_hash'] or '', loc,
                  bits(hit['score']), scores, ','.join(sorted(hit['recalled_by'])), hit['text']]
        encoded += ''.join(map(frame, values))
    return sha(encoded.encode())

def validate_sources(section, fixture):
    documents = {d["id"]: d for d in fixture["documents"]}
    queries = {q["id"]: q for q in fixture["queries"]}
    occurrences = 0
    for row in section["mode"]["queries"]:
        query = queries[row["id"]]
        labels = {e["doc_id"]: e for e in query["relevant"]}
        kinds = section["returned_source_kinds"][query["id"]]
        for run, source_kinds in zip(row["runs"], kinds):
            hits = run["results"]
            check(run["fingerprint"] == fingerprint(hits, source_kinds), "source fingerprint differs from raw arrays")
            for hit in hits:
                document = documents[hit["doc_id"]]
                body = document["body_md"].encode()
                check(document["persona_id"] == query["persona_id"] and
                      hit["doc_id"] not in query.get("forbidden_doc_ids", []), "source belongs to forbidden Space")
                check(hit["doc_title"] == document["title"], "source title differs from fixture")
                check(hit["revision_hash"] == revision(document["body_md"]), "source revision hash differs from fixture")
                check(hit["byte_start"] == 0 and hit["byte_end"] == len(body), "short source locator differs from fixture")
                check(hit["text"].encode() == body, "source bytes differ from fixture")
                occurrences += 1
        credited = False
        grades = []
        for hit in row["runs"][0]["results"]:
            label = labels.get(hit["doc_id"])
            if label:
                check(documents[hit["doc_id"]]["body_md"].count(label["evidence"]) == 1,
                      "labelled source span is not unique")
                check(label["evidence"] in hit["text"], "returned source does not contain labelled evidence")
            if label and label["grade"] == 3 and not credited:
                grades.append(3)
                credited = True
            else:
                grades.append(min(label["grade"], 2) if label else 0)
        check(row["grades"] == grades, "reported grades differ from source evidence")
        check(row["covered_spans"] == int(credited), "reported evidence count differs from source evidence")
    return dict(reviewed_queries=len(queries), reviewed_source_occurrences=occurrences,
                source_bytes_anchors_revisions_fingerprints_and_grades="PASS")
