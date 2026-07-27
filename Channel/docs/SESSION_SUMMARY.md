# Session summary — 2026-07-24

Goal: take the working script generator and add AI voiceover + visuals on GCP to
produce finished videos. Designed in the main session, implemented by a Sonnet
subagent, verified live against real endpoints (no unit tests, per preference).

## What was built

A **script → narrated slideshow video** pipeline inside the existing Channel Spring
Boot app. Full design: [VIDEO_PIPELINE.md](VIDEO_PIPELINE.md).

### Approach and cost

Documentary "stills + motion" rather than generative video:

| Stage | Service | Cost / video |
|---|---|---|
| Narration | Cloud TTS, Chirp 3 HD (`en-US-Chirp3-HD-Puck`) | ~$0.30–0.80 |
| Visuals | Wikimedia Commons (free) → `gemini-2.5-flash-image` fallback | ~$0.04/image |
| Assembly | ffmpeg (Ken Burns zoom, text cards, mux) | free, local |
| Storage | GCS `gs://aura-channel-media-vivid-now-390717/runs/{runId}/` | pennies |

**~$0.60–3 per video.** Veo (`veo-3.x`) was rejected: at $0.40–0.75/sec, a 22-minute
video is $500–900. Kept as a possible future add-on for 2–3 hero shots only.

### New endpoints

- `POST /api/videos/generate` — body `{topic, script}`, returns `runId` immediately.
- `GET /api/videos/{runId}` — stage + artifact locations.

Stages: `PARSING → NARRATING → GENERATING_IMAGES → RENDERING → UPLOADING → DONE|ERROR`.
Work dir `Channel/runs/{runId}/` (audio/, images/, clips/, logs/, final/).

### New code (`com.agent.video`)

Interfaces + models, and implementations under `video.impl`:

