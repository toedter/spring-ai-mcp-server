/*
 * Copyright 2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.toedter.spring.mcpserver;

import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Service
public class MovieService {

  private static final String BASE_URL = "https://nexus.toedter.com/api/jsonapi";
  private static final int MAX_PAGE_SIZE = 250;

  private static final Logger log = LoggerFactory.getLogger(MovieService.class);

  private final RestClient restClient;

  public MovieService() {
    SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
    requestFactory.setConnectTimeout(Duration.ofSeconds(5));
    requestFactory.setReadTimeout(Duration.ofSeconds(10));

    this.restClient =
        RestClient.builder()
            .baseUrl(BASE_URL)
            .defaultHeader("Accept", "application/vnd.api+json")
            .defaultHeader("User-Agent", "DemoMcpServer/1.0 (kai@toedter.com)")
            .requestFactory(requestFactory)
            .build();
  }

  /**
   * Get the top ranked movies at imdb
   *
   * @param pageNumber page Number
   * @param pageSize page size
   * @return The ranked list of movies
   */
  @PreAuthorize("hasAuthority('SCOPE_mcp.tools.movies')")
  @McpTool(
      name = "get_top_ranked_movies",
      description = "Get the top-ranked movies from IMDb.",
      annotations = @McpTool.McpAnnotations(readOnlyHint = true, openWorldHint = true))
  public String getTopRankedMovies(
      @McpToolParam(description = "Page number, starting at 0") int pageNumber,
      @McpToolParam(description = "Page size, maximum is 250") int pageSize) {

    if (pageNumber < 0) {
      throw new IllegalArgumentException("pageNumber must not be negative");
    }
    if (pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
      throw new IllegalArgumentException("pageSize must be between 1 and " + MAX_PAGE_SIZE);
    }

    try {
      return restClient
          .get()
          .uri("/movies?page[number]={pageNumber}&page[size]={pageSize}", pageNumber, pageSize)
          .retrieve()
          .body(String.class);
    } catch (RestClientException e) {
      throw ToolErrors.sanitized(log, "Unable to fetch movies from the upstream service", e);
    }
  }
}
