package com.toedter.spring.authorizationserver;

import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.oauth2.server.authorization.OAuth2AuthorizationServerConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.security.web.savedrequest.RequestCache;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.MediaTypeRequestMatcher;
import org.springframework.security.web.util.matcher.NegatedRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
public class SecurityConfig {

  /**
   * Origins that are allowed to call the authorization/token/userinfo endpoints from the browser
   * (the Angular SPA runs on http://localhost:4200 by default).
   */
  private static final List<String> ALLOWED_ORIGINS = List.of("http://localhost:4200");

  @Bean
  @Order(1)
  public SecurityFilterChain authorizationServerSecurityFilterChain(HttpSecurity http)
      throws Exception {
    OAuth2AuthorizationServerConfigurer authorizationServerConfigurer =
        new OAuth2AuthorizationServerConfigurer();
    // Enable OpenID Connect 1.0 so the SPA can obtain an ID token / userinfo.
    authorizationServerConfigurer.oidc(Customizer.withDefaults());
    RequestMatcher endpointsMatcher = authorizationServerConfigurer.getEndpointsMatcher();

    http.securityMatcher(endpointsMatcher)
        .authorizeHttpRequests(authorize -> authorize.anyRequest().authenticated())
        .csrf(csrf -> csrf.ignoringRequestMatchers(endpointsMatcher))
        .cors(cors -> cors.configurationSource(corsConfigurationSource()))
        .with(authorizationServerConfigurer, Customizer.withDefaults())
        // Redirect unauthenticated browser requests to the login page.
        .exceptionHandling(
            exceptions ->
                exceptions.defaultAuthenticationEntryPointFor(
                    new LoginUrlAuthenticationEntryPoint("/login"),
                    new MediaTypeRequestMatcher(MediaType.TEXT_HTML)));

    return http.build();
  }

  @Bean
  @Order(2)
  public SecurityFilterChain defaultSecurityFilterChain(HttpSecurity http) throws Exception {
    http.authorizeHttpRequests(authorize -> authorize.anyRequest().authenticated())
        .cors(cors -> cors.configurationSource(corsConfigurationSource()))
        .requestCache(cache -> cache.requestCache(requestCache()))
        .formLogin(Customizer.withDefaults());

    return http.build();
  }

  /**
   * Chrome periodically probes /.well-known/appspecific/com.chrome.devtools.json when DevTools is
   * open, and browsers request /favicon.ico on every navigation. If either request hits an
   * unauthenticated session, the default request cache saves it and redirects there after login
   * instead of back to the real OAuth2 authorization request. Exclude both from being cached.
   */
  @Bean
  public RequestCache requestCache() {
    HttpSessionRequestCache requestCache = new HttpSessionRequestCache();
    requestCache.setRequestMatcher(
        new NegatedRequestMatcher(
            new OrRequestMatcher(
                PathPatternRequestMatcher.withDefaults().matcher("/.well-known/**"),
                PathPatternRequestMatcher.withDefaults().matcher("/favicon.ico"))));
    return requestCache;
  }

  @Bean
  public CorsConfigurationSource corsConfigurationSource() {
    CorsConfiguration configuration = new CorsConfiguration();
    configuration.setAllowedOrigins(ALLOWED_ORIGINS);
    configuration.setAllowedMethods(List.of("GET", "POST", "OPTIONS"));
    configuration.setAllowedHeaders(List.of("*"));
    configuration.setAllowCredentials(true);

    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/**", configuration);
    return source;
  }
}
