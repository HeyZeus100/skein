#!/usr/bin/env python3
"""Development-only replay of the bounded requested-value policy.

Refuses non-development data; retains the original gold hash and result order.
Independent/reserved validation must never be supplied to this calibration.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re

from analyze_retrieval_signals import terms, coverage

NUMBER_TEXT = (r"(?:[0-9]+(?:[.,][0-9]+)*|zero|one|two|three|four|five|six|seven|eight|nine|ten|"
               r"eleven|twelve|thirteen|fourteen|fifteen|sixteen|seventeen|eighteen|nineteen|twenty|hundred|thousand)")
MONEY = re.compile(r"(?:[$€£¥]\s*[0-9]|\b" + NUMBER_TEXT +
                   r"\s*(?:dollars?|euros?|pounds?|yen|cents?|USD|EUR|GBP)\b|\b(?:free|complimentary|no charge)\b)", re.I)
UNITLESS = re.compile(r"\b(?:is|are|was|were|costs?|totals?|equals?)\s+" + NUMBER_TEXT + r"\b", re.I)
DURATION = re.compile(r"\b" + NUMBER_TEXT + r"\s*(?:milliseconds?|seconds?|minutes?|hours?|days?|weeks?|months?|years?)\b", re.I)
COUNT = re.compile(r"\b" + NUMBER_TEXT + r"\b", re.I)
SENTENCES = re.compile(r"[!?;\n]+|(?<![0-9])\.(?![0-9])|(?<=[0-9])\.(?![0-9])")


def requested(query):
    if re.search(r"^\s*(?:where|who|when|why)\b", query, re.I):
        return None
    if re.search(r"^\s*how long\b", query, re.I):
        return DURATION
    if re.search(r"^\s*how many\b", query, re.I):
        return COUNT
    if (re.search(r"^\s*(?:what (?:is|are|was|were)|how much)\b", query, re.I) and
            re.search(r"\b(?:cost|costs|price|fee|fees|fare|premium|tuition|salary|rent|budget)\b", query, re.I)):
        return MONEY
    return None


def accepts(query, results, threshold):
    query = query[:8192]
    wanted = terms(query)
    if not wanted:
        return False
    kind = requested(query)
    for result in results:
        text = result['text'][:32768]
        if kind is None and coverage(query, {'text': text}) >= 0.5:
            return True
        if kind:
            for sentence in SENTENCES.split(text):
                overlap = len(wanted & terms(sentence))
                ratio = overlap / len(wanted)
                value = kind.search(sentence) or (kind is MONEY and UNITLESS.search(sentence) and not DURATION.search(sentence))
                if value and (ratio >= 0.5 or (ratio >= threshold and overlap >= 2)):
                    return True
    return False


def calibrate(report_path, gold_path, controls_path):
    rb, gb, cb = report_path.read_bytes(), gold_path.read_bytes(), controls_path.read_bytes()
    report, gold, controls = map(json.loads, (rb, gb, cb))
    if any(data['split'] != 'development' for data in (report, gold, controls)):
        raise ValueError('Only development inputs are permitted')
    if report['gold_sha256'] != hashlib.sha256(gb).hexdigest():
        raise ValueError('Original measured gold differs')
    if report.get('vector_count') != 0 or report.get('evidence_policy') is not None:
        raise ValueError('Replay requires vector-free ungated input')
    if report.get('evidence_selection') != 'production ranked top 8; no calibrated weak-evidence rejection policy':
        raise ValueError('Expected original ungated production ranking')
    queries = {q['id']: q for q in gold['queries']}
    if len(queries) != len(gold['queries']):
        raise ValueError('Duplicate gold query')
    modes = [m['name'] for m in report['modes']]
    if sorted(modes) != ['graph_only', 'lexical_graph_default', 'lexical_only']:
        raise ValueError('Invalid mode inventory')
    for mode in report['modes']:
        ids = [q['id'] for q in mode['queries']]
        if len(ids) != len(queries) or set(ids) != set(queries):
            raise ValueError('Missing or duplicate query')
    sweep = []
    for cutoff in (0.25, 0.5, 0.75, 1.0):
        modes = {}
        for mode in report['modes']:
            rows = mode['queries']
            decisions = [(row, accepts(queries[row['id']]['query'], row['runs'][0]['results'], cutoff)) for row in rows]
            answers = [(r, a) for r, a in decisions if r['answerable']]
            absent = [(r, a) for r, a in decisions if not r['answerable']]
            modes[mode['name']] = {
                'answerable_queries': len(answers), 'absence_queries': len(absent),
                'rejected_absence_queries': sum(not a for _, a in absent),
                'falsely_rejected_answerable_queries': sum(not a for _, a in answers),
                'recall_at_8': sum(r['recall_at_8'] if a else 0 for r, a in answers) / len(answers),
                'ndcg_at_8': sum(r['ndcg_at_8'] if a else 0 for r, a in answers) / len(answers),
                'newly_lost_answer_spans': [r['id'] for r, a in answers if not a and r['covered_spans'] > 0],
            }
        control_rows = [{'id': r['id'], 'supported': r['supported'],
                         'accepted': accepts(r['query'], [r], cutoff)} for r in controls['cases']]
        sweep.append({'minimum_value_coverage': cutoff, 'modes': modes, 'controls': control_rows,
                      'controls_correct': sum(r['supported'] == r['accepted'] for r in control_rows)})
    return {'schema_version': 1, 'split': 'development', 'production_candidate': 'lexical-fact-shape-v2-experimental',
            'minimum_query_coverage': 0.5, 'source_report_sha256': hashlib.sha256(rb).hexdigest(),
            'gold_sha256': hashlib.sha256(gb).hexdigest(), 'controls_sha256': hashlib.sha256(cb).hexdigest(),
            'selection_rule': 'Lowest coarse typed-value cutoff preserving default development recall, rejecting every development absence and passing all declared support controls; no independent validation consulted.',
            'limitation': 'Replay only; not fresh runtime, entailment, general paraphrase or hybrid evidence. UTF-16/codepoint bounds are not exercised by this ASCII development data.',
            'sweep': sweep}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--report', type=Path, required=True)
    parser.add_argument('--gold', type=Path, default=Path('testing/src/main/resources/eval/gold.json'))
    parser.add_argument('--controls', type=Path, default=Path('core/rag/src/test/resources/eval/requested-value-development.json'))
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    args.output.write_text(json.dumps(calibrate(args.report, args.gold, args.controls), indent=2) + '\n')
