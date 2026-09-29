#!/usr/bin/env python3
"""Independent read-only final diagnostic review; outputs only review, never policy edits."""
import argparse
import hashlib
import json
import math
from pathlib import Path
import struct
import unicodedata

FIXTURE_SHA = 'bf162254094103310102e28ad90b4c945bf24dd7f8f9a706039b2fe3a826b57c'
STOP = set('a an the of for to in on at by with from is are was were be been being do does did can could would should will shall may might must i me my we our you your he she it they them their this that these those what where when who why how which and or but if as than then there here not no'.split())

def check(value, label):
    if not value:
        raise AssertionError(label)

def same(actual, expected, label):
    if isinstance(expected, float):
        check(actual is not None and math.isclose(actual, expected, rel_tol=1e-12, abs_tol=1e-12), f'{label}: {actual!r} != {expected!r}')
    else:
        check(actual == expected, f'{label}: {actual!r} != {expected!r}')

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

def terms(text):
    out, term = set(), ''
    for char in unicodedata.normalize('NFC', text).lower():
        if unicodedata.category(char)[0] in 'LN':
            term += char
        else:
            if term:
                out.add(term)
                term = ''
    if term:
        out.add(term)
    return out - STOP

def coverage(query, hit):
    wanted = terms(query)
    return len(wanted & terms(hit['text'])) / len(wanted) if wanted else 0.0

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

def aggregate(rows, measured):
    positive = [r for r in rows if r['answerable']]
    absent = [r for r in rows if not r['answerable']]
    avg = lambda key: sum(r[key] for r in positive)/len(positive) if positive else None
    result = dict(queries=len(rows), answerable_queries=len(positive), absence_queries=len(absent),
                  covered_evidence_spans=sum(r['covered_spans'] for r in positive),
                  labelled_evidence_spans=len(positive), macro_recall_at_8=avg('recall_at_8'),
                  recall_queries_scored=len(positive), macro_ndcg_at_8=avg('ndcg_at_8'),
                  ndcg_queries_scored=len(positive), mrr=avg('reciprocal_rank'),
                  rejected_absence_queries=sum(r['rejected'] for r in absent),
                  absence_rejection_rate=sum(r['rejected'] for r in absent)/len(absent) if absent else None,
                  falsely_rejected_answerable_queries=sum(r['rejected'] for r in positive),
                  false_rejection_rate=sum(r['rejected'] for r in positive)/len(positive) if positive else None,
                  scope_violations=0, invalid_anchors=0, provenance_violations=0, duplicate_results=0,
                  nondeterministic_queries=0, recall_gate=.75, ndcg_gate=.60)
    result['ranking_gate_status'] = ('NOT_APPLICABLE' if not positive else
        'PASS' if result['macro_recall_at_8'] >= .75 and result['macro_ndcg_at_8'] >= .60 else 'FAIL')
    for key, value in result.items():
        same(measured[key], value, 'aggregate.'+key)
    return result

