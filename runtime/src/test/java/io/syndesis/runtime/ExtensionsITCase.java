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

import io.syndesis.model.ListResult;
import io.syndesis.model.ResourceIdentifier;
import io.syndesis.model.extension.Extension;
import io.syndesis.model.integration.Integration;
import io.syndesis.model.integration.SimpleStep;
import io.syndesis.rest.v1.operations.Violation;
import org.apache.commons.io.IOUtils;
import org.junit.Test;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertNotNull;

public class ExtensionsITCase extends BaseITCase {

    @Test
    public void basicConnectivityTest() {
        ResponseEntity<ListResult<Extension>> exts = get("/api/v1beta1/extensions",
            new ParameterizedTypeReference<ListResult<Extension>>() {}, tokenRule.validToken(), HttpStatus.OK);
        assertThat(exts.getBody().getTotalCount()).isGreaterThanOrEqualTo(0);
    }

    @Test
    public void createNewExtensionListDeleteTest() throws IOException {
        // POST
        ResponseEntity<Extension> created = post("/api/v1beta1/extensions", multipartBody(extensionData(1)),
            Extension.class, tokenRule.validToken(), HttpStatus.OK, multipartHeaders());

        assertThat(created.getBody().getId()).isNotEmpty();
        assertThat(created.getBody().getName()).isNotBlank();

        assertThat(created.getBody().getId()).isPresent();
        String id = created.getBody().getId().get();

        // GET
        ResponseEntity<Extension> got = get("/api/v1beta1/extensions/" + id, Extension.class,
            tokenRule.validToken(), HttpStatus.OK);

        assertThat(got.getBody().getName()).isEqualTo(created.getBody().getName());

        // LIST
        ResponseEntity<ListResult<Extension>> list = get("/api/v1beta1/extensions",
            new ParameterizedTypeReference<ListResult<Extension>>() {}, tokenRule.validToken(), HttpStatus.OK);

        assertThat(list.getBody().getTotalCount()).as("extensions size").isGreaterThan(0);

        // DELETE
        delete("/api/v1beta1/extensions/" + id, Void.class, tokenRule.validToken(), HttpStatus.NO_CONTENT);

        // RE-GET
        ResponseEntity<Extension> regot = get("/api/v1beta1/extensions/" + id, Extension.class,
            tokenRule.validToken(), HttpStatus.OK);

        assertThat(regot.getBody().getStatus()).contains(Extension.Status.Deleted);
    }

    @Test
    public void testUpdateExtension() throws IOException {
        ResponseEntity<Extension> created = post("/api/v1beta1/extensions", multipartBody(extensionData(1)),
            Extension.class, tokenRule.validToken(), HttpStatus.OK, multipartHeaders());

        assertThat(created.getBody().getId()).isPresent();
        String id = created.getBody().getId().get();

        ResponseEntity<Extension> updated = post("/api/v1beta1/extensions?updatedId=" + id, multipartBody(extensionData(1)),
            Extension.class, tokenRule.validToken(), HttpStatus.OK, multipartHeaders());

        assertThat(updated.getBody().getId()).isPresent();
    }

    @Test
    public void testUpdateExtensionFailure() throws IOException {
        ResponseEntity<Extension> created = post("/api/v1beta1/extensions", multipartBody(extensionData(1)),
            Extension.class, tokenRule.validToken(), HttpStatus.OK, multipartHeaders());

        assertThat(created.getBody().getId()).isPresent();
        String id = created.getBody().getId().get();

        // Using wrong extensionId="extension2"
        post("/api/v1beta1/extensions?updatedId=" + id, multipartBody(extensionData(2)),
            Void.class, tokenRule.validToken(), HttpStatus.BAD_REQUEST, multipartHeaders());
    }

    @Test
    public void testValidateExtension() throws IOException {
        // Create one extension
        ResponseEntity<Extension> created1 = post("/api/v1beta1/extensions", multipartBody(extensionData(1)),
            Extension.class, tokenRule.validToken(), HttpStatus.OK, multipartHeaders());

        assertThat(created1.getBody().getId()).isPresent();
        String id1 = created1.getBody().getId().get();

        // Install it
        post("/api/v1beta1/extensions/" + id1 + "/install", null, Void.class,
            tokenRule.validToken(), HttpStatus.NO_CONTENT);

        // Create another extension with same extension-id
        ResponseEntity<Extension> created2 = post("/api/v1beta1/extensions", multipartBody(extensionData(1)),
            Extension.class, tokenRule.validToken(), HttpStatus.OK, multipartHeaders());

        assertThat(created2.getBody().getId()).isPresent();
        String id2 = created2.getBody().getId().get();

        // 200 status code: it's just a warning
        ResponseEntity<Set<Violation>> violations = post("/api/v1beta1/extensions/" + id2 + "/validation",
            null, new ParameterizedTypeReference<Set<Violation>>() {}, tokenRule.validToken(), HttpStatus.OK);

        assertThat(violations.getBody().size()).isGreaterThan(0);
        assertThat(violations.getBody())
            .hasOnlyOneElementSatisfying(v -> assertThat(v.message()).startsWith("The tech extension already exists"));
    }

