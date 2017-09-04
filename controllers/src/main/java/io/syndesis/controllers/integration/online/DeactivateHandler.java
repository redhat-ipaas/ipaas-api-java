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
package io.syndesis.controllers.integration.online;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import io.fabric8.kubernetes.client.KubernetesClientException;
import io.syndesis.controllers.integration.StatusChangeHandler;
import io.syndesis.controllers.integration.StatusUpdate;
import io.syndesis.core.Tokens;
import io.syndesis.model.integration.Integration;
import io.syndesis.model.integration.IntegrationRevision;
import io.syndesis.model.integration.IntegrationState;
import io.syndesis.openshift.OpenShiftDeployment;
import io.syndesis.openshift.OpenShiftService;

public class DeactivateHandler implements StatusChangeHandler {


    private final OpenShiftService openShiftService;

    DeactivateHandler(OpenShiftService openShiftService) {
        this.openShiftService = openShiftService;
    }

    @Override
    public Set<IntegrationState> getTriggerStatuses() {
        return Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            IntegrationState.Inactive, IntegrationState.Draft)));
    }

    @Override
    public StatusUpdate execute(Integration integration, IntegrationRevision revision) {
        Integer version = revision.getVersion().orElseThrow(() -> new IllegalStateException("Deployed IntegrationRevision should have a version"));

        String token = integration.getToken().get();
        Tokens.setAuthenticationToken(token);

        OpenShiftDeployment deployment = OpenShiftDeployment
            .builder()
            .revisionNumber(version)
            .name(integration.getName())
            .replicas(0)
            .token(token)
            .build();



        try {
            openShiftService.scale(deployment);
        } catch (KubernetesClientException e) {
            // Ignore 404 errors, means the deployment does not exist for us
            // to scale down
            if( e.getCode() != 404 ) {
                throw e;
            }
        }

        IntegrationState currentStatus = openShiftService.isScaled(deployment)
            ? IntegrationState.Undeployed
            : IntegrationState.Pending;

        return new StatusUpdate(version, currentStatus);
    }

}
