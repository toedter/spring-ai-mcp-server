package com.toedter.spring.authorizationserver;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;

/**
 * Verifies that {@link AuthorizationServerApplication#tokenCustomizer()} narrows the {@code scope}
 * claim to the intersection of the authorized scopes and the authenticated principal's own {@code
 * SCOPE_mcp.tools*} authorities, since Spring Authorization Server's built-in scope validation only
 * checks requested scopes against the client's registration, never the individual user's
 * authorities (see {@code AuthorizationServerApplication.userDetailsManager} where John and Jane
 * are deliberately granted different mcp.tools.* subsets).
 */
class AuthorizationServerApplicationTest {

  private final OAuth2TokenCustomizer<JwtEncodingContext> tokenCustomizer =
      new AuthorizationServerApplication().tokenCustomizer();

  @Test
  void narrowsScopeToPrincipalAuthoritiesForAuthorizationCodeGrant() {
    JwtEncodingContext context =
        buildContext(
            AuthorizationGrantType.AUTHORIZATION_CODE,
            Set.of("openid", "profile", "mcp.tools", "mcp.tools.movies", "mcp.tools.weather"),
            "SCOPE_mcp.tools",
            "SCOPE_mcp.tools.movies");

    tokenCustomizer.customize(context);

    @SuppressWarnings("unchecked")
    Set<String> resultingScope = (Set<String>) context.getClaims().build().getClaim("scope");
    // openid/profile are untouched, mcp.tools.movies survives (John has it),
    // mcp.tools.weather is stripped (John does not have it).
    assertThat(resultingScope)
        .containsExactlyInAnyOrder("openid", "profile", "mcp.tools", "mcp.tools.movies");
  }

  @Test
  void narrowsScopeToPrincipalAuthoritiesForTokenExchangeGrant() {
    JwtEncodingContext context =
        buildContext(
            AuthorizationGrantType.TOKEN_EXCHANGE,
            Set.of("mcp.tools", "mcp.tools.movies", "mcp.tools.weather"),
            "SCOPE_mcp.tools",
            "SCOPE_mcp.tools.weather");

    tokenCustomizer.customize(context);

    @SuppressWarnings("unchecked")
    Set<String> resultingScope = (Set<String>) context.getClaims().build().getClaim("scope");
    assertThat(resultingScope).containsExactlyInAnyOrder("mcp.tools", "mcp.tools.weather");
  }

  @Test
  void doesNotNarrowScopeForClientCredentialsGrant() {
    // Client-credentials principals (the client itself) carry no SCOPE_mcp.tools*
    // authorities at all -- narrowing must not apply, or every mcp.tools scope
    // on service tokens (mcp-client, mcp-server) would be stripped.
    JwtEncodingContext context =
        buildContext(
            AuthorizationGrantType.CLIENT_CREDENTIALS,
            Set.of("mcp.tools", "mcp.tools.movies", "mcp.tools.weather", "mcp.tools.diagnostics"));

    tokenCustomizer.customize(context);

    @SuppressWarnings("unchecked")
    Set<String> resultingScope = (Set<String>) context.getClaims().build().getClaim("scope");
    assertThat(resultingScope)
        .containsExactlyInAnyOrder(
            "mcp.tools", "mcp.tools.movies", "mcp.tools.weather", "mcp.tools.diagnostics");
  }

  private static JwtEncodingContext buildContext(
      AuthorizationGrantType grantType, Set<String> authorizedScopes, String... authorities) {
    JwsHeader.Builder jwsHeaderBuilder = JwsHeader.with(SignatureAlgorithm.RS256);
    JwtClaimsSet.Builder claimsBuilder =
        JwtClaimsSet.builder()
            .issuer("http://localhost:9000")
            .subject("test-user")
            .claim(OAuth2ParameterNames.SCOPE, authorizedScopes);

    List<SimpleGrantedAuthority> grantedAuthorities =
        java.util.Arrays.stream(authorities).map(SimpleGrantedAuthority::new).toList();
    UsernamePasswordAuthenticationToken principal =
        new UsernamePasswordAuthenticationToken("test-user", "n/a", grantedAuthorities);

    return JwtEncodingContext.with(jwsHeaderBuilder, claimsBuilder)
        .principal(principal)
        .authorizedScopes(authorizedScopes)
        .tokenType(OAuth2TokenType.ACCESS_TOKEN)
        .authorizationGrantType(grantType)
        .build();
  }
}