def validate_reserved(section, fixture):
    docs = {d['id']:d for d in fixture['documents']}
    qs = {q['id']:q for q in fixture['queries']}
    for key, value in dict(schema_version=1, split='public_reserved_validation', blind_benchmark=False,
                           fixture_sha256=FIXTURE_SHA, document_count=6, chunk_count=6, query_count=12,
                           validated_answer_spans=6, embedder=None, entity_extractor=None, vector_count=0,
                           full_hybrid_gate='INELIGIBLE', warmups_per_query=1, repetitions=3, ingest_warnings=[]).items():
        same(section[key],value,key)
    mode = section['mode']
    rows = mode['queries']
    same(len(rows),12,'reserved row count')
    same({r['id'] for r in rows},set(qs),'reserved query IDs')
    observed_docs, hit_count, reviewed = set(), 0, []
    known_ids = {}
    for row in rows:
        q = qs[row['id']]
        positive = any(label['grade']==3 for label in q['relevant'])
        same(row['category'],q['category'],'category')
        same(row['space_alias'],'default','space alias')
        same(row['answerable'],positive,'answerable')
        kinds = section['returned_source_kinds'][q['id']]
        same(len(row['runs']),3,'repetitions')
        same(len(kinds),3,'source-kind repetitions')
        first = row['runs'][0]['results']
        for run, sourcekinds in zip(row['runs'],kinds):
            hits = run['results']
            check(len(hits)<=8,'bounded top8')
            same(hits,first,'deterministic ordered values')
            same(len({h['chunk_id'] for h in hits}),len(hits),'unique chunks')
            same(sourcekinds,['NOTE']*len(hits),'eligible sources')
            same(run['fingerprint'],fingerprint(hits,sourcekinds),'source fingerprint')
            check(math.isfinite(run['elapsed_ms']) and run['elapsed_ms']>=0,'elapsed millis')
            for hit in hits:
                check(hit['doc_id'] in docs,'returned doc exists in reserved fixture')
                doc = docs[hit['doc_id']]
                body = doc['body_md'].encode()
                same(hit['doc_title'],doc['title'],'title identity')
                same(hit['revision_hash'],revision(doc['body_md']),'revision recomputation')
                same(hit['byte_start'],0,'short paragraph start')
                same(hit['byte_end'],len(body),'short paragraph end')
                same(hit['text'],body[hit['byte_start']:hit['byte_end']].decode(),'exact source bytes')
                check('VECTOR' not in hit['recalled_by'],'no synthetic vector claim')
                check(set(hit['recall_scores']) <= set(hit['recalled_by']),'score provenance')
                check(all(math.isfinite(s) for s in [hit['score'],*hit['recall_scores'].values()]),'finite scores')
                identity = (hit['doc_id'],hit['revision_hash'],hit['byte_start'],hit['byte_end'],hit['text'])
                if hit['chunk_id'] in known_ids:
                    same(identity,known_ids[hit['chunk_id']],'chunk identity across queries')
                known_ids[hit['chunk_id']] = identity
                observed_docs.add(hit['doc_id'])
                hit_count += 1
        grades = []
        credited = False
        labels = {l['doc_id']: l for l in q['relevant']}
        for hit in first:
            label = labels.get(hit['doc_id'])
            if label:
                doc = docs[hit['doc_id']]
                same(doc['body_md'].count(label['evidence']),1,'unique labelled span')
                check(label['evidence'] in hit['text'],'complete labelled evidence in returned chunk')
            if label and label['grade']==3 and not credited:
                grades.append(3)
                credited = True
            else:
                grades.append(min(label['grade'],2) if label else 0)
        dcg = sum((2**g-1)/math.log2(i+2) for i,g in enumerate(grades)) if positive else None
        expected = dict(covered_spans=int(credited), labelled_spans=int(positive),
                        recall_at_8=float(credited) if positive else None, dcg_at_8=dcg,
                        ideal_dcg_at_8=7.0 if positive else None, ndcg_at_8=dcg/7 if positive else None,
                        reciprocal_rank=1/(grades.index(3)+1) if 3 in grades else (0.0 if positive else None),
                        grades=grades, duplicate_results=0, scope_violation_chunk_ids=[], invalid_anchor_chunk_ids=[],
                        provenance_violation_chunk_ids=[], rejected=not first, deterministic=True)
        for key,value in expected.items():
            same(row[key],value,q['id']+'.'+key)
        reviewed.append(dict(id=q['id'], category=q['category'],answerable=positive,
            classification=('answerable_covered' if credited else 'answerable_false_rejection' if not first else 'answerable_uncovered')
                if positive else ('absence_rejected' if not first else 'absence_false_acceptance'),
            candidate_count=len(first), max_query_coverage=max((coverage(q['query'],h) for h in first),default=0.0),
            **expected))
    total = aggregate(reviewed,mode['summary'])
    for category, summary in mode['categories'].items():
        aggregate([r for r in reviewed if r['category']==category],summary)
    quality = 'PASS' if all((r['covered_spans']==r['labelled_spans']) if r['answerable'] else r['rejected'] for r in reviewed) else 'FAIL'
    same(section['validation_status'],quality,'reserved quality status remains literal')
    return dict(validation_status=quality,summary=total,rows=reviewed,observed_document_count=len(observed_docs),
                source_occurrences_verified=hit_count,revision_hashes={k:revision(v['body_md']) for k,v in docs.items()})

