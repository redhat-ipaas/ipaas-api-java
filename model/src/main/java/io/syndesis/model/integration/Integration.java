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
package io.syndesis.model.integration;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import io.syndesis.model.Kind;
import io.syndesis.model.WithId;
import io.syndesis.model.WithName;
import io.syndesis.model.WithTags;
import io.syndesis.model.user.User;
import io.syndesis.model.validation.UniqueProperty;
import io.syndesis.model.validation.UniquenessRequired;

import org.immutables.value.Value;

import java.io.Serializable;
import java.math.BigInteger;
import java.util.Date;
import java.util.List;
import java.util.Optional;

@Value.Immutable
@JsonDeserialize(builder = Integration.Builder.class)
@UniqueProperty(value = "name", groups = UniquenessRequired.class)
public interface Integration extends WithId<Integration>, WithTags, WithName, Serializable {

    @Override
    default Kind getKind() {
        return Kind.Integration;
    }

    Optional<String> getDescription();

    /**
     * The list of versioned revisions.
     * The items in this list should be versioned and are not meant to be mutated.
     * @return
     */
    List<IntegrationRevision> getRevisions();

    Optional<IntegrationRevision> getDraftRevision();

    Optional<Integer> getDeployedRevisionNumber();

    @JsonIgnore
    default Optional<IntegrationRevision> getDeployedRevision() {
        return getDeployedRevisionNumber().map(i -> getRevisions()
            .stream()
            .filter(r -> r.getVersion().isPresent() && i.equals(r.getVersion().get()))
            .findFirst()
            .orElse(null));
    }

    IntegrationSpec getSpec();

    Optional<String> getUserId();

    Optional<String> getToken();

    Optional<String> getGitRepo();

    List<User> getUsers();

    Optional<List<String>> getStepsDone();

    Optional<Date> getLastUpdated();

    Optional<Date> getCreatedDate();

    Optional<BigInteger> getTimesUsed();

    @Override
    default Integration withId(String id) {
        return new Builder().createFrom(this).id(id).build();
    }

    class Builder extends ImmutableIntegration.Builder {
    }

}
