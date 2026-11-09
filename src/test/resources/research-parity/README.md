# Research parity fixtures (ticket #13, 03 F-027)

42 real Deepgram results from `step2/research` (first 60 s, English), kept as timings, confidences and sentence spans.
No speech: every word that is not a filler, hedge, habit marker or function word is `w`, and clips are numbered
(`b1-NN`, `fx-real-NN`). `ResearchFixtureParityTest` fails if a real word is added.

- `clips/*.tsv`: `N` id, `D` duration (ms), `W` word, start (ms), end (ms), confidence, `S` first and last word of a sentence.
- `expected.tsv`: expected values. `script_*` columns are what `step2/research/scripts/metrics_v2.py` prints; the other
  columns come from an independent Python reading of requirements version 1.7. Neither comes from the Java code.

The research script (`step2/research/scripts/metrics_v2.py`), the sample clips and the generator
(`step2/research/scripts/make_parity_fixtures.py`) are in `step2/`, which is git-ignored, so they exist on the founder's PC only.
The `script_*` values in `expected.tsv` are the script's output copied here, so CI needs none of them.
Rebuild (founder's PC only): `python -I step2/research/scripts/make_parity_fixtures.py`.
See AD-98 in `docs/architecture/DECISIONS.md`.