    @Test
    public void testExtensionActivation() throws IOException {
        // Create one extension
        ResponseEntity<Extension> created1 = post("/api/v1beta1/extensions", multipartBody(extensionData(1)),
            Extension.class, tokenRule.validToken(), HttpStatus.OK, multipartHeaders());

        assertThat(created1.getBody().getId()).isPresent();
        String id1 = created1.getBody().getId().get();

        // Create another extension (id-2)
        ResponseEntity<Extension> created2 = post("/api/v1beta1/extensions", multipartBody(extensionData(2)),
            Extension.class, tokenRule.validToken(), HttpStatus.OK, multipartHeaders());

        assertThat(created2.getBody().getId()).isPresent();
        String id2 = created2.getBody().getId().get();

        // Install them
        post("/api/v1beta1/extensions/" + id1 + "/install", null, Void.class,
            tokenRule.validToken(), HttpStatus.NO_CONTENT);

        post("/api/v1beta1/extensions/" + id2 + "/install", null, Void.class,
            tokenRule.validToken(), HttpStatus.NO_CONTENT);

        // Check status 1
        ResponseEntity<Extension> got1 = get("/api/v1beta1/extensions/" + id1, Extension.class,
            tokenRule.validToken(), HttpStatus.OK);
        assertThat(got1.getBody().getStatus()).contains(Extension.Status.Installed);

        // Check status 2
        ResponseEntity<Extension> got2 = get("/api/v1beta1/extensions/" + id2, Extension.class,
            tokenRule.validToken(), HttpStatus.OK);
        assertThat(got2.getBody().getStatus()).contains(Extension.Status.Installed);

        // Create another extension with same extension-id
        ResponseEntity<Extension> createdCopy1 = post("/api/v1beta1/extensions", multipartBody(extensionData(1)),
            Extension.class, tokenRule.validToken(), HttpStatus.OK, multipartHeaders());

        assertThat(createdCopy1.getBody().getId()).isPresent();
        String idCopy1 = createdCopy1.getBody().getId().get();

        // Install copy
        post("/api/v1beta1/extensions/" + idCopy1 + "/install", null, Void.class,
            tokenRule.validToken(), HttpStatus.NO_CONTENT);

        // Check previous extension is deleted
        ResponseEntity<Extension> reGot1 = get("/api/v1beta1/extensions/" + id1, Extension.class,
            tokenRule.validToken(), HttpStatus.OK);
        assertThat(reGot1.getBody().getStatus()).contains(Extension.Status.Deleted);

        // Check new extension is installed
        ResponseEntity<Extension> gotCopy1 = get("/api/v1beta1/extensions/" + idCopy1, Extension.class,
            tokenRule.validToken(), HttpStatus.OK);
        assertThat(gotCopy1.getBody().getStatus()).contains(Extension.Status.Installed);

        // Check 2nd extension is unchanged
        ResponseEntity<Extension> reGot2 = get("/api/v1beta1/extensions/" + id2, Extension.class,
            tokenRule.validToken(), HttpStatus.OK);
        assertThat(reGot2.getBody().getStatus()).contains(Extension.Status.Installed);
    }

    @Test
    public void testIntegrationsUsingExtension() throws IOException {
        // Create one extension
        ResponseEntity<Extension> created = post("/api/v1beta1/extensions", multipartBody(extensionData(1)),
            Extension.class, tokenRule.validToken(), HttpStatus.OK, multipartHeaders());

        assertThat(created.getBody().getId()).isPresent();
        String id = created.getBody().getId().get();

        // Get extensions using it
        ResponseEntity<Set<ResourceIdentifier>> got1 = get("/api/v1beta1/extensions/" + id + "/integrations",
            new ParameterizedTypeReference<Set<ResourceIdentifier>>() {}, tokenRule.validToken(), HttpStatus.OK);

        assertThat(got1.getBody()).isEmpty();

        // Create a active integration that uses the extension
        dataManager.create(new Integration.Builder()
            .id("integration-extension-1")
            .desiredStatus(Integration.Status.Activated)
            .currentStatus(Integration.Status.Activated)
            .createdDate(new Date())
            .lastUpdated(new Date())
            .userId("important user")
            .steps(Collections.singletonList(
                new SimpleStep.Builder()
                .id("step1")
                .name("step1")
                .stepKind("extension")
                .extension(
                    new Extension.Builder()
                    .createFrom(created.getBody())
                    .build())
                .build()))
            .build());

        // Create a inactive integration that uses the extension
        dataManager.create(new Integration.Builder()
            .id("integration-extension-2")
            .desiredStatus(Integration.Status.Deleted)
            .currentStatus(Integration.Status.Activated)
            .createdDate(new Date())
            .lastUpdated(new Date())
            .userId("important user")
            .steps(Collections.singletonList(
                new SimpleStep.Builder()
                    .id("step2")
                    .name("step2")
                    .stepKind("extension")
                    .extension(
                        new Extension.Builder()
                            .createFrom(created.getBody())
                            .build())
                    .build()))
            .build());

        // Get extensions using it
        ResponseEntity<Set<ResourceIdentifier>> got2 = get("/api/v1beta1/extensions/" + id + "/integrations",
            new ParameterizedTypeReference<Set<ResourceIdentifier>>() {}, tokenRule.validToken(), HttpStatus.OK);

        assertThat(got2.getBody().size()).isEqualTo(1);
        assertThat(got2.getBody()).allMatch(ri -> ri.getId().isPresent() && ri.getId().get().equals("integration-extension-1"));

        dataManager.delete(Integration.class, "integration-extension-1");
        dataManager.delete(Integration.class, "integration-extension-2");
    }


    // ===========================================================

    private byte[] extensionData(int prg) throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/extension" + prg + ".bin")) {
            // they are jar file
            assertNotNull(in);
            return IOUtils.toByteArray(in);
        }
    }

    private HttpHeaders multipartHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        return headers;
    }

    private MultiValueMap<String, Object> multipartBody(byte[] data) {
        LinkedMultiValueMap<String, Object> multipartData = new LinkedMultiValueMap<>();
        multipartData.add("file", new InputStreamResource(new ByteArrayInputStream(data)));
        return multipartData;
    }

}
