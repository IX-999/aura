package com.agent.model;

import com.google.adk.models.BaseLlm;

/**
 * Strategy for supplying the LLM that backs an agent.
 *
 * <p>Implementations encapsulate everything provider-specific (endpoint, credentials, model id)
 * so agents can depend on this abstraction and the underlying model can be swapped purely through
 * configuration ({@code agent.model.provider}) without touching agent code.
 */
public interface LlmModelProvider {

    /** Builds the concrete {@link BaseLlm} this provider represents. */
    BaseLlm createModel();

    /** Human-readable provider id, e.g. {@code "gemini"} or {@code "grok"}. */
    String providerName();
}
