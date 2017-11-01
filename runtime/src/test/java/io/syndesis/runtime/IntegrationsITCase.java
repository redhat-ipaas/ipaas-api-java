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
package io.syndesis.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import io.syndesis.model.integration.Integration;
import io.syndesis.rest.v1.handler.exception.RestError;
import io.syndesis.rest.v1.operations.Violation;
import org.junit.Before;
import org.junit.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

public class IntegrationsITCase extends BaseITCase {

    @SuppressWarnings({"unchecked", "rawtypes"})
    private final static Class<List<Violation>> RESPONSE_TYPE = (Class) List.class;

    @Override
    @Before
    public void clearDB() {
        super.clearDB();
    }

    @Test
    public void integrationsListForbidden() {
        ResponseEntity<JsonNode> response = restTemplate().getForEntity("/api/v1/integrations", JsonNode.class);
        assertThat(response.getStatusCode()).as("integrations list status code").isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    public void invalidSortField() {
        ResponseEntity<RestError> response = get("/api/v1/integrations?sort=invalid_field", RestError.class, HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().getErrorCode()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(response.getBody().getUserMsg()).isEqualTo("Please check your sorting arguments");
        assertThat(response.getBody().getDeveloperMsg()).startsWith("Illegal Argument on Call");
    }

    @Test
    public void createAndGetIntegration() throws IOException {

        // Verify that the integration does not exist.
        get("/api/v1/integrations/2001", RestError.class,
            tokenRule.validToken(), HttpStatus.NOT_FOUND);

        // Create the integration.
        Integration integration = new Integration.Builder()
            .id("2001")
            .name("test")
            .desiredStatus(Integration.Status.Draft)
            .currentStatus(Integration.Status.Draft)
            .build();
        post("/api/v1/integrations", integration, Integration.class);

        // Validate we can now fetch it.
        ResponseEntity<Integration> result = get("/api/v1/integrations/2001", Integration.class);
        assertThat(result.getBody().getName()).as("name").isEqualTo("test");

        // Create another integration.
        integration = new Integration.Builder()
            .id("2002")
            .name("test2")
            .desiredStatus(Integration.Status.Draft)
            .currentStatus(Integration.Status.Draft)
            .build();
        post("/api/v1/integrations", integration, Integration.class);

        // Check the we can list the integrations.
        ResponseEntity<IntegrationListResult> list = get("/api/v1/integrations", IntegrationListResult.class);

        assertThat(list.getBody().getTotalCount()).as("total count").isEqualTo(2);
        assertThat(list.getBody().getItems()).as("items").hasSize(2);

        // We should be able to export the integration too.
        ResponseEntity<byte[]> exportData = get("/api/v1/integrations/2001/export.zip", byte[].class);
        assertThat(exportData.getBody()).isNotNull();

        // Lets delete it
        delete("/api/v1/integrations/2001");

        // We should not be able to fetch it again..
        get("/api/v1/integrations/2001", RestError.class,
            tokenRule.validToken(), HttpStatus.NOT_FOUND);

        // The list size should get smaller
        list = get("/api/v1/integrations", IntegrationListResult.class);
        assertThat(list.getBody().getTotalCount()).as("total count").isEqualTo(1);
        assertThat(list.getBody().getItems()).as("items").hasSize(1);

        // Lets now re-import the integration:
        ResponseEntity<Object> importRespons = post("/api/v1/integration-support/import", exportData.getBody(), null);

    }

        @Test
    public void shouldDetermineValidityForInvalidIntegrations() {
        dataManager.create(new Integration.Builder().name("Existing integration").build());

        final Integration integration = new Integration.Builder().name("Existing integration").build();

        final ResponseEntity<List<Violation>> got = post("/api/v1/integrations/validation", integration, RESPONSE_TYPE,
            tokenRule.validToken(), HttpStatus.BAD_REQUEST);

        assertThat(got.getBody()).hasSize(1);
    }

    @Test
    public void shouldDetermineValidityForValidIntegrations() {
        final Integration integration = new Integration.Builder().name("Test integration").desiredStatus(Integration.Status.Draft).build();

        final ResponseEntity<List<Violation>> got = post("/api/v1/integrations/validation", integration, RESPONSE_TYPE,
            tokenRule.validToken(), HttpStatus.NO_CONTENT);

        assertThat(got.getBody()).isNull();
    }

    public static class IntegrationListResult {
        public int totalCount;
        public ArrayList<Integration> items;

        public int getTotalCount() {
            return totalCount;
        }

        public ArrayList<Integration> getItems() {
            return items;
        }
    }

}
