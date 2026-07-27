package com.agent.video.impl;

import com.agent.video.NarrationService;
import com.agent.video.VideoPipelineProperties;
import com.agent.video.model.NarrationClip;
import com.agent.video.model.Segment;
import com.agent.video.model.Storyboard;
import com.google.cloud.texttospeech.v1.AudioConfig;
import com.google.cloud.texttospeech.v1.AudioEncoding;
import com.google.cloud.texttospeech.v1.SynthesisInput;
import com.google.cloud.texttospeech.v1.SynthesizeSpeechResponse;
import com.google.cloud.texttospeech.v1.TextToSpeechClient;
import com.google.cloud.texttospeech.v1.VoiceSelectionParams;
import com.google.protobuf.ByteString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Cloud Text-to-Speech (Chirp 3 HD) narration synthesis. See docs/VIDEO_PIPELINE.md.
 * Plain-text synthesis (no SSML — Chirp3 HD voices don't support it), LINEAR16 @ 24kHz.
 */
@Component
public class TtsNarrationService implements NarrationService {

    private static final Logger log = LoggerFactory.getLogger(TtsNarrationService.class);

    /** Stay comfortably under the ~5000-byte Cloud TTS request limit. */
    private static final int MAX_REQUEST_BYTES = 4500;
    private static final int SAMPLE_RATE_HZ = 24000;
    private static final int BYTES_PER_SAMPLE = 2; // 16-bit mono

    private final VideoPipelineProperties props;

    public TtsNarrationService(VideoPipelineProperties props) {
        this.props = props;
    }

    /** Parallel synthesis requests; Cloud TTS quotas are generous (hundreds/min). */
    private static final int TTS_CONCURRENCY = 8;

    @Override
    public List<NarrationClip> synthesize(Storyboard storyboard, Path audioDir) throws Exception {
        VideoPipelineProperties.Tts ttsProps = props.tts();

        try (TextToSpeechClient client = TextToSpeechClient.create()) {
            VoiceSelectionParams voice = VoiceSelectionParams.newBuilder()
                    .setLanguageCode(ttsProps.languageCode())
                    .setName(ttsProps.voice())
                    .build();

            AudioConfig audioConfig = AudioConfig.newBuilder()
                    .setAudioEncoding(AudioEncoding.LINEAR16)
                    .setSampleRateHertz(SAMPLE_RATE_HZ)
                    .setSpeakingRate(ttsProps.speakingRate())
                    .build();

            // The gRPC client is thread-safe; segments are independent. Futures are
            // collected in segment order so the returned list stays ordered.
            java.util.concurrent.ExecutorService pool =
                    java.util.concurrent.Executors.newFixedThreadPool(TTS_CONCURRENCY);
            try {
                List<java.util.concurrent.Future<NarrationClip>> futures = new ArrayList<>();
                for (Segment segment : storyboard.segments()) {
                    futures.add(pool.submit(() -> {
                        byte[] pcm = synthesizeSegmentPcm(client, voice, audioConfig, segment.narration());
                        Path wavFile = audioDir.resolve(String.format("seg-%03d.wav", segment.index()));
                        writeWav(wavFile, pcm);
                        double durationSeconds = pcm.length / (BYTES_PER_SAMPLE * (double) SAMPLE_RATE_HZ);
                        log.info("synthesized segment {} -> {} ({}s)", segment.index(), wavFile, durationSeconds);
                        return new NarrationClip(segment.index(), wavFile, durationSeconds);
                    }));
                }
                List<NarrationClip> clips = new ArrayList<>();
                for (java.util.concurrent.Future<NarrationClip> future : futures) {
                    clips.add(future.get());
                }
                return clips;
            } finally {
                pool.shutdown();
            }
        }
    }

    /**
     * Synthesizes narration text, splitting into multiple requests at sentence boundaries
     * if it exceeds the per-request byte budget, and concatenating the raw PCM samples.
     */
    private byte[] synthesizeSegmentPcm(TextToSpeechClient client, VoiceSelectionParams voice,
                                        AudioConfig audioConfig, String narration) throws Exception {
        List<String> chunks = splitIntoChunks(narration, MAX_REQUEST_BYTES);
        ByteArrayOutputStream pcmOut = new ByteArrayOutputStream();

        for (String chunk : chunks) {
            SynthesisInput input = SynthesisInput.newBuilder().setText(chunk).build();
            SynthesizeSpeechResponse response = client.synthesizeSpeech(input, voice, audioConfig);
            ByteString audioContent = response.getAudioContent();
            byte[] wavBytes = audioContent.toByteArray();
            byte[] pcm = extractPcmFromWav(wavBytes);
            pcmOut.write(pcm);
        }

        return pcmOut.toByteArray();
    }

    /** Splits text into chunks each under maxBytes (UTF-8), breaking at sentence boundaries. */
    private List<String> splitIntoChunks(String text, int maxBytes) {
        List<String> chunks = new ArrayList<>();
        if (text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= maxBytes) {
            chunks.add(text);
            return chunks;
        }

        // Split on sentence boundaries (after '.', '!', '?' followed by whitespace).
        String[] sentences = text.split("(?<=[.!?])\\s+");
        StringBuilder current = new StringBuilder();
        for (String sentence : sentences) {
            String candidate = current.length() == 0 ? sentence : current + " " + sentence;
            if (candidate.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > maxBytes && current.length() > 0) {
                chunks.add(current.toString());
                current = new StringBuilder(sentence);
            } else {
                current = new StringBuilder(candidate);
            }
        }
        if (current.length() > 0) {
            chunks.add(current.toString());
        }
        return chunks;
    }

    /** Cloud TTS with LINEAR16 encoding returns a complete WAV; strip the header to get raw PCM. */
    private byte[] extractPcmFromWav(byte[] wavBytes) {
        ByteBuffer buf = ByteBuffer.wrap(wavBytes).order(ByteOrder.LITTLE_ENDIAN);
        int pos = 12; // past "RIFF"+size+"WAVE"
        while (pos + 8 <= wavBytes.length) {
            String chunkId = new String(wavBytes, pos, 4, java.nio.charset.StandardCharsets.US_ASCII);
            int chunkSize = buf.getInt(pos + 4);
            int dataStart = pos + 8;
            if ("data".equals(chunkId)) {
                int end = Math.min(dataStart + chunkSize, wavBytes.length);
                byte[] pcm = new byte[end - dataStart];
                System.arraycopy(wavBytes, dataStart, pcm, 0, pcm.length);
                return pcm;
            }
            pos = dataStart + chunkSize + (chunkSize % 2); // chunks are word-aligned
        }
        // No data chunk found (shouldn't happen) — treat whole payload as PCM.
        return wavBytes;
    }

    /** Writes a standard 44-byte-header PCM WAV file (16-bit mono, 24kHz). */
    private void writeWav(Path path, byte[] pcm) throws Exception {
        int byteRate = SAMPLE_RATE_HZ * BYTES_PER_SAMPLE;
        int dataSize = pcm.length;
        int riffSize = 36 + dataSize;

        ByteBuffer header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        header.put("RIFF".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        header.putInt(riffSize);
        header.put("WAVE".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        header.put("fmt ".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        header.putInt(16); // fmt chunk size
        header.putShort((short) 1); // PCM
        header.putShort((short) 1); // mono
        header.putInt(SAMPLE_RATE_HZ);
        header.putInt(byteRate);
        header.putShort((short) BYTES_PER_SAMPLE); // block align
        header.putShort((short) 16); // bits per sample
        header.put("data".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        header.putInt(dataSize);

        try (var out = Files.newOutputStream(path)) {
            out.write(header.array());
            out.write(pcm);
        }
    }
}
