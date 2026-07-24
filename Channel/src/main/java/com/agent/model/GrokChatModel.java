package com.agent.model;

import com.google.adk.models.BaseLlm;
import com.google.adk.models.BaseLlmConnection;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.adk.models.chat.ChatCompletionsClient;
import com.google.adk.models.chat.ChatCompletionsHttpClient;
import com.google.genai.types.HttpOptions;
import io.reactivex.rxjava3.core.Flowable;

import java.util.Map;

/**
 * A {@link BaseLlm} backed by an OpenAI-compatible Chat Completions endpoint.
 *
 * <p>Used here to talk to xAI's Grok API ({@code https://api.x.ai/v1}), which speaks the OpenAI
 * chat-completions protocol. ADK's {@link ChatCompletionsHttpClient} handles request/response
 * translation, including tool (function) calling, so this class only wires the endpoint,
 * credentials, and model id.
 */
public class GrokChatModel extends BaseLlm {

    private final ChatCompletionsClient client;

    public GrokChatModel(String modelName, String baseUrl, String apiKey) {
        super(modelName);
        HttpOptions httpOptions = HttpOptions.builder()
                .baseUrl(baseUrl)
                .headers(Map.of("Authorization", "Bearer " + apiKey))
                .build();
        this.client = new ChatCompletionsHttpClient(httpOptions);
    }

    @Override
    public Flowable<LlmResponse> generateContent(LlmRequest request, boolean stream) {
        return client.complete(request, stream);
    }

    @Override
    public BaseLlmConnection connect(LlmRequest request) {
        throw new UnsupportedOperationException(
                "Live bidi streaming is not supported for the Grok (chat-completions) backend. "
                + "Use runAsync / generateContent instead.");
    }
}
