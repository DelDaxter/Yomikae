# -*- coding: utf-8 -*-
"""
Yomikae - conversation par chapitre + references de la memoire de serie, sur le PC.

Mesure l'effet du nombre de paires (coreen -> anglais humain, prises dans d'AUTRES chapitres)
envoyees en tete de conversation, des paires ajoutees page par page (comme dans l'app :
seules les paires qui partagent un mot avec la page, jamais deja envoyees), et du format des
lignes (numerotees "N| " ou non).

Usage : python session_refs.py <chapitre_test> <paires_en_tete> <paires_par_page_max> [random|relevant] [url] [num|plain]
  ex.  python session_refs.py 1 0 6 relevant http://127.0.0.1:8081 num
"""
import os, sys, io, json, random, time, re
sys.stdout.reconfigure(encoding='utf-8')
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from align_eval import load_strip, similarity
import experiment_refs
from experiment_refs import aligned_pairs, RAW_ID
from server_translate_session import chat, parse

# The sidecars live next to the study, not in the repo.
BASE = experiment_refs.BASE = r'S:/Projet Mihon/eval/nml/sidecars'

BACKGROUND = ("These are the speech bubbles of one page of a Korean webtoon, in reading order. "
              "Use natural spoken English as in published comics. Keep character names consistent.")
RULES = ("[Background Information]\n" + BACKGROUND + "\n\n"
         "Please accurately translate the following text into English, taking the provided background information "
         "into consideration. Each line below is one speech bubble and starts with its number and a vertical bar. "
         "Translate every line, keep exactly the same number of lines, and start each translated line with the same "
         "number and vertical bar as its source line. Only output the translated lines without any additional "
         "explanation. I will send the pages one by one; answer each page the same way.\n\n")
RULES_PLAIN = ("[Background Information]\n" + BACKGROUND + "\n\n"
               "Please accurately translate the following text into English, taking the provided background "
               "information into consideration. Each line below is one speech bubble. Translate every line, one "
               "translated line per source line, in the same order, with exactly the same number of lines. Only "
               "output the translated lines without any additional explanation. I will send the pages one by one; "
               "answer each page the same way.\n\n")
PER_CONVERSATION = 15
TOKEN = re.compile(r'[가-힣]{2,}')


def words(s):
    return set(TOKEN.findall(s))


def relevant(pool, lines, k, exclude):
    """Pairs sharing a Korean word with the page (most shared first), like SeriesMemory.promptPairs."""
    page_words = set()
    for l in lines:
        page_words |= words(l)
    scored = []
    for ko, en in pool:
        if ko in exclude:
            continue
        n = len(words(ko) & page_words)
        if n:
            scored.append((n, ko, en))
    scored.sort(key=lambda t: -t[0])
    return [(ko, en) for _, ko, en in scored[:k]]


def refs_block(pairs, first):
    if not pairs:
        return ""
    head = "Reference the following translations:\n" if first else "Also reference these translations:\n"
    return head + "\n".join(f"`{ko}` translates to `{en}`" for ko, en in pairs) + "\n\n"


def main(test_chapter, n_first, per_page, mode='relevant', url='http://127.0.0.1:8081', fmt='num'):
    random.seed(1)
    rules = RULES if fmt == 'num' else RULES_PLAIN
    pool = []
    for c in range(1, 11):
        if c != test_chapter and os.path.isdir(os.path.join(BASE, f'raw_{RAW_ID(c)}')):
            pool += [(ko, en) for ko, en, _ in aligned_pairs(c)]
    random.shuffle(pool)
    raw_blocks, _ = load_strip(os.path.join(BASE, f'raw_{RAW_ID(test_chapter)}'))
    pages = {}
    for b in raw_blocks:
        pages.setdefault(b['page'], []).append(b)
    all_lines = [b['source'] for bs in pages.values() for b in bs]

    if mode == 'random':
        head_pairs = pool[:n_first]
    else:
        head_pairs = relevant(pool, all_lines, n_first, set())
    sent = set(ko for ko, _ in head_pairs)

    t0 = time.time()
    messages, turns, tokens_sent = [], 0, 0
    mismatches = 0
    for page in sorted(pages):
        lines = [b['source'] for b in pages[page]]
        if not lines:
            continue
        if fmt == 'num':
            numbered = "\n".join(f"{i+1}| {l}" for i, l in enumerate(lines)) + "\n"
        else:
            numbered = "\n".join(lines) + "\n"
        reset = turns == 0
        if reset:
            sent = set(ko for ko, _ in head_pairs)
            new_pairs = relevant(pool, lines, per_page, sent) if per_page else []
            content = refs_block(head_pairs + new_pairs, True) + rules + numbered
            messages = [{"role": "user", "content": content}]
        else:
            new_pairs = relevant(pool, lines, per_page, sent) if per_page else []
            content = refs_block(new_pairs, False) + "Next page, same rules:\n\n" + numbered
            messages.append({"role": "user", "content": content})
        sent |= set(ko for ko, _ in new_pairs)
        tokens_sent += len(content) // 3
        answer = chat(url, messages)
        messages.append({"role": "assistant", "content": answer})
        turns = (turns + 1) % PER_CONVERSATION
        if fmt == 'num':
            outs = parse(answer, len(lines))
        else:
            outs = [l.strip().strip('`"') for l in answer.splitlines() if l.strip()]
            if len(outs) != len(lines):
                mismatches += 1
                outs = (outs + [''] * len(lines))[:len(lines)]
        for b, o in zip(pages[page], outs):
            b['new'] = o

    test_pairs = aligned_pairs(test_chapter, min_iou=0.15)
    key = lambda b: (b['page'], round(b['l']), round(b['t']))
    new_by_key = {key(b): b.get('new', '') for bs in pages.values() for b in bs}
    scores = [similarity(en, new_by_key.get(key(rb), '')) for ko, en, rb in test_pairs]
    good = sum(1 for s in scores if s >= 0.5)
    print(f"ch{test_chapter} head={n_first} page={per_page} {mode} {fmt} mismatch={mismatches}: "
          f"moyenne {sum(scores)/len(scores):.3f}, >=0.5 {good}/{len(scores)}, ~{tokens_sent} tokens envoyes, "
          f"{time.time()-t0:.0f}s")


if __name__ == '__main__':
    main(int(sys.argv[1]), int(sys.argv[2]), int(sys.argv[3]),
         sys.argv[4] if len(sys.argv) > 4 else 'relevant',
         sys.argv[5] if len(sys.argv) > 5 else 'http://127.0.0.1:8081',
         sys.argv[6] if len(sys.argv) > 6 else 'num')
