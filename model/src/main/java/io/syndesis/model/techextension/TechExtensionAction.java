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
package io.syndesis.model.techextension;

import java.io.Serializable;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

import io.syndesis.model.Kind;
import io.syndesis.model.WithId;

import org.immutables.value.Value;

@Value.Immutable
@JsonDeserialize(builder = TechExtensionAction.Builder.class)
@SuppressWarnings("immutables")
public interface TechExtensionAction extends WithId<TechExtensionAction>, Serializable {

    @Override
    default Kind getKind() {
        return Kind.TechExtensionAction;
    }

    // id field comes from the superclass

    String getName();
    String getActionType();
    Optional<String> getDescription();
    Optional<List<String>> getTags();
    Optional<TechExtensionDescriptor> getDescriptor();

    class Builder extends ImmutableTechExtensionAction.Builder {
    }

}
