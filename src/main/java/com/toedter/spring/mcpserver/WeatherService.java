/*
* Copyright 2024 - 2024 the original author or authors.
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

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Service
public class WeatherService {

	private static final String BASE_URL = "http://localhost:8080/api";

	private final RestClient restClient;

	public WeatherService() {

		this.restClient = RestClient.builder()
			.baseUrl(BASE_URL)
			.defaultHeader("Accept", "application/vnd.api+json")
			.defaultHeader("User-Agent", "WeatherApiClient/1.0 (kai@toedter.com)")
			.build();
	}

	/**
	 * Get current weather for specific latitude and longitude
	 * @param latitude Latitude
	 * @param longitude Longitude
	 * @return The current weather for the given location
	 * @throws RestClientException if the request fails
	 */
	@Tool(description = "Get the current weather forecast for specific latitude/longitude")
	public String getWeatherForecastByLocation(double latitude, double longitude) {

		System.out.println(latitude + "," + longitude);
        return restClient.get()
            .uri("/current?latitude={latitude}&longitude={longitude}", latitude, longitude)
            .retrieve()
            .body(String.class);
	}

	public static void main(String[] args) {
		WeatherService client = new WeatherService();
		System.out.println(client.getWeatherForecastByLocation(47.6062, -122.3321));
	}

}
