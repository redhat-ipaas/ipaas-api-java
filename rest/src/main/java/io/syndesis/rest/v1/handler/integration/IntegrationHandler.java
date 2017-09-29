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

import java.math.BigInteger;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import javax.persistence.EntityNotFoundException;
import javax.validation.Validator;
import javax.validation.groups.ConvertGroup;
import javax.validation.groups.Default;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.UriInfo;

import io.swagger.annotations.Api;
import io.syndesis.core.Tokens;
import io.syndesis.dao.manager.DataManager;
import io.syndesis.inspector.Inspectors;
import io.syndesis.model.Kind;
import io.syndesis.model.ListResult;
import io.syndesis.model.connection.DataShape;
import io.syndesis.model.filter.FilterOptions;
import io.syndesis.model.filter.Op;
import io.syndesis.model.integration.Integration;
import io.syndesis.model.integration.Integration.Status;
import io.syndesis.model.integration.IntegrationRevision;
import io.syndesis.model.integration.IntegrationRevisionState;
import io.syndesis.model.validation.AllValidations;
import io.syndesis.rest.util.PaginationFilter;
import io.syndesis.rest.util.ReflectiveSorter;
import io.syndesis.rest.v1.handler.BaseHandler;
import io.syndesis.rest.v1.operations.Creator;
import io.syndesis.rest.v1.operations.Deleter;
import io.syndesis.rest.v1.operations.Getter;
import io.syndesis.rest.v1.operations.Lister;
import io.syndesis.rest.v1.operations.PaginationOptionsFromQueryParams;
import io.syndesis.rest.v1.operations.SortOptionsFromQueryParams;
import io.syndesis.rest.v1.operations.Updater;
import io.syndesis.rest.v1.operations.Validating;

import org.springframework.stereotype.Component;

