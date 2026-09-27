# -*- coding: utf-8 -*-
"""
Yomikae - evaluation d'une serie : plusieurs chapitres raw (traduits par l'app) contre
l'edition humaine (mode extraction), alignes par position.

Usage : python series_eval.py <dossier_sidecars> <variante> <paires>
  <paires> : liste "idRaw:idRef,idRaw:idRef,..." (ids de chapitres dans la base de l'app) ;
             "id1+id2:id3+id4" colle plusieurs chapitres bout a bout quand les editions
             ne coupent pas aux memes endroits
  Les dossiers attendus : <dossier_sidecars>/raw_<idRaw>_<variante>/ et <dossier_sidecars>/en_<idRef>/
  ex. python series_eval.py "S:/Projet Mihon/eval/orv/sidecars" ko-en-local-paddle 1439:812,1438:811

Sortie : par chapitre, bulles appariees et score moyen ; puis le total et les pires bulles.
"""
import os, sys, io
sys.stdout.reconfigure(encoding='utf-8')
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from align_eval import load_strip, iou, similarity


def load_strips(dirs):
    """Several chapter folders in reading order, glued into one strip (editions cut differently)."""
    blocks, offset = [], 0.0
    for d in dirs:
        part, h = load_strip(d)
        for b in part:
            b['t'] += offset
            b['b'] += offset
        blocks += part
        offset += h
    return blocks, offset


def evaluate(raw_dirs, ref_dirs, min_iou=0.15):
    raw, hr = load_strips(raw_dirs)
    ref, hf = load_strips(ref_dirs)
    rows = []
    for rb in raw:
        best, bi = None, 0.0
        for fb in ref:
            v = iou(rb, fb)
            if v > bi:
                best, bi = fb, v
        if best is not None and bi >= min_iou:
            rows.append((rb['page'], rb['source'], best['target'], rb['target'], similarity(best['target'], rb['target'])))
    return rows, len(raw), len(ref), hr, hf


def main(base, variant, pairs):
    total = []
    for pair in pairs.split(','):
        raw_ids, ref_ids = pair.split(':')
        raw_dirs = [os.path.join(base, f'raw_{i}_{variant}') for i in raw_ids.split('+')]
        ref_dirs = [os.path.join(base, f'en_{i}') for i in ref_ids.split('+')]
        missing = [d for d in raw_dirs + ref_dirs if not os.path.isdir(d)]
        if missing:
            print(f'{pair}: dossier manquant {missing}'); continue
        rows, nraw, nref, hr, hf = evaluate(raw_dirs, ref_dirs)
        if not rows:
            print(f'{pair}: raw {nraw} blocs (bande {hr:.0f}px), ref {nref} blocs (bande {hf:.0f}px), aucun appariement'); continue
        s = [r[4] for r in rows]
        print(f'{pair}: raw {nraw} blocs, ref {nref} blocs, bandes {hr:.0f}/{hf:.0f}px | apparies {len(rows)} | '
              f'moyenne {sum(s)/len(s):.3f}, >=0.5 : {sum(1 for x in s if x >= 0.5)}/{len(s)}')
        total += rows
    if total:
        s = sorted(r[4] for r in total)
        print(f'\nTOTAL {len(total)} bulles : moyenne {sum(s)/len(s):.3f}, mediane {s[len(s)//2]:.3f}, >=0.5 : {sum(1 for x in s if x >= 0.5)}/{len(s)}')
        out = os.path.join(base, f'eval_{variant}.tsv')
        with io.open(out, 'w', encoding='utf-8') as f:
            f.write('page\tko\ten_human\ten_engine\tscore\n')
            for r in total:
                f.write('\t'.join(str(x) for x in r) + '\n')
        print(f'-> {out}')
        print('\npires bulles (KO | humain | moteur) :')
        for r in sorted(total, key=lambda r: r[4])[:10]:
            print(f'  [{r[4]:.2f}] {r[1][:35]} | {r[2][:50]} | {r[3][:50]}')


if __name__ == '__main__':
    main(sys.argv[1], sys.argv[2], sys.argv[3])
