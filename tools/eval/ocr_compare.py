# -*- coding: utf-8 -*-
# Fair OCR comparison: same LLM (PC 7B), same reference pairs (chapters 2-10 only),
# two OCR outputs of raw chapter 1 (ML Kit = raw_363, PaddleOCR = raw1_paddle).
import sys, os, io, random, time
sys.stdout.reconfigure(encoding='utf-8')
sys.path.insert(0, 'S:/Projet Mihon/Yomikae/tools/eval')
import experiment_refs as X
from align_eval import load_strip, iou, similarity
X.BASE = 'S:/Projet Mihon/eval/nml/sidecars'
BASE = X.BASE
WM = ["뉴토끼", "구글검색", "구글검", "웹툰미리보기", "웹튼미리보기", "미리보기", "짬툰", "마나토끼", "북토끼", "툰코", "Newtoki", "Toonkor"]
PARTS = ["가장", "빠른", "웹툰", "웹튼", "미리", "보기", "구글"]


def banner(t):
    c = ''.join(t.split())
    return any(w in c for w in WM) or sum(p in c for p in PARTS) >= 2


random.seed(1)
pool = []
for c in range(2, 11):
    pool += [(ko, en) for ko, en, _ in X.aligned_pairs(c)]
random.shuffle(pool)
refs = pool[:60]
ref_blocks, _ = load_strip(os.path.join(BASE, 'en_460'))
url = sys.argv[1] if len(sys.argv) > 1 else 'http://127.0.0.1:8080'
for name in ['raw_363', 'raw1_paddle']:
    raw, _ = load_strip(os.path.join(BASE, name))
    raw = [b for b in raw if not banner(b['source'])]
    pages = {}
    for b in raw:
        pages.setdefault(b['page'], []).append(b)
    t0 = time.time()
    for p in sorted(pages):
        outs = X.translate_page(url, [b['source'] for b in pages[p]], refs)
        for b, o in zip(pages[p], outs):
            b['new'] = o
    scores, rows = [], []
    for rb in raw:
        best, bi = None, 0.0
        for fb in ref_blocks:
            v = iou(rb, fb)
            if v > bi:
                best, bi = fb, v
        if best is not None and bi >= 0.15:
            s = similarity(best['target'].strip(), rb.get('new', ''))
            scores.append(s)
            rows.append((rb['page'], rb['source'], best['target'], rb.get('new', ''), s))
    xs = sorted(scores)
    print(f"{name}: {len(raw)} blocs, {len(scores)} apparies ; moyenne {sum(scores)/len(scores):.3f}, "
          f"mediane {xs[len(xs)//2]:.3f}, >=0.5 : {sum(1 for x in xs if x >= 0.5)}/{len(xs)}  ({time.time()-t0:.0f}s)")
    with io.open(os.path.join(BASE, '..', f'ocrcmp_{name}.tsv'), 'w', encoding='utf-8') as f:
        for r in rows:
            f.write('\t'.join(str(x) for x in r) + '\n')
