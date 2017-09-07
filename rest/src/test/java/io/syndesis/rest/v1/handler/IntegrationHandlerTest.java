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
package io.syndesis.rest.v1.handler;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.validation.Validator;

import io.syndesis.dao.manager.DataManager;
import io.syndesis.inspector.ClassInspector;
import io.syndesis.model.connection.Action;
import io.syndesis.model.connection.DataShape;
import io.syndesis.model.connection.DataShapeKinds;
import io.syndesis.model.filter.FilterOptions;
import io.syndesis.model.integration.Integration;
import io.syndesis.model.integration.IntegrationRevision;
import io.syndesis.model.integration.IntegrationSpec;
import io.syndesis.model.integration.SimpleStep;
import io.syndesis.rest.v1.handler.integration.IntegrationHandler;
import org.junit.Before;
import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * @author roland
 * @since 29.08.17
 */
public class IntegrationHandlerTest {


    private DataManager manager;
    private IntegrationHandler handler;
    private ClassInspector inspector;

    @Before
    public void setUp() {
        Validator validator = mock(Validator.class);
        manager = mock(DataManager.class);
        inspector = mock(ClassInspector.class);
        handler = new IntegrationHandler(manager, validator, inspector);
    }

    @Test
    public void filterOptionsSimple() {
        Integration integration = createIntegrationFromDataShapes(dataShape(DataShapeKinds.JAVA, "twitter4j.Status"), null, dataShape(DataShapeKinds.NONE));
        when(manager.fetch(Integration.class, integration.getId().get())).thenReturn(integration);
        when(inspector.getPaths("twitter4j.Status")).thenReturn(Arrays.asList("paramA", "paramB"));

        FilterOptions options = handler.getFilterOptions(integration.getId().get(), 1, -1);
        assertThat(options.getPaths()).hasSize(2).contains("paramA","paramB");
    }

    @Test
    public void filterOptionsMultipleOutputShapes() {
        Integration integration =
            createIntegrationFromDataShapes(dataShape(DataShapeKinds.JAVA, "blubber.bla"),null, dataShape(DataShapeKinds.JAVA, "twitter4j.Status"));
        when(manager.fetch(Integration.class, integration.getId().get())).thenReturn(integration);
        when(inspector.getPaths("twitter4j.Status")).thenReturn(Arrays.asList("paramA", "paramB"));
        when(inspector.getPaths("blubber.bla")).thenReturn(Arrays.asList("paramY", "paramZ"));


        assertThat(handler.getFilterOptions(integration.getId().get(), 1,-1).getPaths())
            .hasSize(2).contains("paramA", "paramB");

        assertThat(handler.getFilterOptions(integration.getId().get(), 1, 1).getPaths())
            .hasSize(2).contains("paramY","paramZ");
    }

    @Test
    public void filterOptionsNoOutputShape() {
        Integration integration =
            createIntegrationFromDataShapes(dataShape(DataShapeKinds.NONE), null);

        when(manager.fetch(Integration.class, integration.getId().get())).thenReturn(integration);
        FilterOptions options = handler.getFilterOptions(integration.getId().get(), 1, -1);
        assertThat(options.getPaths()).isEmpty();
    }

    private Integration createIntegrationFromDataShapes(DataShape... dataShapes) {
       return new Integration.Builder()
                .id("someid")
                .revisions(Arrays.<IntegrationRevision>asList(new IntegrationRevision.Builder()
                    .version(1)
                    .spec(new IntegrationSpec.Builder().steps(steps(dataShapes)).build())
                    .build()))
            .build();
    }

    private List<? extends SimpleStep> steps(DataShape ... dataShapes) {
        List<SimpleStep> ret = new ArrayList<>();
        for (DataShape shape : dataShapes) {
            Action action = new Action.Builder().outputDataShape(shape).build();
            ret.add(new SimpleStep.Builder().action(action).build());
        }
        return ret;
    }

    private DataShape dataShape(String kind) {
        return dataShape(kind, null);
    }

    private DataShape dataShape(String kind, String type) {
        return new DataShape.Builder().kind(kind).type(type).build();
    }
}
