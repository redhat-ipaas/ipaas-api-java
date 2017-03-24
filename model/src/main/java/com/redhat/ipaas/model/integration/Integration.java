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
package com.redhat.ipaas.model.integration;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.redhat.ipaas.model.Kind;
import com.redhat.ipaas.model.WithId;
import com.redhat.ipaas.model.WithName;
import com.redhat.ipaas.model.connection.Connection;
import com.redhat.ipaas.model.user.User;
import org.immutables.value.Value;

import java.io.Serializable;
import java.util.List;
import java.util.Optional;

@Value.Immutable
@JsonDeserialize(builder = Integration.Builder.class)
public interface Integration extends WithId<Integration>, WithName, Serializable {

    public static enum Type {Activated, Deactivated, Deleted, Draft};

    public static enum Phase {ACTIVATED, DEACTIVATED, DELETED, PENDING};

    /**
     *Required Labels
     */
    String LABEL_NAME = "ipaas.redhat.com/integration-modelName";

    /**
     * Optional Labels
     */

    //The integration id
    String LABEL_ID = "ipaas.redhat.com/integration-id";

    //The integration template id
    String LABEL_TEMPLATE_ID = "ipaas.redhat.com/template-id";

    @Override
    default Kind getKind() {
        return Kind.Integration;
    }

    Optional<String> getConfiguration();

    Optional<String> getIntegrationTemplateId();

    Optional<IntegrationTemplate> getIntegrationTemplate();

    Optional<String> getUserId();

    List<User> getUsers();

    List<Tag> getTags();

    Optional<List<Connection>> getConnections();

    Optional<List<Step>> getSteps();

    Optional<String> getDescription();

    Optional<String> getGitRepo();

    Optional<Type> getDesiredStatus();

    Optional<Phase> getCurrentStatus();

    Optional<String> getStatusMessage();

    @Override
    default Integration withId(String id) {
        return new Builder().createFrom(this).id(id).build();
    }

    class Builder extends ImmutableIntegration.Builder {
    }

}