@Path("/integrations")
@Api(value = "integrations")
@Component
public class IntegrationHandler extends BaseHandler
    implements Lister<Integration>, Getter<Integration>, Creator<Integration>, Deleter<Integration>, Updater<Integration>, Validating<Integration> {

    private final Inspectors inspectors;

    private final Validator validator;

    public IntegrationHandler(final DataManager dataMgr, final Validator validator, final Inspectors inspectors) {
        super(dataMgr);
        this.validator = validator;
        this.inspectors = inspectors;
    }

    @Override
    public Kind resourceKind() {
        return Kind.Integration;
    }

    @Override
    public Integration get(String id) {
        Integration integration = Getter.super.get(id);

        if (Status.Deleted.equals(integration.getCurrentStatus().get()) ||
            Status.Deleted.equals(integration.getDesiredStatus().get())) {
            //Not sure if we need to do that for both current and desired status,
            //but If we don't do include the desired state, IntegrationITCase is not going to pass anytime soon. Why?
            //Cause that test, is using NoopHandlerProvider, so that means no controllers.
            throw new EntityNotFoundException();
        }

        //fudging the timesUsed for now
        Optional<Status> currentStatus = integration.getCurrentStatus();
        if (currentStatus.isPresent() && currentStatus.get() == Integration.Status.Activated) {
            return new Integration.Builder()
                    .createFrom(integration)
                    .timesUsed(BigInteger.valueOf(new Date().getTime()/1000000))
                    .build();
        }

        return integration;
    }

    @Override
    public ListResult<Integration> list(UriInfo uriInfo) {
        Class<Integration> clazz = resourceKind().getModelClass();
        return getDataManager().fetchAll(
            Integration.class,
            new DeletedFilter(),
            new ReflectiveSorter<>(clazz, new SortOptionsFromQueryParams(uriInfo)),
            new PaginationFilter<>(new PaginationOptionsFromQueryParams(uriInfo))
        );
    }

    @Override
    public Integration create(@ConvertGroup(from = Default.class, to = AllValidations.class) final Integration integration) {
        Date rightNow = new Date();

        IntegrationRevision revision = IntegrationRevision
            .createNewRevision(integration)
            .withCurrentState(IntegrationRevisionState.Draft);

        Integration updatedIntegration = new Integration.Builder()
            .createFrom(integration)
            .deployedRevisionId(revision.getVersion())
            .addRevision(revision)
            .token(Tokens.getAuthenticationToken())
            .userId(Tokens.getUsername())
            .statusMessage(Optional.empty())
            .lastUpdated(rightNow)
            .createdDate(rightNow)
            .currentStatus(determineCurrentStatus(integration))
            .build();

        return Creator.super.create(updatedIntegration);
    }

    @Override
    public void update(String id, @ConvertGroup(from = Default.class, to = AllValidations.class) Integration integration) {
        Integration existing = Getter.super.get(id);

        Status currentStatus = determineCurrentStatus(integration);
        IntegrationRevision currentRevision = IntegrationRevision.deployedRevision(existing)
            .withCurrentState(IntegrationRevisionState.from(currentStatus))
            .withTargetState(IntegrationRevisionState.from(integration.getDesiredStatus().orElse(Status.Pending)));

        Integration updatedIntegration = new Integration.Builder()
            .createFrom(integration)
            .deployedRevisionId(existing.getDeployedRevisionId())
            .userId(Tokens.getUsername())
            .token(Tokens.getAuthenticationToken())
            .lastUpdated(new Date())
            .currentStatus(currentStatus)
            .addRevision(currentRevision)
            .build();

        Updater.super.update(id, updatedIntegration);
    }


    @Override
    public void delete(String id) {
         Integration existing = Getter.super.get(id);

        Status currentStatus = determineCurrentStatus(existing);
        IntegrationRevision currentRevision = IntegrationRevision.deployedRevision(existing)
            .withCurrentState(IntegrationRevisionState.from(currentStatus))
            .withTargetState(IntegrationRevisionState.from(Status.Deleted));

        Integration updatedIntegration = new Integration.Builder()
            .createFrom(existing)
            .deployedRevisionId(existing.getDeployedRevisionId())
            .token(Tokens.getAuthenticationToken())
            .lastUpdated(new Date())
            .desiredStatus(Status.Deleted)
            .addRevision(currentRevision)
            .build();

        Updater.super.update(id, updatedIntegration);
    }


    @Override
    public void delete(String id) {
         Integration existing = Getter.super.get(id);

        Integration updatedIntegration = new Integration.Builder()
            .createFrom(existing)
            .deployedRevisionId(existing.getDeployedRevisionId())
            .token(Tokens.getAuthenticationToken())
            .lastUpdated(new Date())
            .addRevision(IntegrationRevision.fromIntegration(existing))
            .addRevision(existing.getRevisions().toArray(new IntegrationRevision[existing.getRevisions().size()]))
            .desiredStatus(Status.Deleted)
            .build();

        Updater.super.update(id, updatedIntegration);
    }

    @POST
    @Produces(MediaType.APPLICATION_JSON)
    @Path(value = "/filters/options")
    public FilterOptions getFilterOptions(DataShape dataShape) {
        FilterOptions.Builder builder = new FilterOptions.Builder().addOp(Op.DEFAULT_OPTS);

        final List<String> paths = inspectors.getPaths(dataShape.getKind(), dataShape.getType(), dataShape.getSpecification(), dataShape.getExemplar());
        builder.paths(paths);
        return builder.build();
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @Path(value = "/filters/options")
    public FilterOptions getGlobalFilterOptions() {
        return new FilterOptions.Builder().addOp(Op.DEFAULT_OPTS).build();
    }

    // Determine the current status to 'pending' or 'draft' immediately depending on
    // the desired stated. This status will be later changed by the activation handlers.
    // This is not the best place to set but should be done by the IntegrationController
    // However because of how the Controller works (i.e. that any change to the integration
    // within the controller will trigger an event again), the initial status must be set
    // from the outside for the moment.
    private Integration.Status determineCurrentStatus(Integration integration) {
        Integration.Status desiredStatus = integration.getDesiredStatus().orElse(Integration.Status.Draft);
        return desiredStatus == Integration.Status.Draft ?
            Integration.Status.Draft :
            Integration.Status.Pending;
    }

    @Override
    public Validator getValidator() {
        return validator;
    }


    private static class DeletedFilter implements Function<ListResult<Integration>, ListResult<Integration>> {
        @Override
        public ListResult<Integration> apply(ListResult<Integration> list) {
            List<Integration> filtered = list.getItems().stream()
                    .filter(i -> !Status.Deleted.equals(i.getCurrentStatus().get()))
                    .filter(i -> !Status.Deleted.equals(i.getDesiredStatus().get()))
                    .collect(Collectors.toList());

            return new ListResult.Builder<Integration>()
                .totalCount(filtered.size())
                .addAllItems(filtered).build();
        }
    }
}
