# AI YouTube Channel Agent — Architecture & Implementation Guide

## Vision

A fully autonomous AI agent that researches trending topics, writes scripts, generates voiceover audio, assembles video with visuals, and uploads finished content to YouTube on a configurable schedule — all running on GCP.

---

## High-Level Architecture

```
┌─────────────────────────────────────────────────────────────────────┐
│                        Cloud Scheduler (cron)                       │
│                    "Every day at 6 AM UTC"                          │
└──────────────────────────┬──────────────────────────────────────────┘
                           │ triggers
                           ▼
┌─────────────────────────────────────────────────────────────────────┐
│                     Cloud Run — Orchestrator                        │
│  Spring Boot (or Python FastAPI) service                            │
│  Pulls config from Firestore, kicks off the pipeline                │
└──────┬──────────┬──────────┬──────────┬──────────┬──────────────────┘
       │          │          │          │          │
       ▼          ▼          ▼          ▼          ▼
   ┌────────┐ ┌────────┐ ┌────────┐ ┌────────┐ ┌────────────┐
   │ Step 1 │ │ Step 2 │ │ Step 3 │ │ Step 4 │ │  Step 5    │
   │Research│ │ Script │ │ Audio  │ │ Video  │ │  Upload    │
   │& Topic │ │  Gen   │ │  Gen   │ │Assembly│ │ & Publish  │
   └───┬────┘ └───┬────┘ └───┬────┘ └───┬────┘ └─────┬──────┘
       │          │          │          │              │
       ▼          ▼          ▼          ▼              ▼
   Google     Vertex AI   Cloud TTS   Cloud Run    YouTube
   Search     (Gemini)    or ElevenL  (FFmpeg)     Data API v3
   + Vertex AI             abs API    + GCS
```

---

## Pipeline Stages — Detail

### Stage 1: Topic Research & Selection

**Goal:** Pick a trending, high-potential topic the channel hasn't covered yet.

| Component | Service | Notes |
|---|---|---|
| Trend discovery | Google Trends API, YouTube Data API (trending), Reddit API | Pull trending queries in the channel's niche |
| Deduplication | Firestore `published_topics` collection | Skip topics already covered |
| Topic scoring | Vertex AI (Gemini 1.5 Pro) | Prompt: score each candidate on search volume, novelty, audience fit |
| Output | JSON `{ topic, angle, target_keywords[], estimated_length }` stored in GCS |

### Stage 2: Script Generation

**Goal:** Produce a timestamped script with intro hook, sections, and CTA.

| Component | Service | Notes |
|---|---|---|
| Research grounding | Vertex AI Search (grounding with Google Search) | Grounds the LLM on fresh web results |
| Script writing | Vertex AI (Gemini 1.5 Pro) | System prompt defines channel voice, structure, length target |
| Fact-check pass | Second Gemini call with grounding | Flags unsupported claims |
| Output | Markdown script + section-level SSML hints → GCS |

### Stage 3: Audio / Voiceover

**Goal:** Convert script to natural-sounding narration.

| Option A — GCP Native | Option B — Third Party |
|---|---|
| Cloud Text-to-Speech (WaveNet / Studio voices) | ElevenLabs API (more natural, costs more) |
| Free tier: 1M chars/month WaveNet | Pay-per-character |
| SSML support for pacing, emphasis | Voice cloning for brand consistency |

Output: WAV/MP3 per section → GCS.

### Stage 4: Video Assembly

**Goal:** Combine audio with visuals into a rendered MP4.

| Component | Service | Notes |
|---|---|---|
| Visual assets | Vertex AI Imagen (image gen), Pexels/Pixabay API (stock), Google Image Search | Per-section b-roll or generated illustrations |
| Subtitles | Chirp (Speech-to-Text) on the generated audio, or pass script directly | SRT file generation |
| Rendering | Cloud Run Job (high-CPU/GPU) running FFmpeg + MoviePy | Combines audio tracks, images, transitions, subtitles, intro/outro bumper |
| Thumbnail | Vertex AI Imagen or Pillow script | Bold text overlay on a generated background |
| Output | `video.mp4` + `thumbnail.png` → GCS |

### Stage 5: Upload & Publish

**Goal:** Upload to YouTube with optimized metadata.

