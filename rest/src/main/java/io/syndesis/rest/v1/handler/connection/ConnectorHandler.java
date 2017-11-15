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
package io.syndesis.rest.v1.handler.connection;

import java.util.List;
import java.util.Map;

import javax.persistence.EntityNotFoundException;
import javax.validation.constraints.NotNull;
import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiParam;
import io.syndesis.connector.generator.ConnectorGenerator;
import io.syndesis.credential.Credentials;
import io.syndesis.dao.manager.DataManager;
import io.syndesis.dao.manager.EncryptionComponent;
import io.syndesis.inspector.Inspectors;
import io.syndesis.model.Kind;
import io.syndesis.model.action.ConnectorAction;
import io.syndesis.model.connection.Connector;
import io.syndesis.model.connection.ConnectorTemplate;
import io.syndesis.model.filter.FilterOptions;
import io.syndesis.model.filter.Op;
import io.syndesis.rest.v1.handler.BaseHandler;
import io.syndesis.rest.v1.operations.Getter;
import io.syndesis.rest.v1.operations.Lister;
import io.syndesis.rest.v1.state.ClientSideState;
import io.syndesis.verifier.Verifier;

import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

@Path("/connectors")
@Api(value = "connectors")
@Component
public class ConnectorHandler extends BaseHandler implements Lister<Connector>, Getter<Connector> {

    private final Verifier verifier;
    private final Credentials credentials;
    private final Inspectors inspectors;
    private final ClientSideState state;
    private final ApplicationContext applicationContext;
    private final EncryptionComponent encryptionComponent;

    public ConnectorHandler(final DataManager dataMgr, final Verifier verifier, final Credentials credentials,
                            final Inspectors inspectors, final ClientSideState state, EncryptionComponent encryptionComponent,
                            final ApplicationContext applicationContext) {
        super(dataMgr);
        this.verifier = verifier;
        this.credentials = credentials;
        this.inspectors = inspectors;
        this.state = state;
        this.encryptionComponent = encryptionComponent;
        this.applicationContext = applicationContext;
    }

    @Override
    public Kind resourceKind() {
        return Kind.Connector;
    }

    @POST
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes("application/json")
    @Path("/{connector-template-id}")
    public Connector create(@NotNull @PathParam("connector-template-id") final String connectorTemplateId, final Connector template) {
        final ConnectorTemplate connectorTemplate = getDataManager().fetch(ConnectorTemplate.class, connectorTemplateId);

        if (connectorTemplate == null) {
            throw new EntityNotFoundException("Connector template: " + connectorTemplateId);
        }

        final ConnectorGenerator connectorGenerator = applicationContext.getBean(connectorTemplateId, ConnectorGenerator.class);

        final Connector connector = connectorGenerator.generate(connectorTemplate, template);

        return getDataManager().create(connector);
    }

    @Path("/{id}/actions")
    public ConnectorActionHandler getActions(@PathParam("id") final String connectorId) {
        return new ConnectorActionHandler(getDataManager(), connectorId);
    }

    @POST
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    @Path("/{id}/verifier")
    public List<Verifier.Result> verifyConnectionParameters(@NotNull @PathParam("id") final String connectorId,
        final Map<String, String> props) {
        return verifier.verify(connectorId, encryptionComponent.decrypt(props));
    }

    @Path("/{id}/credentials")
    public ConnectorCredentialHandler credentials(@NotNull final @PathParam("id") String connectorId) {
        return new ConnectorCredentialHandler(credentials, state, connectorId);
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @Path(value = "/{connectorId}/actions/{actionId}/filters/options")
    public FilterOptions getFilterOptions(@PathParam("connectorId") @ApiParam(required = true) String connectorId,
        @PathParam("actionId") @ApiParam(required = true) String actionId) {
        FilterOptions.Builder builder = new FilterOptions.Builder().addOp(Op.DEFAULT_OPTS);
        Connector connector = getDataManager().fetch(Connector.class, connectorId);

        if (connector == null) {
            return builder.build();
        }

        connector.actionById(actionId)
            .filter(ConnectorAction.class::isInstance)
            .map(ConnectorAction.class::cast)
            .ifPresent(action -> {
                action.getOutputDataShape().ifPresent(dataShape -> {
                    final List<String> paths = inspectors.getPaths(dataShape.getKind(), dataShape.getType(), dataShape.getSpecification(), dataShape.getExemplar());
                    builder.addAllPaths(paths);
                }
            );
        });
        return builder.build();
    }
}
