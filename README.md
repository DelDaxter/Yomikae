<div align="center">

# Yomikae

### Lecteur de manga avec traduction automatique des pages, sur l'appareil

Fork de [Mihon](https://github.com/mihonapp/mihon) qui ajoute la traduction automatique des pages
(coréen et japonais vers anglais et français), avec un système de plugins pour brancher
ses propres moteurs d'OCR, de traduction ou d'effacement.

[![License: Apache-2.0](https://img.shields.io/github/license/DelDaxter/Yomikae?labelColor=27303D&color=0877d2)](/LICENSE)

</div>

## Statut

Projet en cours de démarrage (septembre 2026). Pour l'instant, Yomikae est Mihon renommé : la traduction arrive
par paliers, décrits dans la feuille de route ci-dessous.

## Objectifs

- **100 % sur le téléphone** : aucun serveur requis. Les moteurs légers (Google ML Kit) tournent partout,
  les moteurs lourds (modèles ONNX, LLM locaux) sur les téléphones récents.
- **Coréen d'abord** (webtoons), japonais ensuite (manga, texte vertical).
- **Plugins** : des tiers peuvent ajouter des moteurs sans recompiler l'app, avec le même mécanisme
  que les extensions de Mihon (APK séparés, dépôt JSON, vérification de signature), plus un mode isolé
  pour les moteurs natifs.
- **Licences permissives uniquement** : Yomikae reste sous Apache-2.0, comme Mihon.

## Feuille de route

| Palier | Contenu | État |
| :--- | :--- | :--- |
| P0 | Fork, renommage, environnement de build | en cours |
| P1 | Traduction d'un chapitre coréen en tâche de fond avec ML Kit (OCR + traduction), pages traduites stockées à côté des originales | à faire |
| P2 | Interfaces de moteurs, traduction à la volée des chapitres en ligne, bascule original/traduit | à faire |
| P3 | Plugins APK (moteurs HTTP : API compatibles OpenAI, DeepL, serveur manga-image-translator) | à faire |
| P4 | Japonais : détection RT-DETR, OCR manga-ocr (ONNX), effacement LaMa | à faire |
| P5 | LLM local (Hy-MT2, Gemma 4) dans un moteur isolé, traduction directe vers le français | à faire |
| P6 | Publication, dépôt de plugins, CI | à faire |

## Compiler

Prérequis : JDK 21, SDK Android (platform 37.2, build-tools 37).

```
./gradlew assembleDebug
```

`-Pdist=ci` active la télémétrie Firebase de Mihon, que Yomikae n'utilise pas : ne pas l'utiliser.

## Crédits

Yomikae est un fork de [Mihon](https://github.com/mihonapp/mihon), lui-même issu de Tachiyomi.
Tout le lecteur, la bibliothèque et le système d'extensions viennent de Mihon.
Les extensions de sources Mihon fonctionnent telles quelles dans Yomikae.

## Licence

<pre>
Copyright © 2015 Javier Tomás
Copyright © 2024 Mihon Open Source Project
Copyright © 2026 Yomikae contributors

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
</pre>

## Avertissement

Les développeurs de ce projet n'ont aucun lien avec les fournisseurs de contenu disponibles via les
extensions. Ce projet n'héberge aucun contenu.