| Component | Service | Notes |
|---|---|---|
| Metadata gen | Vertex AI (Gemini) | Generate title, description, tags, optimized for CTR |
| Upload | YouTube Data API v3 (resumable upload) | OAuth 2.0 service account or refresh token |
| Scheduling | Set `publishAt` for optimal time | Use channel analytics to pick best publish window |
| Logging | Firestore `uploads` collection | Track video ID, topic, performance metrics |

---

## GCP Services Map

```
┌─────────────────────────────────────────────────────┐
│                   GCP Project                       │
│                                                     │
│  Compute          Storage          AI/ML            │
│  ─────────        ────────         ──────           │
│  Cloud Run        Cloud Storage    Vertex AI        │
│  Cloud Run Jobs   Firestore        (Gemini 1.5 Pro) │
│  Cloud Scheduler  Secret Manager   Imagen           │
│                                    Cloud TTS        │
│  Networking       Ops              Cloud STT        │
│  ─────────        ────                              │
│  VPC Connector    Cloud Logging                     │
│  (if needed)      Cloud Monitoring                  │
│                   Error Reporting                   │
└─────────────────────────────────────────────────────┘
```

---

## Data Model (Firestore)

### Collection: `channels`
```json
{
  "id": "tech-explained",
  "name": "Tech Explained AI",
  "niche": "technology explainers",
  "voice_config": { "provider": "cloud-tts", "voice": "en-US-Studio-M" },
  "schedule_cron": "0 6 * * *",
  "youtube_channel_id": "UC...",
  "system_prompt": "You are a friendly tech educator...",
  "style_guide": { "intro_hook": true, "max_length_minutes": 10 }
}
```

### Collection: `pipeline_runs`
```json
{
  "id": "run_2026-07-22_abc",
  "channel_id": "tech-explained",
  "status": "completed",          // pending | researching | scripting | audio | video | uploading | completed | failed
  "topic": "How quantum error correction works",
  "created_at": "2026-07-22T06:00:00Z",
  "completed_at": "2026-07-22T06:47:00Z",
  "artifacts": {
    "script_gcs": "gs://yt-agent/runs/run_abc/script.md",
    "audio_gcs": "gs://yt-agent/runs/run_abc/narration.wav",
    "video_gcs": "gs://yt-agent/runs/run_abc/final.mp4",
    "thumbnail_gcs": "gs://yt-agent/runs/run_abc/thumb.png"
  },
  "youtube_video_id": "dQw4w9WgXcQ",
  "error": null
}
```

### Collection: `published_topics`
```json
{
  "topic_hash": "sha256_of_normalized_topic",
  "topic": "How quantum error correction works",
  "published_at": "2026-07-22T07:00:00Z",
  "video_id": "dQw4w9WgXcQ"
}
```

---

## Project Structure

```
ai-youtube-agent/
├── orchestrator/                  # Cloud Run service (main brain)
│   ├── src/main/java/com/agent/
│   │   ├── AgentApplication.java
│   │   ├── config/
│   │   │   ├── GcpConfig.java
│   │   │   ├── VertexAiConfig.java
│   │   │   └── YoutubeConfig.java
│   │   ├── controller/
│   │   │   └── PipelineController.java       # HTTP trigger endpoint
│   │   ├── model/
│   │   │   ├── PipelineRun.java
│   │   │   ├── ChannelConfig.java
│   │   │   └── TopicCandidate.java
│   │   ├── pipeline/
│   │   │   ├── PipelineOrchestrator.java      # Runs stages sequentially
│   │   │   ├── TopicResearchStage.java
│   │   │   ├── ScriptGenerationStage.java
│   │   │   ├── AudioGenerationStage.java
│   │   │   ├── VideoAssemblyStage.java
│   │   │   └── YoutubeUploadStage.java
│   │   └── service/
│   │       ├── VertexAiService.java           # Gemini API wrapper
│   │       ├── CloudTtsService.java           # Text-to-Speech
│   │       ├── GcsService.java                # Cloud Storage ops
│   │       ├── FirestoreService.java          # State management
│   │       └── YoutubeService.java            # YouTube Data API
│   ├── src/main/resources/
│   │   ├── application.yml
│   │   └── prompts/
│   │       ├── topic-scoring.txt
│   │       ├── script-writing.txt
│   │       ├── fact-check.txt
│   │       └── metadata-generation.txt
│   ├── Dockerfile
│   ├── pom.xml
│   └── cloudbuild.yaml
│
├── video-renderer/                # Cloud Run Job (heavy compute)
│   ├── render.py                  # FFmpeg + MoviePy assembly
│   ├── templates/
│   │   ├── intro.mp4
│   │   ├── outro.mp4
│   │   └── lower_third.png
│   ├── requirements.txt
│   ├── Dockerfile
│   └── cloudbuild.yaml
│
├── infra/                         # Terraform IaC
│   ├── main.tf
│   ├── variables.tf
│   ├── outputs.tf
│   ├── modules/
│   │   ├── cloud-run/
│   │   ├── cloud-scheduler/
│   │   ├── firestore/
│   │   ├── gcs/
│   │   ├── iam/
│   │   └── secrets/
│   └── environments/
│       ├── dev.tfvars
│       └── prod.tfvars
│
├── dashboard/                     # Optional: monitoring UI
│   ├── src/                       # React app showing run history
│   └── package.json
│
├── scripts/
│   ├── setup-youtube-oauth.sh     # Interactive OAuth consent flow
│   └── seed-firestore.sh          # Seed channel config
│
├── .github/
│   └── workflows/
│       └── deploy.yml             # CI/CD
│
└── README.md
```

