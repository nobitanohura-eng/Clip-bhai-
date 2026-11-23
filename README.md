# ClipAI v2 — Auto Video Clipper (Native Kotlin, Fully On-Device)

ClipAI v2 turns a long video into short, edited, vertical clips — **fully
automatically, on the phone, with no server upload.** Pick a video, tap
"Auto Clip", and the app transcribes it, finds the best moments, crops to
9:16, burns in karaoke captions and a hook overlay, and exports share-ready
clips.

> **Honest status box — read this first**
>
> - **No APK has been built yet.** Flutter / Dart / Java are NOT installed in
>   this workspace, so no build and no test run has happened here. The first
>   real verification is the first green GitHub Actions run (see
>   [Build the APK from your phone](#build-the-apk-from-your-phone)).
> - **Timings below are estimates from published benchmarks, NOT measured**
>   on a real phone. Measure them on your own phone before promising a time
>   in the app or to anyone else.
> - **Gemma 4 `.litertlm` is gated / host-your-own.** The model file is
>   Apache 2.0, but the Hugging Face download still asks you to accept terms
>   (see [Models](#models)). The app works without any LLM — rule-based
>   scoring is the default path.
> - **Library versions are marked `TODO-verify`** in `app/build.gradle.kts`.
>   They must be confirmed on the first CI run; do not assume they resolve.
> - **Fixes applied 2026-11-23 (before first CI run):** the LiteRT-LM
>   coordinate was wrong (`litertlm:0.1.0` does not exist) and is now the
>   verified `litertlm-android:0.18.0`; the missing `app/proguard-rules.pro`
>   referenced by the release build type was added; the workflow now pins
>   Gradle 8.9 (AGP 8.5.2 needs 8.7+; the repo has no wrapper). Media3
>   1.11.1 was also confirmed as the latest stable on Google Maven.
>   These remove three known first-run failures, but the build is still
>   unverified until GitHub Actions goes green.
> - The **first Gemma run is slow** (model load + warm-up). Later runs reuse
>   the loaded model. Budget for this in the UI — show "loading model",
>   never a fake progress bar.

## What changed in v2 vs v1 (Flutter)

v1 (`~/workspace/clipai-android/`, now frozen/legacy) was a Flutter app.
v2 is a **native Kotlin** rewrite. The research verdict (2026-11-23) is:
**native Kotlin first; Flutter only as a UI shell** — every heavy piece
(Sherpa-ONNX, LiteRT-LM, MediaPipe Tasks, Media3 Transformer) is a native
Kotlin API with no mature Dart equivalent, and doing inference/export in
Dart would mean a platform channel for each piece plus a second runtime to
debug.

| Area | v1 (Flutter, legacy) | v2 (native Kotlin) |
|---|---|---|
| App stack | Flutter + Dart, platform channels | Native Kotlin + Jetpack Compose, MVVM/Clean |
| Speech (ASR) | Whisper (generic) | **Whisper Tiny via Sherpa-ONNX (INT8)**, Base opt-in, Moonshine fallback |
| LLM (hooks / titles / scores) | Gemma 2 2B via MediaPipe LLM Inference | **Gemma 4 E2B INT4 via LiteRT-LM** (Gemma 3 1B low-RAM fallback). MediaPipe LLM Inference is **NOT used** — it is maintenance-only; Google directs new projects to LiteRT-LM. Gemma 2 2B is legacy. |
| Smart crop | Basic centre crop | **BlazeFace** face tracking (5 fps, 480p proxy), smoothed, cut-aware, manual override |
| Export | FFmpeg | **Media3 Transformer (hardware MediaCodec) primary**; FFmpeg only as fallback for ASS burn-in / loudnorm two-pass |
| Models | Download, size check only | **Model Manager**: manifest, SHA-256 verify, resumable download (HTTP Range + `.part`), update, delete, per-model RAM gating |
| Background work | Foreground service | Foreground `mediaProcessing` service + WorkManager downloads, checkpoint after every stage, cancel + resume |
| Quality gate | None | **QA stage**: probe output (duration / resolution / bitrate), thumbnail check, loudness check before declaring success |

## How it works (pipeline)

```
Pick video (SAF / MediaStore, never copied wholesale)
  → Probe (duration, fps, rotation, codec)
  → Extract audio 16 kHz mono → VAD / silence detection (Silero VAD)
  → Transcribe in ≤30 s windows → words [{text, start, end, confidence}]
       (cached per video hash — re-clipping the same video skips ASR)
  → Sentence / segment building (snap to pauses, never cut mid-sentence)
  → Scene-cut detection (proxy frames) — never cut across a cut
  → Candidate windows (30–60 s, step 10–15 s, snapped to sentences)
  → Rule-based scoring (Hook 35% / Flow 25% / Value 25% / Audio-visual 15%)
  → LLM re-rank of the TOP ~10 candidates only (never every window)
  → Titles + hook line (verbatim quote or clearly-marked title)
  → Face-track crop plan per clip (BlazeFace @ 5 fps, smoothed)
  → Render (Media3, single pass) → QA → Export / share
```

**Scoring (local, deterministic, explainable):** hook words in the first 3 s
(question / bold claim / number / "how to" / contrast), completeness
(starts/ends at a sentence + pause boundary), info density (content words
per second, no filler repetition), energy (RMS / pitch variance vs video
mean), emotion words, quotability, face-visible fraction, silence fraction
(penalised). Each clip shows its score breakdown — a score you can inspect
is a score you can trust. The LLM only re-ranks and writes titles; if it
fails, times out, or the phone is too small for it, the app falls back to
the rule-based score + template title. **Trend scoring is not computed
offline** — it needs live platform data, so v2 does not pretend to score it.

**Window selection:** candidates are scored, sorted best-first, then
**greedy dedup** — keep the best clip, discard any candidate overlapping it
or within 30 s of it, repeat until you have the requested count (default 5).

**Captions:** Whisper word timestamps → 2–3-word lines, max ~42 chars →
karaoke word-by-word highlight (active word yellow, rest white, black
outline, bold), burned in with Media3 `OverlayEffect` + pre-rendered
caption bitmaps. Font ~4.2% of video height, 12% safe margin from the
bottom (platform UI overlap). Devanagari-safe wrapping: never split a word
or a consonant cluster / matra. Low-confidence words are flagged
(`✓ High / ⚠ Review / ⚠ Uncertain`, `___` instead of a hallucinated guess)
and manual edits are locked in.

**Hook overlay:** big bold text, top third, shown for the first 3 s —
a verbatim quote from the speaker, or an LLM title clearly marked as a
title. Never a fabricated quote.

**Export (one preset that survives Shorts / Reels / TikTok):**
1080×1920, 9:16 (720×1280 "fast" preset), frame rate matched to source,
H.264 High Profile MP4 with fast-start, 8–12 Mbps VBR, AAC-LC 48 kHz
stereo 192–320 kbps, loudness-normalised to **-14 LUFS, TP -1 dBTP**.
Clips 30–60 s, hard cap 3 min.

## Architecture

```
┌─ UI (Jetpack Compose, Material 3, single Activity, Navigation) ─┐
│ Home / Projects · Clip list (score breakdown) · Editor (preview │
│ + caption timeline + crop override) · Model Manager · Settings  │
├─ ViewModel + StateFlow (UI state only; no heavy work here) ─────┤
├─ Domain (pure Kotlin, unit-tested) ─────────────────────────────┤
│ ScoringEngine · WindowSelector (greedy dedup) · TranscriptRepo  │
│ CaptionEngine (words → ASS / bitmap plan) · CropPlanner         │
│ (BlazeFace track → smoothed crop keyframes) · Loudness          │
├─ Data ──────────────────────────────────────────────────────────┤
│ Room (projects, clips, words, models, jobs) · DataStore (prefs) │
│ ModelManager (manifest, download, verify, install)              │
├─ Workers / Services ────────────────────────────────────────────┤
│ ModelDownloadWorker (WorkManager, Range + SHA-256)              │
│ ClipPipelineService (foreground, mediaProcessing, one job at a   │
│   time, checkpoint after each stage, cancellation)               │
├─ Native / ML ───────────────────────────────────────────────────┤
│ Sherpa-ONNX AAR (Whisper Tiny/Base, Silero VAD, diarization)    │
│ LiteRT-LM (Gemma 4 E2B / 3n E2B, Kotlin API, GPU → CPU fallback) │
│ MediaPipe tasks-vision (Face Detector / BlazeFace)               │
│ Media3: ExoPlayer (preview) + Transformer (export, OverlayEffect│
│   caption bitmaps, Presentation/crop, Composition)               │
│ MediaCodec / MediaMuxer / MediaMetadataRetriever (probe)         │
└─────────────────────────────────────────────────────────────────┘
```

Key rules: models are loaded **one at a time and released after their
stage** (never Whisper + Gemma in RAM together — they do not fit on a 6 GB
phone). No model is ever loaded on the main thread. Work is chunked and
yields between stages; heavy stages ask for charging / >30% battery.
Progress is per-stage and honest (transcribing 42%, scoring, exporting
clip 2/5) — never a fake linear bar. A cancelled job keeps the clips that
already finished.

## Models

Models are **never bundled in the APK** (a 2 GB model blows past the Play
150 MB limit). They download on first launch / on demand into app-private
storage (`filesDir/models/<model-id>/<version>/`), verified by SHA-256
before first use, resumable via HTTP `Range` + `.part` file, one download
at a time, Wi-Fi-only by default for files over 100 MB (user-overridable),
charging required for the LLM download.

| Model | Size | RAM / speed | Role |
|---|---|---|---|
| Whisper Tiny (Sherpa-ONNX, INT8) | ~39–75 MB | ~150 MB; RTF 0.20–0.80 | **Default ASR, all devices.** Multilingual, word timestamps. |
| Whisper Base (Sherpa-ONNX, INT8) | ~74–142 MB | ~250 MB; RTF ~1.20 | **Opt-in, 8 GB+ / flagship.** Better accuracy; too slow as a mid-range default. |
| Moonshine Tiny / Base | ~26 MB / ~60 MB | <1 GB; ~5× less compute than Whisper Tiny at same WER | **Fast fallback**, English-first. Weaker multilingual than Whisper. |
| Gemma 4 E2B INT4 (`.litertlm`, LiteRT-LM) | **2.58 GB** | <1.5 GB active; ~47 tok/s decode (CPU) | **Default LLM.** Re-ranks top ~10 candidates, writes titles/hooks. Apache 2.0, but HF download is gated (accept terms) — host it yourself or paste your own HF token; see below. |
| Gemma 3 1B INT4 | ~0.6–1 GB | ~1–1.5 GB | **Low-RAM fallback (4–6 GB phones).** Titles / short hook rewrites only — weakest reasoning, not for ranking. |
| BlazeFace (MediaPipe Face Detector) | ~15 MB | Negligible | **Smart crop.** Face detection at 5 fps on a 480p proxy. |

Not shipped on phones: Whisper Small / Medium / Large (too big, too slow),
Gemma 2 2B (legacy — superseded by Gemma 3n/4), MediaPipe LLM Inference
(maintenance-only — do not build new work on it).

**Gated models (Gemma):** the Hugging Face repo asks for an account and a
terms click-through, and downloads need a read-access HF token. **Never
hard-code a token in a distributed app.** Practical options, in order:
(a) host the Gemma 4 `.litertlm` on your own CDN / Play Asset Delivery —
Apache 2.0 permits redistribution, so no per-user HF account is needed;
(b) in-app flow: open the HF model page, user accepts terms, user pastes
*their own* read token (stored in EncryptedSharedPreferences, never logged);
(c) use the app with no LLM at all — Whisper + rule-based scoring needs no
token and the app is fully useful without Gemma.

**Fallback ladder (designed, not an exception):** Gemma 4 E2B → Gemma 3 1B
→ no-LLM rule-based mode; Whisper Base → Tiny → Moonshine; GPU → CPU for
LiteRT-LM; if ASR fails entirely, manual clip selection + manual captions
still work. Every failure shows a human message, never a stack trace.

## Phone requirements

| | Recommended | Minimum |
|---|---|---|
| RAM | 6 GB+ (8 GB+ for Gemma 4 + Base) | 4 GB — **Tiny-only, no LLM, 720p export** |
| CPU | arm64 | arm64 (no 32-bit support) |
| Android | 10+ (API 29) | API 29 |
| Free storage | 5–8 GB | 3 GB — the app refuses to start a job below ~3 GB free, with a clear message |
| Network | Wi-Fi for model downloads | Offline after models are downloaded |

Storage for a 10-min 1080p source: source ~1–2 GB (your file, not copied)
+ WAV ~115 MB + transcript <1 MB + models (Tiny ~40 MB + BlazeFace ~15 MB
+ Gemma 4 E2B 2.58 GB) + 5 exported clips ~150–300 MB + temp render files.
The WAV and temp files are deleted after export; the transcript cache is
kept (keyed by file hash) so re-clipping is instant.

## Timings (estimates, NOT measured)

For a **10-minute video**, assembled from the separate ASR / LLM / export
benchmarks in the research report — no source publishes an end-to-end
10-min phone pipeline time, so **measure this on 3 real devices before
promising a time in the UI.**

| Stage | Flagship (8–12 GB) | Mid-range (6 GB) |
|---|---|---|
| Audio extract + probe + VAD | <30 s | <1 min |
| Transcription — Whisper Tiny | 2–5 min | 5–8 min |
| Transcription — Whisper Base | ~10 min | ~12 min (don't default to Base here) |
| Face tracking (BlazeFace @ 5 fps) | <1 min | 1–2 min |
| Rule-based scoring + windowing | seconds | seconds |
| LLM re-rank (Gemma 4 E2B, top 10) | ~30 s (GPU) – 2 min (CPU) | 2–5 min CPU; skipped if <6 GB RAM |
| Export — 5 clips × ~45 s, Media3 | 1–3 min | 3–6 min |
| **Total (Tiny + rule scorer + LLM + export)** | **~5–10 min** | **~15–30 min** |

Sustained CPU + hardware encode heats the phone and thermal throttling can
silently make everything 2–3× slower. The app processes one clip at a
time, never ASR + LLM + encode in parallel.

## Limits — what v2 does NOT do (honest list)

These stay cloud / later. v2 does not fake them:

- **Trend scoring** — needs live platform data.
- **True multimodal moment detection** (visual + audio + sentiment
  reasoning like OpusClip ClipAnything) — vision LLMs are minutes per image
  on a phone CPU.
- **YouTube-URL ingest** — yt-dlp + YouTube ToS; do not ship it in a Play
  app. Local files / screen recordings only.
- **Auto-posting / scheduling to TikTok / Reels / Shorts** — official APIs,
  OAuth, server-side.
- **B-roll insertion** — no local stock library and no vision model fast
  enough to pick relevant B-roll on-phone. (Blurred-background fill,
  punch-in zoom, and user-picked B-roll from their own gallery are the
  local alternatives.)
- **Cloud quality** — Whisper Large / 8B+ LLM quality is not reachable
  locally. Expect good, not cloud-Opus-Clip, results.
- High-quality TTS dubbing / voice cloning, speaker diarization (later),
  iOS port (only after Android is stable).

## Build the APK from your phone

You have no PC — only a phone. **GitHub Actions is the build machine.**
You do not need to install Android Studio, Java, or Gradle on your phone.
Exact steps, all from the phone:

1. **Get the code onto GitHub (first time only).**
   Open the GitHub app or github.com in your phone browser, create a new
   repository (for example `clipai-v2`), and upload / push this project
   folder into it (GitHub website → repository → "Add file" → "Upload
   files" works for small uploads; for the whole project, use a git client
   app on your phone, or upload a zip and unzip via the website).
   Make sure the repo contains `.github/workflows/build-apk.yml`.
2. **Open the repository** in the GitHub app / website.
3. Tap the **Actions** tab. You will see the **"Build APK"** workflow.
   - If you just pushed to `main` (or `master`), it starts automatically.
   - Or tap the workflow → **"Run workflow"** → **"Run workflow"** to
     start it by hand.
4. **Wait for the run.** Tap the running job to watch the steps
   (tests → build APK). A green tick means success; a red cross means a
   step failed — tap the failed step to read the log.
5. **Download the APK.** When the run is green, scroll to the bottom of
   the run page → **Artifacts** → tap **`app-release-apk`** to download
   the zip. Unzip it on your phone (Files app) — inside is
   `app-release.apk`.
6. **Install it.** Open the APK file. Android will ask you to allow
   installing from this source ("Allow from this source" / allow unknown
   apps for your browser or Files app) — allow it once, then tap
   **Install**. The app installs like any other app.
7. **First launch:** the app asks you to download the AI models
   (Whisper Tiny first, ~40 MB). Do this on Wi-Fi. Gemma 4 E2B (2.58 GB)
   is optional and downloads only if you turn it on.

Notes:

- The APK from this workflow is signed with the **default debug key** —
  fine for personal use, not a Play Store release. For a properly signed
  release, add the four secrets (`KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`,
  `KEY_ALIAS`, `KEY_PASSWORD`) in repo Settings → Secrets and variables →
  Actions, and enable the commented signing step in
  `.github/workflows/build-apk.yml`.
- Artifacts are kept for **30 days**. Download the APK before it expires,
  or re-run the workflow.
- If the build fails, the **test-reports** artifact (uploaded on failure)
  contains the unit-test reports — check it before re-running.

### Codemagic (alternative)

Codemagic is a phone-friendly alternative CI: create an account at
codemagic.io, connect the same GitHub repo, pick the Android workflow, and
it builds the APK in the cloud — no PC, no local setup. It needs an
account + repo connection (and its free tier has build-minute limits).
GitHub Actions is the default path for this project because it needs no
extra account beyond GitHub; use Codemagic if GitHub Actions is ever
blocked or you prefer its UI.

## Tests

- **Unit tests** (pure Kotlin, JUnit, no Android — they run in CI):
  `app/src/test/java/com/clipai/v2/domain/` —
  `ScoringEngineTest`, `WindowSelectorTest`, `CaptionEngineTest`.
  Run locally (on a PC) with `./gradlew :app:testDebugUnitTest`.
  They cover: scoring weights sum to 1.0, hook scoring, greedy dedup with
  the 30 s rule, no overlapping windows, caption lines ≤3 words and
  ≤42 chars, Devanagari-safe wrapping (never splits a word), hook ≤10 words.
- **Manual QA:** see [`docs/QA_CHECKLIST.md`](docs/QA_CHECKLIST.md) —
  device matrix, HDR/4K/60 fps, Hindi + English + Hinglish, rotation/VFR,
  low-RAM 4 GB path, cancel/resume, background kill, airplane mode,
  storage-full, corrupt model file, export checks.

## Project status

- v2.0.0-alpha. Native Kotlin scaffold + domain tests + CI workflow exist.
  Kotlin source / UI / domain implementation is in progress (other owners).
- v1 Flutter project is frozen/legacy — see the note at the top of
  `~/workspace/clipai-android/README.md`.
- Research basis: `~/workspace/research_notes/clipai-next-level-research-20261123-1019/report.md`
  (§5 feasibility/limits, §7 feature list + architecture).
