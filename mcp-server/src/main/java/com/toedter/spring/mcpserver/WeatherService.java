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
public class WeatherService {

  private static final String BASE_URL = "https://api.open-meteo.com/v1/forecast";

  private static final Logger log = LoggerFactory.getLogger(WeatherService.class);

  private final RestClient restClient;

  public WeatherService() {
    SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
    requestFactory.setConnectTimeout(Duration.ofSeconds(5));
    requestFactory.setReadTimeout(Duration.ofSeconds(10));

    this.restClient =
        RestClient.builder()
            .baseUrl(BASE_URL)
            .defaultHeader("Accept", "application/vnd.api+json")
            .defaultHeader("User-Agent", "WeatherApiClient/1.0 (kai@toedter.com)")
            .requestFactory(requestFactory)
            .build();
  }

  /**
   * Get current weather for specific latitude and longitude
   *
   * @param latitude Latitude
   * @param longitude Longitude
   * @param unit Preferred temperature unit, "celsius" or "fahrenheit"
   * @return The current weather for the given location
   */
  @PreAuthorize("hasAuthority('SCOPE_mcp.tools.weather')")
  @McpTool(
      name = "get_weather_forecast_by_location",
      description = "Get the current weather forecast for a specific location.",
      annotations = @McpTool.McpAnnotations(readOnlyHint = true, openWorldHint = true))
  public String getWeatherForecastByLocation(
      @McpToolParam(description = "Latitude of the location, between -90 and 90") double latitude,
      @McpToolParam(description = "Longitude of the location, between -180 and 180")
          double longitude,
      @McpToolParam(
              required = false,
              description =
                  "Preferred temperature unit: \"celsius\" or \"fahrenheit\". Only set this if the"
                      + " user has explicitly stated a preferred unit in the conversation; otherwise"
                      + " omit this parameter entirely and let the caller decide.")
          String unit) {

    if (latitude < -90 || latitude > 90) {
      throw new IllegalArgumentException("latitude must be between -90 and 90");
    }
    if (longitude < -180 || longitude > 180) {
      throw new IllegalArgumentException("longitude must be between -180 and 180");
    }

    String temperatureUnit = unit == null || unit.isBlank() ? "celsius" : unit.toLowerCase();
    if (!temperatureUnit.equals("celsius") && !temperatureUnit.equals("fahrenheit")) {
      throw new IllegalArgumentException("unit must be either \"celsius\" or \"fahrenheit\"");
    }

    try {
      return restClient
          .get()
          .uri(
              "?latitude={latitude}&longitude={longitude}&current=temperature_2m,weathercode,windspeed_10m,precipitation&temperature_unit={unit}",
              latitude,
              longitude,
              temperatureUnit)
          .retrieve()
          .body(String.class);
    } catch (RestClientException e) {
      throw ToolErrors.sanitized(
          log, "Unable to fetch the weather forecast from the upstream service", e);
    }
  }
}
