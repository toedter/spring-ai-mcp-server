package com.toedter.spring.mcpclient;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

/**
 * Performs an OAuth 2.0 Token Exchange (RFC 8693) against mcp-authorization-server so mcp-client
 * can swap an end user's access token for a new, delegated access token that:
 *
 * <ul>
 *   <li>keeps the original user's {@code sub} claim (so mcp-server still knows on whose behalf the
 *       request is made), and
 *   <li>adds mcp-client as the {@code act} (actor) claim (so mcp-server can also see which service
 *       actually performed the call).
 * </ul>
 *
 * The exchange uses mcp-client's own client-credentials access token (see {@link
 * McpServiceTokenProvider}) as the {@code actor_token}, and the incoming user's access token as the
 * {@code subject_token}. Both tokens are sent as {@code
 * urn:ietf:params:oauth:token-type:access_token}.
 *
 * <p>Exchanged tokens are cached per {@code (serverName, subjectAccessToken)} pair so that a
 * delegated token is never reused across MCP server connections, even though this demo only
 * configures one.
 *
 * @see <a href="https://datatracker.ietf.org/doc/html/rfc8693">RFC 8693 - OAuth 2.0 Token
 *     Exchange</a>
 */
@Component
public class TokenExchangeService {

  private static final String TOKEN_EXCHANGE_GRANT_TYPE =
      "urn:ietf:params:oauth:grant-type:token-exchange";
  private static final String ACCESS_TOKEN_TYPE = "urn:ietf:params:oauth:token-type:access_token";
  private static final String SCOPE = "scope";

  private final RestClient restClient = RestClient.create();
  private final ObjectMapper objectMapper = new ObjectMapper();
  private final String tokenUri;
  private final String clientId;
  private final String clientSecret;
  private final String scope;

  /**
   * Exchanged tokens are cached per (server, subject token) to avoid a round-trip per tool call.
   */
  private final Map<CacheKey, CachedToken> cache = new ConcurrentHashMap<>();

  public TokenExchangeService(
      @Value("${mcp.service-token.token-uri}") String tokenUri,
      @Value("${mcp.service-token.client-id}") String clientId,
      @Value("${mcp.service-token.client-secret}") String clientSecret,
      @Value("${mcp.service-token.scope}") String scope) {
    this.tokenUri = tokenUri;
    this.clientId = clientId;
    this.clientSecret = clientSecret;
    this.scope = scope;
  }

  /**
   * Exchanges {@code subjectAccessToken} (the end user's access token) for a new access token
   * scoped to {@code serverName} that keeps the user's {@code sub} claim while adding {@code
   * actorAccessToken}'s owner (mcp-client) as the {@code act} claim.
   */
  public String exchangeUserToken(
      String serverName, String subjectAccessToken, String actorAccessToken) {
    CacheKey key = new CacheKey(serverName, subjectAccessToken);
    CachedToken cached = cache.get(key);
    if (cached != null && cached.isValid()) {
      return cached.accessToken();
    }
    CachedToken fresh = fetchExchangedToken(subjectAccessToken, actorAccessToken);
    cache.entrySet().removeIf(entry -> !entry.getValue().isValid());
    cache.put(key, fresh);
    return fresh.accessToken();
  }

  @SuppressWarnings("unchecked")
  private CachedToken fetchExchangedToken(String subjectAccessToken, String actorAccessToken) {
    MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
    form.add("grant_type", TOKEN_EXCHANGE_GRANT_TYPE);
    form.add("subject_token", subjectAccessToken);
    form.add("subject_token_type", ACCESS_TOKEN_TYPE);
    form.add("actor_token", actorAccessToken);
    form.add("actor_token_type", ACCESS_TOKEN_TYPE);
    form.add("client_id", clientId);
    form.add("client_secret", clientSecret);
    // Without an explicit scope, the authorization server falls back to
    // authorizing all of the subject token's scopes, which fails with
    // invalid_scope as soon as the end user's token carries a scope
    // mcp-client-client isn't registered for. Requesting only the
    // intersection of "what the subject token actually has" and "what this
    // client is registered for" avoids that failure while never widening the
    // subject's own access.
    String requestedScope = intersectScopes(subjectAccessToken);
    if (requestedScope != null) {
      form.add(SCOPE, requestedScope);
    }

    Map<String, Object> response =
        restClient
            .post()
            .uri(tokenUri)
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .body(form)
            .retrieve()
            .body(Map.class);

    if (response == null || response.get("access_token") == null) {
      throw new IllegalStateException(
          "Token exchange endpoint " + tokenUri + " returned no access_token");
    }
    String accessToken = (String) response.get("access_token");
    Number expiresIn = response.get("expires_in") instanceof Number n ? n : 300;
    // Refresh a little early to avoid races with in-flight requests.
    Instant expiry = Instant.now().plusSeconds(Math.max(0, expiresIn.longValue() - 30));
    return new CachedToken(accessToken, expiry);
  }

  /**
   * Restricts the token-exchange {@code scope} request to the intersection of {@code
   * subjectAccessToken}'s own {@code scope} claim and mcp-client's own registered scopes, so the
   * exchange never requests (and thus never risks being granted) a scope the subject token didn't
   * already carry. Returns {@code null} if the subject token has no {@code scope} claim, in which
   * case the authorization server falls back to its own default scope resolution.
   */
  private String intersectScopes(String subjectAccessToken) {
    Set<String> subjectScopes = extractScopes(decodeClaims(subjectAccessToken));
    if (subjectScopes.isEmpty()) {
      return null;
    }
    Set<String> ownScopes = new LinkedHashSet<>(Arrays.asList(scope.split(" ")));
    subjectScopes.retainAll(ownScopes);
    return subjectScopes.isEmpty() ? null : String.join(" ", subjectScopes);
  }

  @SuppressWarnings("unchecked")
  private static Set<String> extractScopes(Map<String, Object> claims) {
    Object rawScope = claims.get(SCOPE);
    if (rawScope instanceof Iterable<?> scopes) {
      Set<String> result = new LinkedHashSet<>();
      scopes.forEach(s -> result.add(String.valueOf(s)));
      return result;
    }
    if (rawScope instanceof String scopeString && !scopeString.isBlank()) {
      return new LinkedHashSet<>(Arrays.asList(scopeString.split(" ")));
    }
    return Set.of();
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> decodeClaims(String jwt) {
    try {
      String[] parts = jwt.split("\\.");
      byte[] payload = Base64.getUrlDecoder().decode(parts[1]);
      return objectMapper.readValue(payload, Map.class);
    } catch (Exception _) {
      // Not a decodable JWT (e.g. an opaque token in tests): fall back to
      // requesting no explicit scope rather than failing the exchange.
      return Map.of();
    }
  }

  private record CacheKey(String serverName, String subjectAccessToken) {}

  private record CachedToken(String accessToken, Instant expiry) {
    boolean isValid() {
      return accessToken != null && Instant.now().isBefore(expiry);
    }
  }
}
