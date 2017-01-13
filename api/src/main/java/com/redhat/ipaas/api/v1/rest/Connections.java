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
package com.redhat.ipaas.api.v1.rest;

import com.redhat.ipaas.api.v1.model.Connection;
import io.swagger.annotations.Api;

import javax.ws.rs.Path;

@Path("/connections")
@Api(value = "connections")
public class Connections extends BaseHandler implements Lister<Connection>, Getter<Connection>, Creator<Connection>, Deleter<Connection>, Updater<Connection> {

    @Override
    public Class<Connection> resourceClass() {
        return Connection.class;
    }

    @Override
    public String resourceKind() {
        return Connection.KIND;
    }

}
