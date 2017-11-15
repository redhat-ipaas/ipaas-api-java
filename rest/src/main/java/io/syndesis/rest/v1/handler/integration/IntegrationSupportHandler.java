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

import io.swagger.annotations.Api;
import io.syndesis.core.Json;
import io.syndesis.dao.init.ModelData;
import io.syndesis.dao.manager.DataManager;
import io.syndesis.model.connection.Connection;
import io.syndesis.model.connection.Connector;
import io.syndesis.model.integration.Integration;
import io.syndesis.project.converter.ProjectGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.ws.rs.Consumes;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Path("/integration-support")
@Api(value = "integration-support")
@Component
public class IntegrationSupportHandler {

    public static final String EXPORT_MODEL_FILE_NAME = "model.json";
    private static final Logger LOG = LoggerFactory.getLogger(IntegrationSupportHandler.class);

    private final ProjectGenerator projectConverter;
    private final DataManager dataManager;

    public IntegrationSupportHandler(ProjectGenerator projectConverter, final DataManager dataManager) {
        this.projectConverter = projectConverter;
        this.dataManager = dataManager;
    }

    @POST
    @Path("/generate/pom.xml")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_OCTET_STREAM)
    public byte[] projectPom(Integration integration) throws IOException {
        return projectConverter.generatePom(integration);
    }


    @POST
    @Path("/import")
    public Response importIntegration(InputStream is) {
        try (ZipInputStream zis = new ZipInputStream(is)) {
            int imported = 0;
            while (true) {
                ZipEntry entry = zis.getNextEntry();
                if( entry == null ) {
                    break;
                }
                if (EXPORT_MODEL_FILE_NAME.equals(entry.getName())) {
                    @SuppressWarnings("rawtypes")
                    ModelData[] models = Json.mapper().readValue(new FilterInputStream(zis) {
                        @Override
                        public void close() throws IOException {
                            // We want to avoid closing zis
                        }
                    }, ModelData[].class);

                    imported += importModels(models);
                }
                zis.closeEntry();
            }
            if (imported==0) {
                LOG.info("Could not import integration: No integration data model found.");
                return Response.status(Response.Status.BAD_REQUEST).build();
            }
            return Response.status(Response.Status.NO_CONTENT).build();
        } catch (IOException e) {
            if (LOG.isInfoEnabled()) {
                LOG.info("Could not import integration: " + e, e);
            }
            return Response.status(Response.Status.BAD_REQUEST).build();
        }
    }

    public int importModels(ModelData<?>... models) throws IOException {
        int count = 0;
        for (ModelData<?> model : models) {
            switch (model.getKind()) {
                case Integration:

                    Integration integration = (Integration) model.getData();
                    // Do we need to create it?
                    if (dataManager.fetch(Integration.class, integration.getId().get()) == null) {
                        dataManager.create(integration);
                    } else {
                        dataManager.update(integration);
                    }
                    count ++;
                    break;

                case Connection:

                    // We only create connections, never update.
                    Connection connection = (Connection) model.getData();
                    if (dataManager.fetch(Connection.class, connection.getId().get()) == null) {
                        dataManager.create(connection);
                    }

                    break;
                case Connector:

                    // We only create connectors, never update.
                    Connector connector = (Connector) model.getData();
                    if (dataManager.fetch(Connector.class, connector.getId().get()) == null) {
                        dataManager.create(connector);
                    }
                    break;

                default:
                    if (LOG.isInfoEnabled()) {
                        LOG.info("Cannot import unsupported model kind: " + model.getKind());
                    }
                    break;

            }
        }
        return count;
    }


}
