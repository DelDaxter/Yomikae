# -*- coding: utf-8 -*-
"""
Yomikae - retraduit les sidecars d'un chapitre (texte OCR de l'app) avec un serveur LLM
compatible OpenAI, sans memoire de serie, et ecrit un nouveau dossier de sidecars avec les
memes boites. Sert a comparer le modele embarque au 7B du PC sur les memes lectures OCR.

Usage : python server_translate.py <dossier_source> <dossier_sortie> [url_serveur]
"""
import os, sys, io, json, glob, time
sys.stdout.reconfigure(encoding='utf-8')
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import experiment_refs as X

BACKGROUND = ("These are the speech bubbles and captions of one page of a Korean webtoon, in reading order. "
              "Use natural spoken English as in published comics. Keep character names consistent.")


def translate_page(url, lines):
    p = "[Background Information]\n" + BACKGROUND + "\n\n"
    p += ("Please accurately translate the following text into English, taking the provided background information "
          "into consideration. Each line below is one speech bubble and starts with its number and a vertical bar. "
          "Translate every line, keep exactly the same number of lines, and start each translated line with the same "
          "number and vertical bar as its source line. Only output the translated lines without any additional "
          "explanation.\n\n")
    p += "\n".join(f"{i+1}| {l}" for i, l in enumerate(lines)) + "\n"
    answer = X.complete(url, p)
    out = {}
    for raw in answer.splitlines():
        raw = raw.strip()
        if '|' not in raw:
            continue
        num, _, text = raw.partition('|')
        try:
            out[int(num.strip())] = text.strip()
        except ValueError:
            pass
    return [out.get(i + 1, '') for i in range(len(lines))]


def main(src, dst, url='http://127.0.0.1:8080'):
    os.makedirs(dst, exist_ok=True)
    t0 = time.time()
    pages = 0
    for f in sorted(glob.glob(os.path.join(src, '*.json'))):
        page = json.load(io.open(f, encoding='utf-8'))
        lines = [b['source'] for b in page['blocks']]
        if lines:
            outs = translate_page(url, lines)
            for b, o in zip(page['blocks'], outs):
                b['target'] = o
        with io.open(os.path.join(dst, os.path.basename(f)), 'w', encoding='utf-8') as g:
            json.dump(page, g, ensure_ascii=False, indent=1)
        pages += 1
    print(f'{src} -> {dst}: {pages} pages en {time.time() - t0:.0f}s')


if __name__ == '__main__':
    main(sys.argv[1], sys.argv[2], sys.argv[3] if len(sys.argv) > 3 else 'http://127.0.0.1:8080')
