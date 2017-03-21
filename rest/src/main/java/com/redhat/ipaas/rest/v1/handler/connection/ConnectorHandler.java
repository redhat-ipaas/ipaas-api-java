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
package com.redhat.ipaas.rest.v1.handler.connection;

import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;

import com.redhat.ipaas.dao.manager.DataManager;
import com.redhat.ipaas.model.Kind;
import com.redhat.ipaas.model.connection.Connector;
import com.redhat.ipaas.rest.v1.dto.VConnectionConfiguration;
import com.redhat.ipaas.rest.v1.handler.BaseHandler;
import com.redhat.ipaas.rest.v1.operations.Getter;
import com.redhat.ipaas.rest.v1.operations.Lister;
import com.redhat.ipaas.verifier.Verifier;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiParam;


@Path("/connectors")
@Api(value = "connectors")
@org.springframework.stereotype.Component
public class ConnectorHandler extends BaseHandler implements Lister<Connector>, Getter<Connector> {

    // Verify connector without connections
    private Verifier verifier;

    public ConnectorHandler(DataManager dataMgr, Verifier verifier) {
        super(dataMgr);
        this.verifier = verifier;
    }

    @Override
    public Kind resourceKind() {
        return Kind.Connector;
    }

    @Path("/{id}/actions")
    public ConnectorActionHandler getActions(@PathParam("id") String connectorId) {
        return new ConnectorActionHandler(getDataManager(), connectorId);
    }

    @POST
    @Produces(MediaType.APPLICATION_JSON)
    @Path("/verifier/{scope}")
    public Verifier.Result verifyConnectionParameters(@ApiParam(required = true) VConnectionConfiguration connectionConfig,
                                                      @PathParam("scope") @ApiParam(required = true) Verifier.Scope scope) {
        return verifier.verify(connectionConfig.getConnectorId(), scope, connectionConfig.getOptions());
    }
}
