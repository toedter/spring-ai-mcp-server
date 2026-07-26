package com.toedter.spring.authorizationserver;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.security.autoconfigure.SecurityProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.security.oauth2.core.oidc.endpoint.OidcParameterNames;
import org.springframework.security.oauth2.server.authorization.InMemoryOAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2TokenExchangeActor;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2TokenExchangeCompositeAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.util.StringUtils;

@SpringBootApplication
public class AuthorizationServerApplication {

  public static void main(String[] args) {
    SpringApplication.run(AuthorizationServerApplication.class, args);
  }

  @Bean
  public InMemoryUserDetailsManager userDetailsManager(SecurityProperties properties) {
    SecurityProperties.User configuredUser = properties.getUser();
    List<String> roles = configuredUser.getRoles();

    UserDetails defaultUser =
        User.withUsername(configuredUser.getName())
            .password(configuredUser.getPassword())
            .roles(StringUtils.toStringArray(roles))
            .build();

    // Demo end-user for the Angular chatbot: john@doe.com / john
    UserDetails johnDoe =
        User.withUsername("john@doe.com")
            .password("{noop}john")
            .authorities("SCOPE_mcp.tools", "SCOPE_mcp.tools.movies", "SCOPE_mcp.tools.diagnostics")
            .build();

    // Demo end-user for the Angular chatbot: jane@doe.com / jane
    UserDetails janeDoe =
        User.withUsername("jane@doe.com")
            .password("{noop}jane")
            .authorities(
                "SCOPE_mcp.tools", "SCOPE_mcp.tools.weather", "SCOPE_mcp.tools.diagnostics")
            .build();

    return new InMemoryUserDetailsManager(defaultUser, johnDoe, janeDoe);
  }

  /**
   * Exposed as an explicit bean (rather than left as an internal default of {@code
   * OAuth2AuthorizationServerConfigurer}) so it can be autowired elsewhere, e.g. by tests that need
   * to seed an authorization to simulate a signed-in user for the token exchange grant.
   */
  @Bean
  public OAuth2AuthorizationService authorizationService() {
    return new InMemoryOAuth2AuthorizationService();
  }

  /**
   * Grant types where the principal represents an actual end user (directly, or as the {@code
   * subject} of a token-exchange delegation chain) rather than a client authenticating for itself.
   * Only for these does {@link #narrowMcpToolsScopeToPrincipalAuthorities} make sense:
   * client-credentials principals carry no {@code SCOPE_mcp.tools*} authorities at all, so
   * narrowing against them would strip every mcp.tools scope from service tokens.
   */
  private static final Set<AuthorizationGrantType> USER_SCOPED_GRANT_TYPES =
      Set.of(
          AuthorizationGrantType.AUTHORIZATION_CODE,
          AuthorizationGrantType.REFRESH_TOKEN,
          AuthorizationGrantType.TOKEN_EXCHANGE);

  @Bean
  public OAuth2TokenCustomizer<JwtEncodingContext> tokenCustomizer() {
    return context -> {
      // Only the resource-server access token should carry the MCP audience.
      // The ID token must keep the client-id audience so the SPA can validate it.
      if (OAuth2TokenType.ACCESS_TOKEN.equals(context.getTokenType())) {
        context.getClaims().audience(Collections.singletonList("mcp-audience"));
        narrowMcpToolsScopeToPrincipalAuthorities(context);

        // RFC 8693 Token Exchange (delegation use case): stamp the
        // "act" (actor) claim onto the exchanged token, e.g.
        // { "sub": "john@doe.com", "act": { "sub": "mcp-client-client" } }
        // so downstream resource servers (mcp-server) can see both who
        // the request is acting on behalf of ("sub") and who is
        // actually making the call ("act").
        if (AuthorizationGrantType.TOKEN_EXCHANGE.equals(context.getAuthorizationGrantType())
            && context.getPrincipal()
                instanceof OAuth2TokenExchangeCompositeAuthenticationToken compositeToken) {
          Map<String, Object> actorClaim = buildActorClaim(compositeToken.getActors());
          if (actorClaim != null) {
            context.getClaims().claim("act", actorClaim);
          }
        }
      }

      // Enrich the OIDC ID token with profile claims so the SPA can show the user.
      if (OidcParameterNames.ID_TOKEN.equals(context.getTokenType().getValue())) {
        String username = context.getPrincipal().getName();
        if ("john@doe.com".equals(username)) {
          context
              .getClaims()
              .claim("name", "John Doe")
              .claim("given_name", "John")
              .claim("family_name", "Doe")
              .claim("email", "john@doe.com");
        } else if ("jane@doe.com".equals(username)) {
          context
              .getClaims()
              .claim("name", "Jane Doe")
              .claim("given_name", "Jane")
              .claim("family_name", "Doe")
              .claim("email", "jane@doe.com");
        }
      }
    };
  }

  /**
   * Restricts the {@code scope} claim to the intersection of the token's authorized {@code
   * mcp.tools*} scopes and the authenticated principal's own {@code SCOPE_mcp.tools*} authorities,
   * so a user like John (only {@code SCOPE_mcp.tools.movies}) can never end up with {@code
   * mcp.tools.weather} in a token just because the client (e.g. mcp-chatbot-client) happens to be
   * registered for it. Spring Authorization Server's built-in scope validation only checks
   * requested scopes against the <em>client's</em> registration, not the end user's own
   * authorities, so this has to be enforced explicitly here. Non-{@code mcp.tools} scopes (e.g.
   * {@code openid}, {@code profile}) are left untouched. For token-exchange delegation, {@code
   * OAuth2TokenExchangeCompositeAuthenticationToken} carries the original end user's authorities at
   * every hop, so the same narrowing applies uniformly across the whole actor chain.
   */
  private static void narrowMcpToolsScopeToPrincipalAuthorities(JwtEncodingContext context) {
    if (!USER_SCOPED_GRANT_TYPES.contains(context.getAuthorizationGrantType())) {
      return;
    }
    Authentication principal = context.getPrincipal();
    Set<String> authorizedScopes = context.getAuthorizedScopes();
    if (principal == null || authorizedScopes.isEmpty()) {
      return;
    }

    Set<String> principalAuthorities = new LinkedHashSet<>();
    for (GrantedAuthority authority : principal.getAuthorities()) {
      principalAuthorities.add(authority.getAuthority());
    }

    Set<String> narrowedScopes = new LinkedHashSet<>();
    for (String scope : authorizedScopes) {
      if (!scope.startsWith("mcp.tools") || principalAuthorities.contains("SCOPE_" + scope)) {
        narrowedScopes.add(scope);
      }
    }
    context.getClaims().claim(OAuth2ParameterNames.SCOPE, narrowedScopes);
  }

  /**
   * Builds the (possibly nested) {@code act} claim from the actor chain of a token-exchange
   * delegation, innermost (oldest) actor first so the outermost claim represents the most recent
   * actor.
   *
   * @see <a href="https://datatracker.ietf.org/doc/html/rfc8693#section-4.4">RFC 8693 Section 4.4 -
   *     "act" (Actor) Claim</a>
   */
  private static Map<String, Object> buildActorClaim(List<OAuth2TokenExchangeActor> actors) {
    if (actors.isEmpty()) {
      return null;
    }
    Map<String, Object> claim = null;
    for (int i = actors.size() - 1; i >= 0; i--) {
      OAuth2TokenExchangeActor actor = actors.get(i);
      Map<String, Object> current = new LinkedHashMap<>();
      current.put("sub", actor.getSubject());
      if (claim != null) {
        current.put("act", claim);
      }
      claim = current;
    }
    return claim;
  }
}
