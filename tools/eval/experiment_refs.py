# -*- coding: utf-8 -*-
"""
Experience "memoire de serie" : retraduit un chapitre raw en donnant au LLM des paires
(coreen -> anglais humain) prises dans d'AUTRES chapitres de la meme serie, puis mesure la
ressemblance avec la traduction humaine du chapitre teste. Compare avec la ligne de base
(traduction sans paires, lue dans les sidecars de l'app).

Usage : python experiment_refs.py <chapitre_test> <nb_paires> [url_serveur]
  ex.  python experiment_refs.py 1 60
"""
import json, os, sys, io, time, urllib.request, random
sys.stdout.reconfigure(encoding='utf-8')
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from align_eval import load_strip, iou, similarity

BASE = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'nml', 'sidecars')
RAW_ID = lambda n: 364 - n
EN_ID = lambda n: 461 - n
BACKGROUND = ("These are the speech bubbles of one page of the Korean adult comedy webtoon \"No Man's Land\" "
              "(남자없는 세계), in reading order. The hero is Lee Seonwoong (이선웅), an ordinary student summoned "
              "to a world without men. Use natural spoken English as in published comics: casual, punchy, "
              "explicit slang allowed. Keep character names consistent.")


def aligned_pairs(chapter, min_iou=0.4):
    raw, _ = load_strip(os.path.join(BASE, f'raw_{RAW_ID(chapter)}'))
    ref, _ = load_strip(os.path.join(BASE, f'en_{EN_ID(chapter)}'))
    pairs = []
    for rb in raw:
        best, best_iou = None, 0.0
        for fb in ref:
            v = iou(rb, fb)
            if v > best_iou:
                best, best_iou = fb, v
        if best is not None and best_iou >= min_iou:
            ko, en = rb['source'].strip(), best['target'].strip()
            if len(ko) >= 3 and len(en.split()) >= 2 and len(en) <= 90:
                pairs.append((ko, en, rb))
    return pairs


def complete(url, prompt, max_tokens=1500):
    body = {"model": "default", "messages": [{"role": "user", "content": prompt}],
            "temperature": 0.7, "top_p": 0.6, "top_k": 20, "repeat_penalty": 1.05, "max_tokens": max_tokens, "stream": False}
    req = urllib.request.Request(url + "/v1/chat/completions", data=json.dumps(body).encode('utf-8'),
                                 headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=600) as r:
        return json.load(r)["choices"][0]["message"]["content"]


def translate_page(url, lines, refs):
    p = ""
    if refs:
        p += "Reference the following translations:\n" + "\n".join(f"`{ko}` translates to `{en}`" for ko, en in refs) + "\n\n"
    p += "[Background Information]\n" + BACKGROUND + "\n\n"
    p += ("Please accurately translate the following text into English, taking the provided background information "
          "into consideration. Each numbered line is one speech bubble; translate every line, keep exactly the same "
          "number of lines and keep each line's prefix \"N| \" unchanged. Only output the translated lines without any "
          "additional explanation.\n\n")
    p += "\n".join(f"{i+1}| {l}" for i, l in enumerate(lines)) + "\n"
    out = complete(url, p)
    got, seq = {}, []
    for raw in out.splitlines():
        raw = raw.strip()
        if not raw:
            continue
        if '|' in raw:
            num, _, text = raw.partition('|')
            text = text.strip().strip('`"')
            if num.strip().isdigit():
                got[int(num.strip())] = text
            seq.append(text)
        else:
            seq.append(raw.strip('`"'))
    if len(got) == len(lines):
        return [got[i + 1] for i in range(len(lines))]
    # Fallback: the model dropped or mangled the numbers; trust the order.
    return [seq[i] if i < len(seq) else "" for i in range(len(lines))]


def main(test_chapter, n_refs, url="http://127.0.0.1:8080"):
    random.seed(1)
    # Reference pairs from the other chapters (never the tested one).
    pool = []
    for c in range(1, 11):
        if c != test_chapter and os.path.isdir(os.path.join(BASE, f'raw_{RAW_ID(c)}')):
            pool += [(ko, en) for ko, en, _ in aligned_pairs(c)]
    random.shuffle(pool)
    refs = pool[:n_refs]
    print(f'paires de reference disponibles : {len(pool)}, utilisees : {len(refs)}')

    # Test chapter: baseline scores come from the sidecars (engine output stored by the app).
    test_pairs = aligned_pairs(test_chapter, min_iou=0.15)
    print(f'chapitre {test_chapter} : {len(test_pairs)} bulles appariees a l humain')
    base = [similarity(en, rb['target']) for ko, en, rb in test_pairs]

    # Re-translate page by page with the references.
    by_page = {}
    for ko, en, rb in test_pairs:
        by_page.setdefault(rb['page'], []).append((ko, en, rb))
    # Keep page order and every block of the page (also the unmatched ones, for context).
    raw_blocks, _ = load_strip(os.path.join(BASE, f'raw_{RAW_ID(test_chapter)}'))
    pages = {}
    for b in raw_blocks:
        pages.setdefault(b['page'], []).append(b)

    new_scores, rows, t0 = [], [], time.time()
    for page in sorted(pages):
        lines = [b['source'] for b in pages[page]]
        outs = translate_page(url, lines, refs)
        for b, out in zip(pages[page], outs):
            b['new'] = out
    key = lambda b: (b['page'], round(b['l']), round(b['t']))
    new_by_key = {key(b): b.get('new', '') for bs in pages.values() for b in bs}
    for ko, en, rb in test_pairs:
        new = new_by_key.get(key(rb), '')
        s = similarity(en, new)
        new_scores.append(s)
        rows.append((rb['page'], ko, en, rb['target'], new, similarity(en, rb['target']), s))
    dt = time.time() - t0

    def stats(xs):
        xs_s = sorted(xs)
        return f"moyenne {sum(xs)/len(xs):.3f}, mediane {xs_s[len(xs)//2]:.3f}, >=0.5 : {sum(1 for x in xs if x>=0.5)}/{len(xs)}"
    print(f'\nSANS paires (app)      : {stats(base)}')
    print(f'AVEC {len(refs):3d} paires        : {stats(new_scores)}   ({dt:.0f}s pour {len(pages)} pages)')

    out_path = os.path.join(BASE, '..', f'ch{test_chapter}_refs{n_refs}.tsv')
    with io.open(out_path, 'w', encoding='utf-8') as f:
        f.write('page\tko\ten_human\ten_base\ten_refs\tscore_base\tscore_refs\n')
        for r in rows:
            f.write('\t'.join(str(x) for x in r) + '\n')
    print(f'-> {out_path}')
    # A few examples where the references changed the most.
    rows.sort(key=lambda r: r[6] - r[5], reverse=True)
    print('\nplus grandes ameliorations :')
    for r in rows[:8]:
        print(f'  [{r[5]:.2f}->{r[6]:.2f}] {r[1]} | humain: {r[2]} | avant: {r[3]} | apres: {r[4]}')
    print('\nplus grandes degradations :')
    for r in rows[-5:]:
        print(f'  [{r[5]:.2f}->{r[6]:.2f}] {r[1]} | humain: {r[2]} | avant: {r[3]} | apres: {r[4]}')


if __name__ == '__main__':
    main(int(sys.argv[1]), int(sys.argv[2]), sys.argv[3] if len(sys.argv) > 3 else "http://127.0.0.1:8080")
