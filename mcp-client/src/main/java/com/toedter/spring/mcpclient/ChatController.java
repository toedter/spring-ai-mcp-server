package com.toedter.spring.mcpclient;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

/**
 * Exposes the chat endpoints consumed by the Angular chatbot. The streaming endpoint drives a
 * human-in-the-loop flow: whenever the model wants to run an MCP tool, an {@code approval} event is
 * pushed to the browser and the tool call blocks until the user approves or denies it via {@code
 * /api/chat/approve}. Once approved, the weather tool additionally pauses for an {@code
 * elicitation} event if no temperature unit was specified, resolved via {@code
 * /api/chat/elicitation-decision} (see {@link TemperatureUnitElicitationToolCallback}).
 *
 * <p>Conversation history is kept per {@code conversationId} (sent by the browser with every
 * request, see {@link DeepChatRequest#conversationId()}) using Spring AI's {@link
 * MessageChatMemoryAdvisor}, so the model remembers earlier turns of the same chat session instead
 * of treating every message in isolation.
 */
@RestController
public class ChatController {
  /** Conversation id used when the caller doesn't supply one (e.g. plain API calls/tests). */
  private static final String DEFAULT_CONVERSATION_ID = "default";

  private final ChatClient chatClient;
  private final ApprovalRegistry approvalRegistry;
  private final ObjectMapper objectMapper = new ObjectMapper();

  public ChatController(
      ChatClient.Builder chatClientBuilder,
      ToolCallbackProvider tools,
      ApprovalRegistry approvalRegistry,
      ChatMemory chatMemory) {
    this.approvalRegistry = approvalRegistry;
    // Wrap every MCP tool so it requires user approval before executing; the
    // weather tool is further wrapped so it asks for a preferred temperature
    // unit (once approved) if the model didn't already supply one.
    List<ToolCallback> guardedTools =
        Arrays.stream(tools.getToolCallbacks())
            .map(
                tc ->
                    (ToolCallback) new TemperatureUnitElicitationToolCallback(tc, approvalRegistry))
            .map(tc -> (ToolCallback) new ApprovalToolCallback(tc, approvalRegistry))
            .toList();
    this.chatClient =
        chatClientBuilder
            .defaultSystem(
                "You are a friendly assistant that can use tools when needed. When calling"
                    + " weather tools, only supply a temperature unit argument if the user has"
                    + " explicitly told you which unit (celsius or fahrenheit) they prefer;"
                    + " otherwise omit that argument so the application can ask the user."
                    + " For Movie related questions, only use the available mcp tools")
            .defaultTools(guardedTools)
            .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
            .build();
  }