---

## Key Implementation Details

### YouTube OAuth 2.0 Setup

YouTube Data API requires OAuth 2.0 with a refresh token (service accounts can't upload to user-owned channels). The flow:

1. Create OAuth 2.0 Client ID (Desktop app type) in Cloud Console
2. Run the consent flow once manually to get a refresh token
3. Store the refresh token in Secret Manager
4. The orchestrator uses the refresh token to mint short-lived access tokens at upload time

```java
// YoutubeService.java — token refresh sketch
GoogleCredential credential = new GoogleCredential.Builder()
    .setTransport(httpTransport)
    .setJsonFactory(jsonFactory)
    .setClientSecrets(clientId, clientSecret)
    .build()
    .setRefreshToken(refreshToken);  // from Secret Manager

YouTube youtube = new YouTube.Builder(httpTransport, jsonFactory, credential)
    .setApplicationName("ai-youtube-agent")
    .build();
```

### Vertex AI (Gemini) Integration

```java
// VertexAiService.java — script generation sketch
public String generateScript(String topic, String systemPrompt) {
    GenerativeModel model = GenerativeModel.builder()
        .modelName("gemini-1.5-pro")
        .project(projectId)
        .location("us-central1")
        .systemInstruction(systemPrompt)
        .build();

    Content content = Content.newBuilder()
        .setRole("user")
        .addParts(Part.newBuilder()
            .setText("Write a YouTube script about: " + topic))
        .build();

    GenerateContentResponse response = model.generateContent(content);
    return response.getCandidates(0).getContent()
        .getParts(0).getText();
}
```

### Video Rendering (Python + FFmpeg)

```python
# render.py — simplified assembly
from moviepy.editor import (
    AudioFileClip, ImageClip, CompositeVideoClip,
    concatenate_videoclips, TextClip
)

def render_video(sections, audio_path, output_path):
    audio = AudioFileClip(audio_path)
    clips = []
    for section in sections:
        img = ImageClip(section["image_path"]).set_duration(section["duration"])
        clips.append(img)

    video = concatenate_videoclips(clips, method="compose")
    video = video.set_audio(audio)
    video.write_videofile(output_path, fps=24, codec="libx264",
                          audio_codec="aac", preset="medium")
```

---

## Terraform — Core Resources

```hcl
# infra/main.tf (abbreviated)

resource "google_cloud_run_v2_service" "orchestrator" {
  name     = "yt-agent-orchestrator"
  location = var.region
  template {
    containers {
      image = "${var.region}-docker.pkg.dev/${var.project}/yt-agent/orchestrator:latest"
      resources {
        limits = { memory = "2Gi", cpu = "2" }
      }
      env {
        name  = "GCS_BUCKET"
        value = google_storage_bucket.artifacts.name
      }
    }
    timeout = "2700s"  # 45 min max for full pipeline
    service_account = google_service_account.agent.email
  }
}

resource "google_cloud_run_v2_job" "video_renderer" {
  name     = "yt-agent-renderer"
  location = var.region
  template {
    template {
      containers {
        image = "${var.region}-docker.pkg.dev/${var.project}/yt-agent/renderer:latest"
        resources {
          limits = { memory = "8Gi", cpu = "4" }
        }
      }
      timeout = "1800s"
    }
  }
}

resource "google_cloud_scheduler_job" "daily_trigger" {
  name     = "yt-agent-daily"
  schedule = "0 6 * * *"
  time_zone = "America/New_York"
  http_target {
    uri         = google_cloud_run_v2_service.orchestrator.uri
    http_method = "POST"
    body        = base64encode(jsonencode({ channel_id = "tech-explained" }))
    oidc_token {
      service_account_email = google_service_account.scheduler.email
    }
  }
}

resource "google_storage_bucket" "artifacts" {
  name     = "${var.project}-yt-agent-artifacts"
  location = var.region
  lifecycle_rule {
    action { type = "Delete" }
    condition { age = 30 }  # cleanup old renders
  }
}

resource "google_firestore_database" "main" {
  project     = var.project
  name        = "(default)"
  location_id = var.region
  type        = "FIRESTORE_NATIVE"
}
```

---

## Cost Estimate (1 video/day)

| Service | Monthly Usage | Est. Cost |
|---|---|---|
| Vertex AI (Gemini 1.5 Pro) | ~30 calls × ~5K tokens | ~$5 |
| Cloud TTS (WaveNet) | ~150K chars | Free tier |
| Imagen (thumbnails) | ~30 images | ~$1 |
| Cloud Run (orchestrator) | ~30 runs × 15 min | ~$3 |
| Cloud Run Job (renderer) | ~30 runs × 20 min (4 CPU) | ~$8 |
| Cloud Storage | ~30 GB/month | ~$1 |
| Firestore | Minimal reads/writes | Free tier |
| Cloud Scheduler | 1 job | Free tier |
| YouTube Data API | Quota: 10K units/day (upload = 1600) | Free |
| **Total** | | **~$18/month** |

Third-party APIs (ElevenLabs, stock footage) would add $10–50/month depending on usage.

---

## Getting Started — Quickstart

```bash
# 1. Create GCP project & enable APIs
gcloud projects create ai-youtube-agent --name="AI YouTube Agent"
gcloud config set project ai-youtube-agent

gcloud services enable \
  run.googleapis.com \
  aiplatform.googleapis.com \
  texttospeech.googleapis.com \
  firestore.googleapis.com \
  cloudscheduler.googleapis.com \
  secretmanager.googleapis.com \
  youtube.googleapis.com \
  cloudbuild.googleapis.com

# 2. Set up infrastructure
cd infra && terraform init && terraform apply -var-file=environments/dev.tfvars

# 3. Run YouTube OAuth flow (one-time)
./scripts/setup-youtube-oauth.sh

# 4. Seed channel config
./scripts/seed-firestore.sh

# 5. Deploy services
gcloud builds submit orchestrator/ --config=orchestrator/cloudbuild.yaml
gcloud builds submit video-renderer/ --config=video-renderer/cloudbuild.yaml

# 6. Test a manual run
curl -X POST $(gcloud run services describe yt-agent-orchestrator \
  --format='value(status.url)') \
  -H "Authorization: Bearer $(gcloud auth print-identity-token)" \
  -H "Content-Type: application/json" \
  -d '{"channel_id": "tech-explained"}'
```

---

## Risks & Mitigations

| Risk | Mitigation |
|---|---|
| YouTube API quota exhaustion | Resumable uploads, batch metadata updates, monitor quota dashboard |
| LLM hallucinations in scripts | Grounded generation + dedicated fact-check pass + human review queue option |
| Copyright issues (images/music) | Use only Imagen-generated or CC0 stock; no copyrighted music |
| Monotone/robotic audio | Use Studio-quality voices, SSML for pacing, or ElevenLabs for premium |
| Channel gets flagged as spam | Add human-in-the-loop approval mode; vary content style; disclose AI |
| Render failures (FFmpeg) | Retry with backoff; alert via Cloud Monitoring; fallback to simpler template |

---

## Future Enhancements

- **Analytics feedback loop** — pull YouTube Analytics to learn which topics/thumbnails perform best and feed that back into topic scoring
- **Multi-channel support** — run N channels from one deployment with different configs
- **Shorts pipeline** — separate flow that cuts highlights from long-form into YouTube Shorts
- **Community engagement agent** — auto-reply to comments using Gemini with guardrails
- **A/B testing** — generate multiple thumbnails/titles, use YouTube's built-in A/B testing
- **Human-in-the-loop** — Slack/Discord approval step before upload for quality control