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
package io.syndesis.rest.v1.handler.integration;

import io.syndesis.model.integration.Integration;
import io.syndesis.model.integration.IntegrationRevision;
import io.syndesis.project.converter.GenerateProjectRequest;
import io.syndesis.project.converter.ProjectGenerator;
import io.swagger.annotations.Api;
import org.springframework.stereotype.Component;

import javax.ws.rs.Consumes;
import javax.ws.rs.NotFoundException;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import java.io.IOException;

@Path("/integration-support")
@Api(value = "integration-support")
@Component
public class IntegrationSupportHandler {

    private final ProjectGenerator projectConverter;

    public IntegrationSupportHandler( ProjectGenerator projectConverter) {
        this.projectConverter = projectConverter;
    }

    @POST
    @Path("/generate/pom.xml")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_OCTET_STREAM)
    public byte[] projectPom(Integration integration) throws IOException {

        IntegrationRevision revision = integration.getDeployedRevision()
            .orElse(integration
                .getDraftRevision()
                .orElseThrow(() -> new NotFoundException()));

        return projectConverter.generatePom(new GenerateProjectRequest.Builder()
            .id(integration.getId())
            .name(integration.getName())
            .description(integration.getDescription())
            .spec(revision.getSpec()).build());
    }

}
