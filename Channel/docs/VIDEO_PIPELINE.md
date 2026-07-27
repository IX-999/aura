# Aura Video Pipeline — Design

Turns a generated script (with inline production markers) into a finished narrated
video, using GCP AI services. Runs inside the existing Channel Spring Boot app.

## Approach

Documentary "stills + motion" style, chosen for cost:

| Stage | Service | Cost / 22-min video |
|---|---|---|
| Narration | Cloud Text-to-Speech, Chirp 3 HD voice | ~$0.80 (~27k chars @ $30/1M) |
| Visuals | Vertex AI Imagen (`imagen-3.0-generate-002`), 1 image per `[B-ROLL]` | ~$1.50 (50 imgs @ $0.03) |
| Assembly | ffmpeg (Ken Burns zoom/pan on stills, graphic cards, mux) | free, local |
| Storage | GCS `gs://{bucket}/runs/{runId}/` | pennies |

Full generative video (Veo) would be $500-900/video at $0.40-0.75/sec — reserved as a
phase-2 option (`video.veo.enabled`) for 2-3 hero shots only (cold open, [TWIST]).

## Data flow

```
script text (markers inline)
  └─ ScriptParser        → Storyboard (ordered Segments)
       └─ NarrationService → one WAV per segment (Chirp3 HD, LINEAR16 24kHz) + durations
       └─ VisualService    → one 16:9 PNG per BROLL segment (Imagen via google-genai SDK)
            └─ TimelineBuilder → RenderManifest (entry = visual + audio + exact durations/gaps)
                 └─ VideoRenderer → ffmpeg: per-segment clips → concat → mux → final.mp4
                      └─ MediaStore → upload script/manifest/final.mp4 to GCS
```

Async: `POST /api/videos/generate` returns a `runId` immediately;
`GET /api/videos/{runId}` reports stage (PARSING → NARRATING → GENERATING_IMAGES →
RENDERING → UPLOADING → DONE | ERROR) and artifact paths.
Local work dir: `{video.work-dir}/{runId}/` (audio/, images/, clips/, final/).

## Parsing contract (DefaultScriptParser)

