/*
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

package io.syndesis.project.converter.visitor;

import java.util.Iterator;
import java.util.Optional;
import java.util.Queue;
import java.util.function.Function;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import io.syndesis.model.integration.Step;
import org.immutables.value.Value;

@Value.Immutable
@JsonDeserialize(builder = StepVisitorContext.Builder.class)
public interface StepVisitorContext extends Iterator<StepVisitorContext> {

    int getIndex();

    Step getStep();

    Queue<Step> getRemaining();

    Function<Step, Optional<String>> getConnectorIdSupplier();

    @Override
    default boolean hasNext() {
        return !getRemaining().isEmpty();
    }

    @Override
    default StepVisitorContext next() {
        final int index = getIndex();
        final Queue<Step> remaining = getRemaining();
        final Step next = remaining.remove();

        return new StepVisitorContext.Builder()
            .createFrom(this)
            .index(index + 1)
            .step(next)
            .remaining(remaining)
            .build();
    }

    class Builder extends ImmutableStepVisitorContext.Builder {
    }
}