  @GetMapping("/chat")
  public String chat(@RequestParam(required = false) String message) {
    if (message == null || message.isBlank()) {
      return "Please provide 'message' parameter";
    }
    CurrentUserToken.set(extractUserAccessToken());
    try {
      return this.chatClient
          .prompt()
          .user(message)
          .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, DEFAULT_CONVERSATION_ID))
          .call()
          .content();
    } finally {
      CurrentUserToken.clear();
    }
  }

  /** Non-streaming endpoint (kept for compatibility). */
  @PostMapping("/api/chat")
  public Map<String, Object> apiChat(@RequestBody DeepChatRequest request) {
    String message = request.lastUserMessage();
    CurrentUserToken.set(extractUserAccessToken());
    try {
      String answer =
          this.chatClient
              .prompt()
              .user(message)
              .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, request.conversationIdOrDefault()))
              .call()
              .content();
      return Map.of("text", answer == null ? "" : answer);
    } finally {
      CurrentUserToken.clear();
    }
  }

  /**
   * Streaming endpoint driven by the Angular deep-chat custom handler. Emits JSON events over SSE:
   * {@code approval}, {@code status}, {@code chunk}, {@code error} and then completes. Tool calls
   * pause here until the user approves them via the approve endpoint.
   */
  @PostMapping(value = "/api/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public Flux<String> apiChatStream(@RequestBody DeepChatRequest request) {
    Sinks.Many<String> sink = Sinks.many().unicast().onBackpressureBuffer();
    String message = request.lastUserMessage();
    if (message == null || message.isBlank()) {
      sink.tryEmitNext(event(Map.of("type", "chunk", "text", "Please provide a message.")));
      sink.tryEmitComplete();
      return sink.asFlux();
    }
    StreamSession session = new StreamSession(sink, objectMapper);
    // Capture the user's token on the request thread: the model/tool call
    // below runs on a boundedElastic worker thread where Spring Security's
    // SecurityContextHolder is not propagated.
    String userAccessToken = extractUserAccessToken();
    String conversationId = request.conversationIdOrDefault();
    Schedulers.boundedElastic()
        .schedule(
            () -> {
              ToolApprovalContext.set(session);
              CurrentUserToken.set(userAccessToken);
              try {
                String answer =
                    this.chatClient
                        .prompt()
                        .user(message)
                        .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
                        .call()
                        .content();
                streamWords(sink, answer == null ? "" : answer);
                sink.tryEmitComplete();
              } catch (Exception e) {
                sink.tryEmitNext(
                    event(
                        Map.of(
                            "type",
                            "error",
                            "message",
                            e.getMessage() == null ? "Unexpected error" : e.getMessage())));
                sink.tryEmitComplete();
              } finally {
                ToolApprovalContext.clear();
                CurrentUserToken.clear();
              }
            });
    return sink.asFlux();
  }

  /** Receives the user's approve/deny decision for a pending tool call. */
  @PostMapping("/api/chat/approve")
  public Map<String, Object> approve(@RequestBody ApprovalDecision decision) {
    approvalRegistry.complete(decision.id(), decision.approved());
    return Map.of("ok", true);
  }

  /** Receives the user's chosen temperature unit for a pending elicitation. */
  @PostMapping("/api/chat/elicitation-decision")
  public Map<String, Object> elicitationDecision(@RequestBody ElicitationDecision decision) {
    approvalRegistry.complete(decision.id(), decision.unit());
    return Map.of("ok", true);
  }

  private void streamWords(Sinks.Many<String> sink, String text) {
    // Split keeping trailing spaces so words re-join naturally in the UI.
    String[] tokens = text.split("(?<= )");
    for (String token : tokens) {
      sink.tryEmitNext(event(Map.of("type", "chunk", "text", token)));
      try {
        Thread.sleep(15);
      } catch (InterruptedException ignored) {
        Thread.currentThread().interrupt();
      }
    }
  }

  private String event(Map<String, Object> data) {
    try {
      return objectMapper.writeValueAsString(data);
    } catch (Exception e) {
      return "{\"type\":\"error\",\"message\":\"serialization failed\"}";
    }
  }

  /**
   * Returns the raw access token of the currently authenticated end user (as validated by the
   * resource-server JWT filter), or {@code null} if the request isn't authenticated with a JWT
   * (e.g. in tests). Used as the {@code subject_token} for the RFC 8693 token exchange in {@link
   * McpTransportConfig}.
   */
  private String extractUserAccessToken() {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication instanceof JwtAuthenticationToken jwtAuthentication) {
      return jwtAuthentication.getToken().getTokenValue();
    }
    return null;
  }

  /**
   * Request payload sent by the deep-chat component. {@code conversationId} identifies the browser
   * chat session (generated once per page load, see {@code app.ts}) so the server can look up the
   * right chat-memory bucket; when absent (e.g. direct API calls), {@link #DEFAULT_CONVERSATION_ID}
   * is used instead.
   */
  public record DeepChatRequest(List<Message> messages, String conversationId) {
    String lastUserMessage() {
      if (messages == null || messages.isEmpty()) {
        return null;
      }
      return messages.get(messages.size() - 1).text();
    }

    String conversationIdOrDefault() {
      return conversationId == null || conversationId.isBlank()
          ? DEFAULT_CONVERSATION_ID
          : conversationId;
    }
  }

  public record Message(String role, String text) {}

  public record ApprovalDecision(String id, boolean approved) {}

  public record ElicitationDecision(String id, String unit) {}
}
