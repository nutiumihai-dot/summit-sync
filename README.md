# Summit Sync

Aplicație Android fără acces la internet: citește din Health Connect pașii, somnul și pulsul, le rezumă pe zi, verifică dacă sunt complete și le predă aplicației Summit Building.

APK: https://github.com/nutiumihai-dot/summit-sync/releases/latest/download/summit-sync.apk

## Rezultatul (`#hc=<cod>.<payload>`)

JSON → deflate (fără antet) → base64url. Pe lângă `zile` (rezumatul pe zi, vezi `COACH.md` → „Brățara”), din 0.13 există `detaliu: {"AAAA-LL-ZZ": {...}}`, câte o intrare pentru fiecare zi din `zile`. Toate `m` sunt minute întregi de la 00:00 ora locală a zilei (pot fi negative pentru seara de dinainte); cheile fără date lipsesc.

| Cheie | Format | Ce e |
|---|---|---|
| `puls` | `[[m, min, medie, max], …]` | puls pe găleți de 5 min (m multiplu de 5), doar gălețile cu mostre |
| `spo2` | `[[m, pct], …]` | fiecare măsurătoare SpO2 a brățării; lipsește dacă permisiunea SpO2 nu e dată |
| `somn` | `[[m0, m1, s], …]` | fazele nopții descrise în `zile.*.somn`; `s`: `p` profund, `r` REM, `u` ușor, `t` treaz / în afara patului, `s` somn nespecificat (sesiune fără faze = un segment `s`) |
| `somnZi` | la fel | somnul de zi (toate sesiunile din `zile.*.somnZi`, în aceeași listă) |
| `pasi` | `[n, …]` | pași pe 30 min (48 de valori; 46/50 la schimbarea orei); suma = `pasi.total` |
| `antr` | `[[[m, bpm], …], …]` | aliniat 1:1 cu `antrenamente`: pulsul mediu pe minut în fiecare antrenament (`[]` fără puls) |

Mărime: ~2,6 KB base64 pe zi în cel mai rău caz (puls la fiecare minut), deci ~40 KB pentru 14 zile.

Versiunea (`0.N`) vine din numărul rulării GitHub Actions (`VERSION_NAME: 0.${run_number}`).
