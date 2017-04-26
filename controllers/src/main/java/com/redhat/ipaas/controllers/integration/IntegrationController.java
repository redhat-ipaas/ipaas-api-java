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
package com.redhat.ipaas.controllers.integration;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;

import com.redhat.ipaas.core.EventBus;
import com.redhat.ipaas.core.Json;
import com.redhat.ipaas.dao.manager.DataManager;
import com.redhat.ipaas.model.ChangeEvent;
import com.redhat.ipaas.model.Kind;
import com.redhat.ipaas.model.integration.Integration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * This class tracks changes to Integrations and attempts to process them so that
 * their current status matches their desired status.
 */
@Service
public class IntegrationController {
    private final Logger log = LoggerFactory.getLogger(getClass());

    private final DataManager dataManager;
    private final EventBus eventBus;
    private final ConcurrentHashMap<Integration.Status, StatusChangeHandlerProvider.StatusChangeHandler> handlers = new ConcurrentHashMap<>();
    private final Set<String> scheduledChecks = new HashSet<>();
    private ExecutorService executor;
    private ScheduledExecutorService scheduler;

    private final static long SCHEDULE_INTERVAL_IN_SECONDS = 60;

    @Autowired
    public IntegrationController(DataManager dataManager, EventBus eventBus, StatusChangeHandlerProvider handlerFactory) {
        this.dataManager = dataManager;
        this.eventBus = eventBus;
        for (StatusChangeHandlerProvider.StatusChangeHandler handler : handlerFactory.getStatusChangeHandlers()) {
            for (Integration.Status status : handler.getTriggerStatuses()) {
                this.handlers.put(status, handler);
            }
        }
    }

    @PostConstruct
    public void start() {
        executor = Executors.newSingleThreadExecutor();
        scheduler = Executors.newScheduledThreadPool(1);
        scanIntegrationsForWork();

        eventBus.subscribe("integration-controller", getChangeEventSubscription());
    }

    private EventBus.Subscription getChangeEventSubscription() {
        return (event, data) -> {
            // Never do anything that could block in this callback!
            if (event!=null && "change-event".equals(event)) {
                try {
                    ChangeEvent changeEvent = Json.mapper().readValue(data, ChangeEvent.class);
                    if (changeEvent != null) {
                        changeEvent.getId().ifPresent(id -> {
                            changeEvent.getKind()
                                       .map(Kind::from)
                                       .filter(k -> k == Kind.Integration)
                                       .ifPresent(k -> {
                                           checkIntegrationStatusIfNotAlreadyInProgress(id);
                                       });
                        });
                    }
                } catch (IOException e) {
                    log.error("Error while subscribing to change-event " + data, e);
                }
            }
        };
    }

    private void checkIntegrationStatusIfNotAlreadyInProgress(String id) {
        executor.execute(() -> {
            Integration integration = dataManager.fetch(Integration.class, id);
            String scheduledKey = getIntegrationMarkerKey(integration);
            // Don't start check is already a check is running
            if (!scheduledChecks.contains(scheduledKey)) {
                checkIntegrationStatus(integration);
            }
        });
    }

    @PreDestroy
    public void stop() {
        eventBus.unsubscribe("integration-controller");
        scheduler.shutdownNow();
        executor.shutdownNow();
    }

    private void scanIntegrationsForWork() {
        executor.submit(() -> {
            dataManager.fetchAll(Integration.class).getItems().forEach(integration -> {
                log.info("Checking integrations for their status.");
                checkIntegrationStatus(integration);
            });
        });
    }

    private void checkIntegrationStatus(Integration integration) {
        if (integration == null) {
            return;
        }
        Optional<Integration.Status> desired = integration.getDesiredStatus();
        Optional<Integration.Status> current = integration.getCurrentStatus();
        if (!current.equals(desired)) {
            desired.ifPresent(desiredStatus ->
                integration.getId().ifPresent(integrationId -> {
                    StatusChangeHandlerProvider.StatusChangeHandler statusChangeHandler = handlers.get(desiredStatus);
                    if (statusChangeHandler != null) {
                        log.info("Integration {} : Desired status \"{}\" != current status \"{}\" --> calling status change handler",
                                               integrationId, desiredStatus.toString(), current.map(Enum::toString).orElse("[none]"));
                        callStatusChangeHandler(statusChangeHandler, integrationId);
                    }
                }));
        } else {
            // When the desired state is reached remove the marker so that a next change trigger a check again
            // Doesn't harm when no such key exists
            desired.ifPresent(d -> scheduledChecks.remove(getIntegrationMarkerKey(integration)));
        }
    }

    private String getLabel(Integration integration) {
        return "Integration " + integration.getId().orElse("[none]");
    }

    private void callStatusChangeHandler(StatusChangeHandlerProvider.StatusChangeHandler handler, String integrationId) {
        executor.submit(() -> {
            Integration integration = dataManager.fetch(Integration.class, integrationId);
            String checkKey = getIntegrationMarkerKey(integration);
            scheduledChecks.add(checkKey);

            if (stale(handler, integration)) {
                scheduledChecks.remove(checkKey);
                return;
            }

            try {
                log.info("Integration {} : Start processing integration with {}", integrationId, handler.getClass().getSimpleName());
                StatusChangeHandlerProvider.StatusChangeHandler.StatusUpdate update = handler.execute(integration);
                if (update!=null) {

                    log.info("{} : Setting status to {}", getLabel(integration), update.getStatus());

                    // handler.execute might block for while so refresh our copy of the integration
                    // data before we update the current status

                    // TODO: do this in a single TX.
                    Integration current = dataManager.fetch(Integration.class, integrationId);
                    dataManager.update(
                        new Integration.Builder()
                            .createFrom(current)
                            .currentStatus(update.getStatus())
                            .statusMessage(update.getStatusMessage())
                            .currentStatusStep(update.getStatusStep())
                            .lastUpdated(new Date())
                            .build());
                }

            } catch (Exception e) {
                log.error("Error while processing integration status for integration " + integrationId, e);
                // Something went wrong.. lets note it.
                Integration current = dataManager.fetch(Integration.class, integrationId);
                dataManager.update(new Integration.Builder()
                    .createFrom(current)
                    .statusMessage("Error: "+e)
                    .lastUpdated(new Date())
                    .build());

            } finally {
                // Add a next check for the next interval
                reschedule(integrationId);
            }

        });
    }

    private void reschedule(String integrationId) {
        scheduler.schedule(() -> {
            Integration i = dataManager.fetch(Integration.class, integrationId);
            checkIntegrationStatus(i);
        }, SCHEDULE_INTERVAL_IN_SECONDS, TimeUnit.SECONDS);
    }

    private String getIntegrationMarkerKey(Integration integration) {
        return integration.getDesiredStatus().orElseThrow(() -> new IllegalArgumentException("No desired status set on " + integration)).toString() +
               ":" +
               integration.getId().orElseThrow(() -> new IllegalArgumentException("No id set in integration " + integration));
    }

    private boolean stale(StatusChangeHandlerProvider.StatusChangeHandler handler, Integration integration) {
        if (integration == null || handler == null) {
            return true;
        }

        Optional<Integration.Status> desiredStatus = integration.getDesiredStatus();
        return !desiredStatus.isPresent()
               || desiredStatus.equals(integration.getCurrentStatus())
               || !handler.getTriggerStatuses().contains(desiredStatus.get());
    }
}
