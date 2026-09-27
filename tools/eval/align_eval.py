# -*- coding: utf-8 -*-
"""
Yomikae - alignement raw / edition traduite et evaluation du moteur.

Entree : deux dossiers de "sidecars" JSON produits par l'app (un par page : NNN.json avec
page, width, height, blocks[{source, target, left, top, right, bottom}]).
  - raw_dir : chapitre raw traduit par le moteur (source = coreen OCR, target = traduction moteur)
  - ref_dir : meme chapitre de l'edition traduite, en mode extraction (source = target = anglais OCR)

Les deux editions sont la meme bande verticale, redimensionnee et decoupee differemment.
On ramene tout a une largeur commune, on cumule les hauteurs de pages pour obtenir une
coordonnee "bande", puis on apparie les blocs par recouvrement de boites.

Sortie : un tableau coreen | anglais humain | anglais moteur | score, et des statistiques.
Usage : python align_eval.py <raw_dir> <ref_dir> [sortie.tsv]
"""
import json, os, sys, io, difflib, re

COMMON_WIDTH = 720.0


def load_strip(d):
    """Returns (blocks, total_height) with boxes in common-width strip coordinates."""
    files = sorted(f for f in os.listdir(d) if f.endswith('.json'))
    blocks, offset = [], 0.0
    for f in files:
        page = json.load(io.open(os.path.join(d, f), encoding='utf-8'))
        scale = COMMON_WIDTH / page['width']
        for b in page['blocks']:
            blocks.append({
                'source': b['source'], 'target': b['target'],
                'l': b['left'] * scale, 't': offset + b['top'] * scale,
                'r': b['right'] * scale, 'b': offset + b['bottom'] * scale,
                'page': page['page'],
            })
        offset += page['height'] * scale
    return blocks, offset


def iou(a, b):
    w = min(a['r'], b['r']) - max(a['l'], b['l'])
    h = min(a['b'], b['b']) - max(a['t'], b['t'])
    if w <= 0 or h <= 0:
        return 0.0
    inter = w * h
    area_a = (a['r'] - a['l']) * (a['b'] - a['t'])
    area_b = (b['r'] - b['l']) * (b['b'] - b['t'])
    return inter / (area_a + area_b - inter)


def norm(s):
    return re.sub(r'[^a-z0-9 ]', '', s.lower()).split()


def similarity(a, b):
    """0..1 : ratio of shared words (order-insensitive) blended with a sequence ratio."""
    wa, wb = norm(a), norm(b)
    if not wa or not wb:
        return 0.0
    shared = len(set(wa) & set(wb)) / max(len(set(wa)), len(set(wb)))
    seq = difflib.SequenceMatcher(None, ' '.join(wa), ' '.join(wb)).ratio()
    return 0.5 * shared + 0.5 * seq


def main(raw_dir, ref_dir, out_path=None):
    raw, raw_h = load_strip(raw_dir)
    ref, ref_h = load_strip(ref_dir)
    print(f'raw: {len(raw)} blocks, strip {raw_h:.0f}px | ref: {len(ref)} blocks, strip {ref_h:.0f}px '
          f'(hauteur ecart {abs(raw_h - ref_h):.0f}px)')

    # Greedy matching: raw block -> best overlapping ref block (a ref block may collect
    # several raw blocks when the translator merged bubbles).
    pairs, unmatched = [], []
    for rb in raw:
        best, best_iou = None, 0.0
        for fb in ref:
            v = iou(rb, fb)
            if v > best_iou:
                best, best_iou = fb, v
        if best is not None and best_iou >= 0.15:
            pairs.append((rb, best, best_iou))
        else:
            unmatched.append(rb)

    rows, scores = [], []
    for rb, fb, v in pairs:
        s = similarity(fb['target'], rb['target'])
        scores.append(s)
        rows.append((rb['page'], f'{v:.2f}', rb['source'], fb['target'], rb['target'], f'{s:.2f}'))

    print(f'apparies : {len(pairs)} / {len(raw)} blocs raw ; non apparies : {len(unmatched)}')
    if scores:
        scores_sorted = sorted(scores)
        print(f'similarite moteur vs humain : moyenne {sum(scores)/len(scores):.2f}, '
              f'mediane {scores_sorted[len(scores)//2]:.2f}, '
              f'>= 0.5 : {sum(1 for s in scores if s >= 0.5)}/{len(scores)}')

    print('\npage | iou  | coreen (OCR) | anglais humain | anglais moteur | score')
    for r in rows:
        print(' | '.join(str(x) for x in r))

    if out_path:
        with io.open(out_path, 'w', encoding='utf-8') as f:
            f.write('page\tiou\tko\ten_human\ten_engine\tscore\n')
            for r in rows:
                f.write('\t'.join(str(x) for x in r) + '\n')
        print(f'\n-> {out_path}')


if __name__ == '__main__':
    sys.stdout.reconfigure(encoding='utf-8')
    main(sys.argv[1], sys.argv[2], sys.argv[3] if len(sys.argv) > 3 else None)