The storyteller agent now outputs **structured JSON** (see StoryTellerAgent OUTPUT FORMAT):
`{topic, title, sections:[{name, segments:[{type: "broll"|"graphic", visual, narration, pause, twist}]}]}`.
The parser tries JSON first: if the input (after trimming, and stripping a ```json fence if
present) starts with `{` and parses, map it straight onto Segments (flatten sections in
order; section name from the enclosing section; skip segments with blank narration/visual).

**Fallback — legacy marker format** (old saved scripts, or when the model slips):
Markers, each on its own line (inline `[TWIST]`/`[PAUSE]` also occur mid-paragraph):
`[SECTION: name]`, `[B-ROLL: prompt]`, `[GRAPHIC: text]`, `[TWIST]`, `[PAUSE]`.

- A new `[B-ROLL]` or `[GRAPHIC]` marker starts a new Segment; narration lines accumulate
  into the current segment. Consecutive visual markers with no narration between them
  collapse to the LAST one.
- ALL bracketed markers are stripped from narration text (never sent to TTS).
- `[PAUSE]` sets `pauseAfter` on the segment containing/preceding it (split the segment
  if the pause is mid-narration). `[TWIST]` sets `twist`.
- Narration before any visual marker gets visual prompt: `"Cinematic establishing shot for a documentary about {topic}"`.
- Segments with blank narration are dropped.

## Narration (TtsNarrationService)

`google-cloud-texttospeech` client, ADC auth. Per segment: plain-text synthesize
(no SSML — Chirp3 doesn't support it), voice from `video.tts.voice`, LINEAR16 @ 24000 Hz
→ `audio/seg-%03d.wav`. Duration read from the WAV data-chunk size: `dataBytes / (2 * 24000.0)`.
Requires `texttospeech.googleapis.com` enabled on the project.

## Visuals (CompositeVisualService)

Image source is configurable via `video.image.source`:
- `imagen` — every BROLL image AI-generated (Vertex AI Imagen).
- `wikimedia` — search Wikimedia Commons (free API, no key, reusable licenses) for a real
  photo matching the prompt; fall back to Imagen when no usable hit.
- `hybrid` (default) — Wikimedia first (real photos suit true-crime/history topics and
  cost $0), Imagen fallback. We deliberately do NOT scrape arbitrary websites: images in a
  monetized video must be license-safe, which Commons guarantees and Imagen sidesteps.

Wikimedia lookup: `https://commons.wikimedia.org/w/api.php?action=query&generator=search&gsrsearch=filetype:bitmap {prompt}&gsrnamespace=6&prop=imageinfo&iiprop=url|size|extmetadata&format=json`
— take the first result at least 1280px wide, download, letterbox/crop to 16:9 in the renderer.

**Image generation: Gemini (`gemini-2.5-flash-image`) via `generateContent`, NOT Imagen.**
This project's Vertex AI project has no Imagen publisher model access — `imagen-4.0-generate-001`,
`imagen-3.0-generate-002`, and `imagegeneration@006` all return 404 on `predict` in `us-central1`
(verified live). `gemini-2.5-flash-image` works in the same project/region via `generateContent`
and returns inline image bytes, at roughly $0.04/image. Reuse the `com.google.genai` SDK already
on the classpath (ADK dependency): `Client.builder().vertexAI(true).project(...).location(...)`
then `client.models.generateContent(model, "Generate a single 16:9 widescreen image. " + prompt
+ ", " + styleSuffix, config)` where `config` sets `responseModalities("TEXT", "IMAGE")` and
`imageConfig(ImageConfig.builder().aspectRatio("16:9").build())`. Extract the first
`candidates[].content().parts()` entry with `inlineData()` present and write its bytes to
`images/seg-%03d.png`. GRAPHIC segments get NO image — they render as text cards in ffmpeg
(image models render text poorly). On a per-image failure (safety filter, no image part, etc.),
log and fall back to the nearest previous successful image; never fail the run.

## Timeline (DefaultTimelineBuilder)

Per segment entry: `durationSeconds = audio duration`, `gapAfterSeconds = 0.7 if pauseAfter else 0.25`.
Video clip length for a segment = duration + gap (frozen last frame / continued zoom during gap).
Audio track = segment WAVs separated by silence of exactly `gapAfterSeconds`.
This shared arithmetic is what keeps A/V in sync — both sides derive from the manifest only.
Manifest serialized to `manifest.json` (Jackson).

## Render (FfmpegVideoRenderer)

1. Narration track: generate silence WAVs (`anullsrc`, 24kHz mono s16), concat demuxer → `narration.wav`.
2. Per entry: BROLL → `-loop 1` on the PNG, slow zoompan (alternate zoom-in/zoom-out per entry),
   `scale` to 1920x1080, `fps=30`, `yuv420p`, libx264, `-t {duration+gap}` → `clips/seg-%03d.mp4`.
   GRAPHIC → dark solid background + `drawtext` using `textfile=` (write the text to a file —
   avoids quoting/escaping bugs), wrapped, centered, same codec params.
3. Concat demuxer over clips (identical codec params → stream-copy concat) → `video.mp4`.
4. Mux: `-i video.mp4 -i narration.wav -c:v copy -c:a aac -b:a 192k -shortest final/final.mp4`.
   If `video.music.path` set: `amix` music at -18dB under narration.

ffmpeg binary from `video.ffmpeg.path` (default `ffmpeg` on PATH). Fail fast with a clear
message if not found.

## Storage (GcsMediaStore)

Bucket `video.gcs.bucket` in the app's GCP project; lazily create (US multi-region) if
missing. Upload `script.md`, `storyboard.json`, `manifest.json`, `final.mp4` under
`runs/{runId}/`. Also backs `StoryTellerTools.saveScript` (replaces the TODO stub).

## Publish (thumbnail + YouTube)

After the GCS upload, two more artifacts are produced:

1. **Thumbnail (`ThumbnailService`)** — always runs (no config flag). Picks the image of
   the `[TWIST]` segment if one exists, else the first available image; ffmpeg
   crop/scales it to exactly 1280x720 (cover crop), draws a semi-transparent scrim box
   across the bottom, then overlays the video title bottom-left (bold Arial, white,
   `textfile=` + box/shadow for contrast, wrapped to ~20 chars/line, max 3 lines,
   fontsize 72). Written to `final/thumbnail.jpg` at `-q:v 2`; if that exceeds YouTube's
   2MB thumbnail limit it's re-encoded at `-q:v 5`. Included in the GCS artifact set and
   as `job.artifact("thumbnail", ...)`.

2. **YouTube upload (`YouTubeUploader`)** — gated by `youtube.upload.enabled` (default
   `false`). When on, a new `UPLOADING_YOUTUBE` stage runs after the GCS upload: the
   named video + thumbnail are uploaded via the YouTube Data API v3 (resumable upload,
   category "Education", `selfDeclaredMadeForKids=false`), with description built from
   the first B-ROLL segment's narration plus the optional `description-suffix`. The
   thumbnail is set in a second call wrapped in its own try/catch — unverified API
   projects/channels can't set custom thumbnails, so that failure is recorded as
   `thumbnailError` rather than failing the run. Any upload failure is caught, logged,
   and recorded as `youtubeError` — **a publish failure must never lose an otherwise
   finished video**; the job still reaches `DONE`.

   **Auth**: OAuth 2.0 installed-app flow (`GoogleAuthorizationCodeFlow` +
   `AuthorizationCodeInstalledApp` + `LocalServerReceiver` on port 8888), scope
   `https://www.googleapis.com/auth/youtube.upload`. The *first* upload opens a browser
   for one-time consent; the refresh token is then cached under `youtube.upload.tokens-dir`
   (default `.yt-tokens/`, gitignored) so every later upload is silent.

   **Getting `client_secret.json`**: Google Cloud Console → APIs & Services → enable
   "YouTube Data API v3" → Credentials → Create Credentials → OAuth client ID → Application
   type **Desktop app** → download the JSON, save it at the path in
   `youtube.upload.client-secrets-path` (default `client_secret.json` in the working
   directory). This file is gitignored — never commit it.

   **Why private by default**: `youtube.upload.privacy-status=private` so every publish
   is reviewable before anyone else can see it; flip to `unlisted`/`public` per-run once
   you're happy with a video, or change the default once the pipeline is trusted.

## Config (application.properties, prefix `video.`)

work-dir, gcs.bucket, gcs.enabled, tts.voice (en-US-Chirp3-HD-Charon), tts.speaking-rate,
image.model, image.style-suffix, ffmpeg.path, render.width/height/fps,
render.inter-segment-gap, render.pause-gap, music.path (optional).

## Build

Temurin JDK 17 (`C:\Users\zyang\AppData\Local\Programs\Eclipse Adoptium\jdk-17.0.19+10`),
Maven at `C:\Users\zyang\tools\apache-maven-3.9.9`. `mvn -DskipTests clean package`.
