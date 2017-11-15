/**
 * Copyright (C) 2016 Red Hat, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.syndesis.runtime.action;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;

import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ContextConfiguration;

import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.junit.WireMockRule;

import io.syndesis.model.connection.ActionDefinition;
import io.syndesis.model.connection.ConfigurationProperty;
import io.syndesis.model.connection.Connection;
import io.syndesis.runtime.BaseITCase;

@ContextConfiguration(initializers = BaseITCase.TestConfigurationInitializer.class)
@SuppressWarnings({"PMD.TooManyStaticImports", "PMD.ExcessiveImports"})
public class DynamicActionSqlStoredITCase extends BaseITCase {

    private final String connectionId = UUID.randomUUID().toString();

    @BeforeClass
    public static void startMockIfNeeded() {
        if (wireMock==null || !wireMock.isRunning()) {
            wireMock = new WireMockRule(wireMockConfig().dynamicPort());
            wireMock.start();
        }
    }

    @Before
    public void setupConnection() {
        dataManager.create(new Connection.Builder().id(connectionId).connectorId("sql-stored-connector")
            .putConfiguredProperty("user", "sa").build());
    }

    @Before
    public void setupMocks() {
        WireMock.configureFor(wireMock.port());
        stubFor(WireMock
            .post(urlEqualTo(
                "/api/v1/connectors/sql-stored-connector/actions/io.syndesis:sql-stored-connector:latest"))//
            .withHeader("Accept", containing("application/json"))//
            .withRequestBody(
                    equalToJson("{\"template\":null,\"procedureName\":null,\"user\":\"sa\"}"))
            .willReturn(aResponse()//
                .withStatus(200)//
                .withHeader("Content-Type", "application/json")//
                .withBody(read("/verifier-response-sql-stored-list.json"))));

        stubFor(WireMock
            .post(urlEqualTo(
                "/api/v1/connectors/sql-stored-connector/actions/io.syndesis:sql-stored-connector:latest"))//
            .withHeader("Accept", equalTo("application/json"))//
            .withRequestBody(
                    equalToJson("{\"template\":null,\"user\":\"sa\",\"procedureName\":\"DEMO_ADD\"}"))
            .willReturn(aResponse()//
                .withStatus(200)//
                .withHeader("Content-Type", "application/json")//
                .withBody(read("/verifier-response-sql-stored-demo-add-metadata.json"))));
    }

    @Test
    public void shouldOfferDynamicActionPropertySuggestions() {

        final HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);

        final ResponseEntity<ActionDefinition> firstResponse = http(HttpMethod.POST,
            "/api/v1/connections/" + connectionId + "/actions/io.syndesis:sql-stored-connector:latest", null,
            ActionDefinition.class, tokenRule.validToken(), headers, HttpStatus.OK);

        ConfigurationProperty procedureNames = firstResponse.getBody().getPropertyDefinitionSteps().iterator().next().getProperties().get("procedureName");
        assertThat(procedureNames.getEnum()).hasSize(2);
        assertThat(procedureNames.getEnum().iterator().next().getLabel()).startsWith("DEMO_ADD");

        final ResponseEntity<ActionDefinition> secondResponse = http(HttpMethod.POST,
                "/api/v1/connections/" + connectionId + "/actions/io.syndesis:sql-stored-connector:latest",
                Collections.singletonMap("procedureName", "DEMO_ADD"),
                ActionDefinition.class, tokenRule.validToken(), headers, HttpStatus.OK);

        final Map<String, ConfigurationProperty> secondRequestProperties = secondResponse.getBody().getPropertyDefinitionSteps().get(0).getProperties();
        assertThat(secondRequestProperties.get("template").getDefaultValue()).isEqualTo("DEMO_ADD(INTEGER ${body[A]}, INTEGER ${body[B]}, INTEGER ${body[C]})");
    }

    private static String read(final String path) {
        try {
            return String.join("",
                Files.readAllLines(Paths.get(DynamicActionSqlStoredITCase.class.getResource(path).toURI())));
        } catch (IOException | URISyntaxException e) {
            throw new IllegalArgumentException("Unable to read from path: " + path, e);
        }
    }
}
