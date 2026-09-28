# -*- coding: utf-8 -*-
"""
Yomikae - variante "conversation par chapitre" de server_translate.py : les regles ne sont
envoyees qu'au premier tour, chaque page suivante est un nouveau tour de la meme conversation
(l'historique reste dans le contexte). Sert a verifier, avant de le coder dans l'app, que ce
mode ne degrade pas la qualite ; sur le telephone il evite de relire les regles a chaque page
(le prefill est ce qui coute le plus).

Usage : python server_translate_session.py <dossier_source> <dossier_sortie> [url] [pages_par_conversation]
"""
import os, sys, io, json, glob, time, urllib.request

sys.stdout.reconfigure(encoding='utf-8')
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import server_translate as base

RULES = ("[Background Information]\n" + base.BACKGROUND + "\n\n"
         "Please accurately translate the following text into English, taking the provided background information "
         "into consideration. Each line below is one speech bubble and starts with its number and a vertical bar. "
         "Translate every line, keep exactly the same number of lines, and start each translated line with the same "
         "number and vertical bar as its source line. Only output the translated lines without any additional "
         "explanation. I will send the pages one by one; answer each page the same way.\n\n")


def chat(url, messages, max_tokens=1500):
    body = {"model": "default", "messages": messages,
            "temperature": 0.7, "top_p": 0.6, "top_k": 20, "repeat_penalty": 1.05, "max_tokens": max_tokens, "stream": False}
    req = urllib.request.Request(url + "/v1/chat/completions", data=json.dumps(body).encode('utf-8'),
                                 headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=600) as r:
        return json.load(r)["choices"][0]["message"]["content"]


def parse(answer, n):
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
    return [out.get(i + 1, '') for i in range(n)]


def main(src, dst, url='http://127.0.0.1:8081', per_conversation=15):
    os.makedirs(dst, exist_ok=True)
    t0 = time.time()
    messages, turns, pages = [], 0, 0
    for f in sorted(glob.glob(os.path.join(src, '*.json'))):
        page = json.load(io.open(f, encoding='utf-8'))
        lines = [b['source'] for b in page['blocks']]
        if lines:
            numbered = "\n".join(f"{i+1}| {l}" for i, l in enumerate(lines)) + "\n"
            if turns == 0:
                messages = [{"role": "user", "content": RULES + numbered}]
            else:
                messages.append({"role": "user", "content": "Next page:\n\n" + numbered})
            answer = chat(url, messages)
            messages.append({"role": "assistant", "content": answer})
            turns += 1
            if turns >= per_conversation:
                turns = 0
            outs = parse(answer, len(lines))
            for b, o in zip(page['blocks'], outs):
                b['target'] = o
        with io.open(os.path.join(dst, os.path.basename(f)), 'w', encoding='utf-8') as g:
            json.dump(page, g, ensure_ascii=False, indent=1)
        pages += 1
    print(f'{src} -> {dst}: {pages} pages en {time.time() - t0:.0f}s (conversation de {per_conversation} pages)')


if __name__ == '__main__':
    main(sys.argv[1], sys.argv[2], sys.argv[3] if len(sys.argv) > 3 else 'http://127.0.0.1:8081',
         int(sys.argv[4]) if len(sys.argv) > 4 else 15)
