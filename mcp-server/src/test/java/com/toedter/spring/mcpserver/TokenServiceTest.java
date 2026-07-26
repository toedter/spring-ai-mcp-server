package com.toedter.spring.mcpserver;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Verifies that {@link TokenService#exchangeToken(String)} only ever requests the intersection of
 * the subject token's own {@code scope} claim and mcp-server's own registered scopes, so a token
 * exchange never risks widening the subject's access.
 */
class TokenServiceTest {

  private HttpServer server;
  private final AtomicReference<String> lastRequestBody = new AtomicReference<>();
  private TokenService tokenService;

  @BeforeEach
  void startFakeTokenEndpoint() throws IOException {
    server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    server.createContext(
        "/oauth2/token",
        exchange -> {
          lastRequestBody.set(readRequestBody(exchange));
          String body = "{\"access_token\":\"exchanged-token\",\"expires_in\":300}";
          byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        });
    server.start();

    String tokenUri = "http://localhost:" + server.getAddress().getPort() + "/oauth2/token";
    tokenService =
        new TokenService(tokenUri, "client-id", "client-secret", "mcp.tools mcp.tools.weather");
  }

  @AfterEach
  void stopFakeTokenEndpoint() {
    server.stop(0);
  }

  @Test
  void requestsOnlyTheIntersectionOfSubjectScopesAndOwnRegisteredScopes() {
    // Subject token (already delegated by mcp-client) carries mcp.tools,
    // mcp.tools.weather AND mcp.tools.movies, but mcp-server's own
    // configuration (see startFakeTokenEndpoint) only has "mcp.tools
    // mcp.tools.weather" -- the movies scope must never be requested even
    // though the subject token has it.
    String subjectToken = fakeJwt("mcp.tools", "mcp.tools.weather", "mcp.tools.movies");

    tokenService.exchangeToken(subjectToken);

    Map<String, String> form = parseForm(lastRequestBody.get());
    assertThat(form.get("scope")).isEqualTo("mcp.tools mcp.tools.weather");
  }

  @Test
  void omitsScopeParameterWhenSubjectTokenIsNotADecodableJwt() {
    tokenService.exchangeToken("opaque-subject-token");

    Map<String, String> form = parseForm(lastRequestBody.get());
    assertThat(form).doesNotContainKey("scope");
  }

  private static String readRequestBody(HttpExchange exchange) throws IOException {
    return new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
  }

  private static Map<String, String> parseForm(String body) {
    Map<String, String> result = new LinkedHashMap<>();
    for (String pair : body.split("&")) {
      String[] parts = pair.split("=", 2);
      result.put(
          URLDecoder.decode(parts[0], StandardCharsets.UTF_8),
          URLDecoder.decode(parts[1], StandardCharsets.UTF_8));
    }
    return result;
  }

  /** Builds an unsigned JWT with the given {@code scope} values, sufficient for scope decoding. */
  private static String fakeJwt(String... scopes) {
    String header = base64UrlEncode("{\"alg\":\"none\"}");
    StringBuilder scopeArray = new StringBuilder("[");
    for (int i = 0; i < scopes.length; i++) {
      if (i > 0) {
        scopeArray.append(",");
      }
      scopeArray.append("\"").append(scopes[i]).append("\"");
    }
    scopeArray.append("]");
    String payload = base64UrlEncode("{\"sub\":\"test-user\",\"scope\":" + scopeArray + "}");
    return header + "." + payload + ".signature";
  }

  private static String base64UrlEncode(String json) {
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(json.getBytes(StandardCharsets.UTF_8));
  }
}