- `DefaultScriptParser` — JSON-first (the storyteller agent's structured output),
  falls back to the legacy `[B-ROLL:]`/`[SECTION:]` marker format.
- `TtsNarrationService` — one WAV per segment, LINEAR16 24 kHz; duration read from
  the WAV header. Long narration split at sentence boundaries.
- `CompositeVisualService` (`@Primary`) — routes per `video.image.source`
  (`imagen` | `wikimedia` | `hybrid`); per-image failure falls back to the previous
  image rather than failing the run. Uses `WikimediaSearch` + `ImagenVisualService`.
- `DefaultTimelineBuilder` — aligns audio durations with visuals into a
  `RenderManifest`; both the video clips and the audio track derive from it, which is
  what keeps A/V in sync.
- `FfmpegVideoRenderer` — per-segment clips (zoompan / drawtext cards), concat, mux.
- `GcsMediaStore` — uploads run artifacts, creates the bucket if missing.
- `VideoPipelineService` / `VideoController` / `VideoJob` — async orchestration,
  single-threaded executor (one render at a time).

### Changes to existing code

1. **`StoryTellerAgent` now emits structured JSON** instead of a marker-annotated
   blob, so downstream stages consume it mechanically:
   `{topic, title, sections:[{name, segments:[{type, visual, narration, pause, twist}]}]}`.
2. **`StoryTellerController` doubled-script bug fixed** (previously open issue #1).
   It concatenated every text part the agent emitted, so draft + revision both landed
   in `script`. Now it captures the exact `scriptText` passed to `saveScript`, with the
   last text block as fallback; response includes `scriptSource` (`saveScript`|`finalText`).
   Verified: last run reported `saveScript`, 1,906 words counted once.
3. **`StoryTellerTools.saveScript` implemented** (was a TODO) — writes to
   `gs://{bucket}/scripts/{timestamp}-{slug}.md` plus a local copy; never throws.
4. **`ExemplarLibrary`** (new) — few-shot style conditioning, see below.
5. `pom.xml`: added `google-cloud-texttospeech`. `ChannelApplication`:
   `@ConfigurationPropertiesScan`. `.gitignore`: `Channel/runs/`, `Channel/scripts/`.

### Style exemplars

Drop `.txt`/`.md` files in `Channel/exemplars/` → loaded at startup and appended to the
agent's instruction as style examples (voice, pacing, hooks, structure only — never
subject matter or facts; research still drives all factual claims). Limits: 3 files,
8,000 chars each. Each file is screened by the same deterministic `ContentPolicy` the
agent obeys; a BLOCK verdict skips it with a log warning.

## Environment fixes required along the way

Each of these was a real failure hit during live testing, not a precaution:

1. **ADC quota project** — Cloud TTS returned `PERMISSION_DENIED` because user-credential
   ADC has no quota project. Added `quota_project_id: vivid-now-390717` to
   `%APPDATA%\gcloud\application_default_credentials.json` (backup at `.bak`).
2. **No Imagen access** — `imagen-4.0-generate-001`, `imagen-3.0-generate-002`, and
   `imagegeneration@006` all 404 on this project. Switched to `gemini-2.5-flash-image`
   via `generateContent` with `responseModalities:["TEXT","IMAGE"]` — verified working.
3. **ffmpeg installed** (`winget install Gyan.FFmpeg`); absolute path pinned in
   `application.properties`.
4. **ffmpeg drawtext escaping** — the Windows drive colon must be escaped (`C\:/...`)
   inside a filter graph even within single quotes; text cards failed until fixed.
5. **GCS project id** — `StorageOptions.getDefaultInstance()` threw
   "Required parameter project must be specified" with user ADC; now sets project explicitly.

## Videos produced

| Run | Title | Length | Segments |
|---|---|---|---|
| `20260724-211039-da013052` | smoke test (Eiffel Tower) | 13 s | 2 |
| `20260724-211625-389f4dda` | The Zodiac Killer: The Code That Took 51 Years to Break | 8 m 56 s | 19 |
| `20260724-214741-7a452f5e` | The Invisible Chains: A Life Lived in Debt's Shadow | 6 m 36 s | 20 |

All three rendered and uploaded to GCS successfully.

## Open issues

1. **Scripts run short.** ~1,900 words → 6–9 minutes, against the instruction's own
   3,500–4,500 word / 20–25 minute target. Model under-generates on long outputs.
   Options: raise output token limit, require a minimum segment count per section, or
   generate section-by-section across multiple turns.
2. **Graphic cards render the stage direction, not the text.** For `type: "graphic"`
   segments the agent writes a *description* ("Graphic displaying two paths: ...")
   into `visual`, and the renderer prints it verbatim. The JSON schema comment in the
   instruction describes `visual` as a cinematic description for all types — needs to
   say "for graphic: the literal on-screen text".
3. **Exemplars weren't active in the last run.** `transcript.txt` and `transcript2.txt`
   were added at 21:43–21:44; the running app started at 21:39. `ExemplarLibrary` loads
   only at startup — restart to pick them up, then check the log for per-file verdicts.
4. **`ContentPolicy` screening is literal keyword matching.** It matches fixed phrases
   ("glorify the murderer", "hire a hitman"). It will not detect a narrative that
   *enacts* violence without using those phrases, so it should not be relied on as the
   sole check on exemplar files — review what goes in `exemplars/` directly.
5. **Safety guidance thinned in the instruction rewrite.** The current
   `StoryTellerAgent` instruction replaced the detailed CONTENT POLICY and ACCURACY
   RULES sections with a brief "screen and proceed carefully" workflow. The
   deterministic `screenTopicSafety` gate still runs, but model-level guidance on
   victim respect, sourcing every claim from research, and not inventing statistics is
   now much weaker. Note the style also invites invented micro-detail
   ("$173.48 balance") — fine for hypothetical POV scenarios, fabrication in factual
   pieces. Recommend folding a condensed version back in.
6. **Niche is now a hint, not a constraint.** Asked for "true crime", the agent chose
   "The Cost of Extreme Debt" — the rewritten instruction tells it to pick for
   emotional stakes over niche fidelity.
7. **Secrets still hardcoded** as `${VAR:default}` fallbacks in the git-tracked
   `application.properties` (YouTube key, TikTok key/secret). Pre-existing; the YouTube
   key was also pasted into a chat and should be rotated.
8. **`Channel/target/` is tracked in git** — build output shows up in every diff.
   Worth adding to `.gitignore` and `git rm -r --cached`.

## Build / run

```
$env:JAVA_HOME="C:\Users\zyang\AppData\Local\Programs\Eclipse Adoptium\jdk-17.0.19+10"
& "C:\Users\zyang\tools\apache-maven-3.9.9\bin\mvn.cmd" -DskipTests clean package
& "$env:JAVA_HOME\bin\java.exe" -jar target\demo-0.0.1-SNAPSHOT.jar
```

Full workflow: `POST /api/scripts/generate` `{"niche":"..."}` → take `.script` from the
response → `POST /api/videos/generate` `{topic, script}` → poll `GET /api/videos/{runId}`.