def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--report',type=Path,required=True)
    parser.add_argument('--fixture',type=Path,required=True)
    parser.add_argument('--source',required=True)
    parser.add_argument('--preliminary',type=Path,required=True)
    parser.add_argument('--calibration',type=Path,required=True)
    parser.add_argument('--output',type=Path,required=True)
    args=parser.parse_args()
    reportbytes=args.report.read_bytes(); report=json.loads(reportbytes)
    fixturebytes=args.fixture.read_bytes(); same(sha(fixturebytes),FIXTURE_SHA,'frozen fixture bytes')
    fixture=json.loads(fixturebytes)
    same(report['build_revision'],args.source,'source revision')
    same(report['evidence_policy'],dict(version='lexical-query-coverage-v1',minimum_query_coverage=.5,semantic_vector_policy='uncalibrated_bypass'),'frozen policy')
    same(report['full_hybrid_gate'],'INELIGIBLE','hybrid eligibility')
    sections={k:validate_reserved(v,fixture) for k,v in report['rejection_validation'].items()}
    same(set(sections),{'production_policy','ungated_control'},'control sections')
    policy=report['rejection_validation']['production_policy']['mode']['queries']
    control={r['id']:r for r in report['rejection_validation']['ungated_control']['mode']['queries']}
    queries={q['id']:q for q in fixture['queries']}
    for row in policy:
        original=control[row['id']]['runs'][0]['results']
        accept=any(coverage(queries[row['id']]['query'],hit)>=.5 for hit in original)
        same(row['runs'][0]['results'],original if accept else [],'reserved exact policy/control '+row['id'])
    prelim=json.loads(args.preliminary.read_bytes()); calibration=json.loads(args.calibration.read_bytes())
    same(calibration['source_report_sha256'],sha(args.preliminary.read_bytes()),'development calibration source')
    same(report['gold_sha256'],prelim['gold_sha256'],'gold unchanged')
    same(report['corpus_sha256'],prelim['corpus_sha256'],'corpus unchanged')
    preliminary_modes={m['name']:m for m in prelim['modes']}
    development={}
    for mode in report['modes']:
        base=preliminary_modes[mode['name']]
        base_rows={r['id']:r for r in base['queries']}
        cal_rows={r['id']:r for r in calibration['modes'][mode['name']]['rows']}
        same({r['id'] for r in mode['queries']},set(base_rows),'development query set')
        for row in mode['queries']:
            prior=base_rows[row['id']]['runs'][0]['results']
            expect=prior if cal_rows[row['id']]['max_query_coverage']>=.5 else []
            for run in row['runs']:
                same(run['results'],expect,'actual development gate against preserved results '+mode['name']+'/'+row['id'])
        predicted=next(r for r in calibration['modes'][mode['name']]['coverage_sweep'] if r['threshold']==.5)
        actual=mode['summary']
        for key, predictedkey in [('macro_recall_at_8','recall_at_8_after_query_gate'),('macro_ndcg_at_8','ndcg_at_8_after_query_gate'),
            ('rejected_absence_queries','rejected_absence_queries'),('falsely_rejected_answerable_queries','falsely_rejected_answerable_queries')]:
            same(actual[key],predicted[predictedkey],'development measured/prediction '+mode['name']+'/'+key)
        development[mode['name']]={'actual':actual,'preliminary_summary':base['summary'],
            'coverage_delta':actual['covered_evidence_spans']-base['summary']['covered_evidence_spans']}
    audit=report['lexical_probe_audit']
    for key,value in dict(audit_active=True, documents_written=850,documents_without_rows=0,legacy_unprobeable_documents=0,
                           legacy_probes=850,legacy_top50_misses=508,legacy_misses_with_row_match=508,
                           legacy_misses_without_row_match=0,legacy_hits_without_row_match=0,row_match_queries=850).items():
        same(audit[key],value,'FTS audit '+key)
    same(report['ingest_warnings'],[],'no production ingest warnings')
    metrics=['covered_evidence_spans','macro_recall_at_8','macro_ndcg_at_8','rejected_absence_queries','falsely_rejected_answerable_queries']
    delta={key:sections['production_policy']['summary'][key]-sections['ungated_control']['summary'][key] for key in metrics}
    output=dict(schema_version=1, reviewer='fts_verification independent bounded review',report_path=str(args.report),
        source_revision=args.source,report_sha256=sha(reportbytes),fixture_sha256=FIXTURE_SHA,
        integrity_status='PASS',quality_status=sections['production_policy']['validation_status'],
        reserved=sections,reserved_policy_minus_control=delta,development=development,lexical_probe_audit=audit,
        limitations=['Public reserved diagnostic, not blind benchmark or answer-generation accuracy',
                    'No vector or full-hybrid validation', 'FTS checks sampled legacy term postings, not full posting integrity'])
    with args.output.open('x') as stream:
        json.dump(output,stream,indent=2); stream.write('\n')
    print(json.dumps({k:output[k] for k in ['report_sha256','integrity_status','quality_status','reserved_policy_minus_control']},indent=2))

if __name__=='__main__':
    main()
