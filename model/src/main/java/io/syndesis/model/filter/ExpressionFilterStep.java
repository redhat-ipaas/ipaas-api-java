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
package io.syndesis.model.filter;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import io.syndesis.model.Kind;
import org.immutables.value.Value;

@Value.Immutable
@JsonDeserialize(builder = ExpressionFilterStep.Builder.class)
@JsonIgnoreProperties("filterExpression")
@SuppressWarnings("immutables")
public interface ExpressionFilterStep extends FilterStep {

    String STEP_KIND = "filter";

    /**
     * Filter in the simple expression language. This can be overwritten, but fectched
     * by default from the configured properties.
     */
    @Override
    default String getFilterExpression() {
        return getConfiguredProperties().get("filter");
    }

    class Builder extends ImmutableExpressionFilterStep.Builder {
    }

    @Override
    @Value.Default
    default String getStepKind() {
        return STEP_KIND;
    }

    @Override
    @Value.Default
    default Kind getKind() {
        return Kind.Step;
    }

}
