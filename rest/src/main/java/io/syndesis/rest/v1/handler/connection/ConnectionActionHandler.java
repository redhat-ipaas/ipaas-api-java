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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import javax.persistence.EntityNotFoundException;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.Entity;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.MediaType;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import io.swagger.annotations.ApiResponse;
import io.swagger.annotations.ApiResponses;
import io.syndesis.model.connection.Action;
import io.syndesis.model.connection.ActionDefinition;
import io.syndesis.model.connection.ConfigurationProperty;
import io.syndesis.model.connection.Connection;
import io.syndesis.model.connection.Connector;
import io.syndesis.model.connection.DataShape;
import io.syndesis.model.connection.DynamicActionMetadata;
import io.syndesis.verifier.VerificationConfigurationProperties;

@Api(value = "actions")
public class ConnectionActionHandler {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final List<Action> actions;

    private final VerificationConfigurationProperties config;

    private final Connection connection;

    private final Connector connector;

    public ConnectionActionHandler(final Connection connection, final VerificationConfigurationProperties config) {
        this.connection = connection;
        this.config = config;

        final Optional<Connector> maybeConnector = connection.getConnector();
        connector = maybeConnector.orElseThrow(() -> new EntityNotFoundException(
            "Connection with id `" + connection.getId() + "` does not have a Connector defined"));

        actions = connector.getActions();
    }

    @POST
    @Path(value = "/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation("Retrieves enriched action definition, that is an action definition that has input/output data shapes and property enums defined with respect to the given action properties")
    @ApiResponses(@ApiResponse(code = 200, response = ActionDefinition.class,
        message = "A map of zero or more action property suggestions keyed by the property name"))
    public ActionDefinition enrichWithMetadata(
        @PathParam("id") @ApiParam(required = true,
            example = "io.syndesis:salesforce-create-or-update:latest") final String id,
        final Map<String, String> properties) {

        final Action action = actions.stream().filter(a -> a.idEquals(id)).findAny()
            .orElseThrow(() -> new EntityNotFoundException("Action with id: " + id));

        final ActionDefinition defaultDefinition = action.getDefinition();

        if (!action.getTags().contains("dynamic")) {
            return defaultDefinition;
        }

        final String connectorId = connector.getId().get();

        final Map<String, String> parameters = new HashMap<>(Optional.ofNullable(properties).orElseGet(HashMap::new));
        // put all action parameters with `null` values
        defaultDefinition.getPropertyDefinitionSteps()
            .forEach(step -> step.getProperties().forEach((k, v) -> parameters.putIfAbsent(k, null)));

        // lastly put all connection properties
        parameters.putAll(connection.getConfiguredProperties());

        final Client client = createClient();
        final WebTarget target = client
            .target(String.format("http://%s/api/v1/connectors/%s/actions/%s", config.getService(), connectorId, id));

        final ActionDefinition.Builder enriched = new ActionDefinition.Builder().createFrom(defaultDefinition);
        final DynamicActionMetadata dynamicActionMetadata = target.request(MediaType.APPLICATION_JSON)
            .post(Entity.entity(parameters, MediaType.APPLICATION_JSON), DynamicActionMetadata.class);

        final Map<String, List<DynamicActionMetadata.ActionPropertySuggestion>> actionPropertySuggestions = dynamicActionMetadata
            .properties();
        actionPropertySuggestions.forEach((k, vals) -> enriched.replaceConfigurationProperty(k,
            b -> b.addAllEnum(vals.stream().map(s -> ConfigurationProperty.PropertyValue.Builder.from(s))::iterator)));

        //Setting the defaultValue as suggested by the metadata
        if (properties != null && !properties.isEmpty()) {
            String propertyListName = properties.keySet().iterator().next();
            for (DynamicActionMetadata.ActionPropertySuggestion suggestion : actionPropertySuggestions.get(propertyListName)) {
                enriched.replaceConfigurationProperty(suggestion.displayValue(), v -> v.defaultValue(suggestion.value()));
            }
        }

        final Object input = dynamicActionMetadata.inputSchema();
        if (shouldEnrichDataShape(defaultDefinition.getInputDataShape(), input)) {
            enriched.inputDataShape(new DataShape.Builder().type(typeFrom(input)).kind("json-schema")
                .specification(specificationFrom(input)).build());
        }

        final Object output = dynamicActionMetadata.outputSchema();
        if (shouldEnrichDataShape(defaultDefinition.getOutputDataShape(), output)) {
            enriched.outputDataShape(new DataShape.Builder().type(typeFrom(output)).kind("json-schema")
                .specification(specificationFrom(output)).build());
        }

        return enriched.build();
    }

    /* default */ Client createClient() {
        return ClientBuilder.newClient();
    }

    /* default */ static boolean shouldEnrichDataShape(final Optional<DataShape> maybeExistingDataShape,
        final Object received) {
        if (maybeExistingDataShape.isPresent() && received != null) {
            final DataShape existingDataShape = maybeExistingDataShape.get();

            return "json-schema".equals(existingDataShape.getKind()) && existingDataShape.getSpecification() == null;
        }

        return false;
    }

    /* default */ static String specificationFrom(final Object obj) {
        try {
            return MAPPER.writeValueAsString(obj);
        } catch (final JsonProcessingException e) {
            throw new IllegalArgumentException("Unable to serialize JSON", e);
        }
    }

    /* default */ static String typeFrom(final Object obj) {
        final JsonNode node = (JsonNode) obj;

        return node.get("title").asText();
    }

}
