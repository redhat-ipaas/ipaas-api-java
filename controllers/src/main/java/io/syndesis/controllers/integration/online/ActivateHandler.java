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

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import io.syndesis.controllers.integration.StatusChangeHandler;
import io.syndesis.controllers.integration.StatusUpdate;
import io.syndesis.core.Names;
import io.syndesis.core.SyndesisServerException;
import io.syndesis.core.Tokens;
import io.syndesis.dao.manager.DataManager;
import io.syndesis.github.GitHubService;
import io.syndesis.integration.model.steps.Endpoint;
import io.syndesis.model.connection.Connector;
import io.syndesis.model.integration.Integration;
import io.syndesis.model.integration.IntegrationRevision;
import io.syndesis.model.integration.IntegrationState;
import io.syndesis.model.integration.Step;
import io.syndesis.openshift.ImmutableOpenShiftDeployment;
import io.syndesis.openshift.OpenShiftDeployment;
import io.syndesis.openshift.OpenShiftService;
import io.syndesis.project.converter.GenerateProjectRequest;
import io.syndesis.project.converter.ProjectGenerator;
import org.eclipse.egit.github.core.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ActivateHandler implements StatusChangeHandler {

    // Step used which should be performed only once per integration
    /* default */ static final String STEP_GITHUB = "github-setup";
    /* default */ static final String STEP_OPENSHIFT = "openshift-setup";

    private final DataManager dataManager;
    private final OpenShiftService openShiftService;
    private final GitHubService gitHubService;
    private final ProjectGenerator projectConverter;

    private static final Logger LOG = LoggerFactory.getLogger(ActivateHandler.class);

    /* default */ ActivateHandler(DataManager dataManager, OpenShiftService openShiftService,
                    GitHubService gitHubService, ProjectGenerator projectConverter) {
        this.dataManager = dataManager;
        this.openShiftService = openShiftService;
        this.gitHubService = gitHubService;
        this.projectConverter = projectConverter;
    }

    public Set<IntegrationState> getTriggerStatuses() {
        return Collections.singleton(IntegrationState.Active);
    }


    /**
     *
     * @param integration
     * @param revision
     * @return
     */
    @Override
    public StatusUpdate execute(Integration integration, IntegrationRevision revision) {
        //TODO: This is clearly wrong, we need something better than that.
        Integer version = revision.getVersion().orElse(
            integration.getRevisions()
                .stream()
                .map(i -> i.getVersion().orElse(0))
                .reduce(Integer::max)
                .orElse(0) + 1);

        IntegrationRevision versionedRevision = revision.withVersion(version);

        if (!integration.getToken().isPresent()) {
            return new StatusUpdate(version, versionedRevision.getCurrentState(), "No token present");
        }

        if (isTokenExpired(integration)) {
            LOG.info("{} : Token is expired", getLabel(integration));
            return new StatusUpdate(version, versionedRevision.getCurrentState(), "Token is expired");
        }
        String token = storeToken(integration);

        Properties applicationProperties = extractApplicationPropertiesFrom(versionedRevision);

        OpenShiftDeployment deployment = OpenShiftDeployment
            .builder()
            .name(integration.getName())
            .revisionNumber(version)
            .replicas(1)
            .token(token)
            .applicationProperties(applicationProperties)
            .build();

        String secret = createSecret();

        // TODO: Verify Token and refresh if expired ....
        List<String> stepsPerformed = integration.getStepsDone().orElse(new ArrayList<>());
        try {
            String gitCloneUrl = null;
            if (!stepsPerformed.contains(STEP_GITHUB)) {
                User gitHubUser = getGitHubUser();
                String username = gitHubUser.getLogin();
                //TODO: Possibly the is something that want to do for every new revision.
                LOG.info("{} : Looked up GitHub user {}", getLabel(integration), username);
                Map<String, byte[]> projectFiles = createProjectFiles(username, integration, versionedRevision);
                LOG.info("{} : Created project files", getLabel(integration));

                gitCloneUrl = ensureGitHubSetup(integration, gitHubUser, getWebHookUrl(deployment, secret), projectFiles);
                LOG.info("{} : Updated GitHub repo {}", getLabel(integration), gitCloneUrl);
                stepsPerformed.add(STEP_GITHUB);
            }

            if (!stepsPerformed.contains(STEP_OPENSHIFT)) {
                if (gitCloneUrl==null) {
                    gitCloneUrl = getCloneURL(integration);
                }
                createOpenShiftResources(integration.getName(), gitCloneUrl, secret, applicationProperties);
                LOG.info("{} : Created OpenShift resources", getLabel(integration));
                stepsPerformed.add(STEP_OPENSHIFT);
            }

            if (openShiftService.isScaled(deployment)) {
                //Once an IntegrationRevision is published and transfered to the state Active it becomes immutable and can not be changed afterwards (except for state related properties).
                dataManager.update(new Integration.Builder().createFrom(integration)
                    .draftRevision(Optional.empty())
                    .addRevision(versionedRevision.withCurrentState(IntegrationState.Active))
                    .deployedRevisionId(revision.getVersion())
                    .build());

                return new StatusUpdate(version, IntegrationState.Active, stepsPerformed);
            }
        } catch (@SuppressWarnings("PMD.AvoidCatchingGenericException")Exception e) {
            LOG.info("{} : Failure", getLabel(integration), e);
        }
        return new StatusUpdate(version, IntegrationState.Pending, stepsPerformed);
    }

    protected String getCloneURL(Integration integration)  {
        try {
            return gitHubService.getCloneURL(Names.sanitize(integration.getName()));
        } catch (IOException e) {
            throw SyndesisServerException.launderThrowable(e);
        }
    }

    private String getWebHookUrl(OpenShiftDeployment deployment, String secret) {
        return openShiftService.getGitHubWebHookUrl(deployment, secret);
    }

    @SuppressWarnings("PMD.UnusedPrivateMethod") // PMD false positive
    private String ensureGitHubSetup(Integration integration, User githubUser, String webHookUrl, Map<String, byte[]> projectFiles) {
        try {
            // Do all github stuff at once
            String gitHubRepoName = Names.sanitize(integration.getName());
            String gitCloneUrl = gitHubService.createOrUpdateProjectFiles(gitHubRepoName, githubUser, generateCommitMessage(), projectFiles, webHookUrl);

            // Update integration within DB. Maybe re-read it before updating the URL ? Best: Add a dedicated 'updateGitRepo()'
            // method to the backend
            Integration updatedIntegration = new Integration.Builder()
                .createFrom(integration)
                .gitRepo(gitCloneUrl)
                .build();
            dataManager.update(updatedIntegration);
            return gitCloneUrl;
        } catch (IOException e) {
            throw SyndesisServerException.launderThrowable(e);
        }
    }

    private Map<String, byte[]> createProjectFiles(String username, Integration integration, IntegrationRevision revision) {
        try {
            GenerateProjectRequest request = new GenerateProjectRequest.Builder()
                .id(integration.getId())
                .name(integration.getName())
                .spec(revision.getSpec())
                .connectors(fetchConnectorsMap())
                .gitHubRepoName(Names.sanitize(integration.getName()))
                .gitHubUserLogin(username)
                .build();
            return projectConverter.generate(request);
        } catch (IOException e) {
            throw SyndesisServerException.launderThrowable(e);
        }
    }

    private User getGitHubUser() {
        try {
            return gitHubService.getApiUser();
        } catch (IOException e) {
            throw SyndesisServerException.launderThrowable(e);
        }
    }

    private String generateCommitMessage() {
        // TODO Let's generate some nice message...
        return "Updated";
    }

    private String createSecret() {
        return UUID.randomUUID().toString();
    }

    private Map<String, Connector> fetchConnectorsMap() {
        return dataManager.fetchAll(Connector.class).getItems().stream().collect(Collectors.toMap(o -> o.getId().get(), o -> o));
    }

    private void createOpenShiftResources(String integrationName, String gitCloneUrl, String webHookSecret, Properties applicationProperties) {
        openShiftService.create(
            ImmutableOpenShiftDeployment.builder()
                                        .name(integrationName)
                                        .gitRepository(gitCloneUrl)
                                        .webhookSecret(webHookSecret)
                                        .applicationProperties(applicationProperties)
                                        .build());
    }

    /**
     * Creates a {@link Map} that contains all the configuration that corresponds to application.properties.
     * The configuration should include:
     *  i) component properties
     *  ii) sensitive endpoint properties that should be masked.
     * @param revision
     * @return
     */
    private Properties extractApplicationPropertiesFrom(IntegrationRevision revision) {
        Properties secrets = new Properties();
        Map<String, Connector> connectorMap = fetchConnectorsMap();

        for (Step step : revision.getSpec().getSteps()) {
            if (step.getStepKind().equals(Endpoint.KIND)) {
                step.getAction().ifPresent(action -> {
                    step.getConnection().ifPresent(connection -> {
                        String connectorId = step.getConnection().get().getConnectorId().orElse(action.getConnectorId());
                            if (!connectorMap.containsKey(connectorId)) {
                                throw new IllegalStateException("Connector:[" + connectorId + "] not found.");
                            }
                        String prefix = action.getCamelConnectorPrefix();
                        Connector connector = connectorMap.get(connectorId);

                        //Handle component data
                        secrets.putAll(connector.filterProperties(connection.getConfiguredProperties(),
                                                                  connector.isComponentProperty(),
                                                                  e -> prefix + "." + e.getKey(),
                                                                  e -> e.getValue()));

                        secrets.putAll(connector.filterProperties(step.getConfiguredProperties().orElse(new HashMap<String,String>()),
                                                                  connector.isComponentProperty(),
                                                                  e -> prefix + "." + e.getKey(),
                                                                  e -> e.getValue()));

                        //Handle sensitive data
                        secrets.putAll(connector.filterProperties(connection.getConfiguredProperties(),
                                                                  connector.isSecret(),
                                                                  e -> prefix + "." + e.getKey(),
                                                                  e -> e.getValue()));

                        secrets.putAll(connector.filterProperties(step.getConfiguredProperties().orElse(new HashMap<String,String>()),
                                                                  connector.isSecret(),
                                                                  e -> prefix + "." + e.getKey(),
                                                                  e -> e.getValue()));
                    });
                });
                continue;
            }
        }
        return secrets;
    }

    private static String getLabel(Integration integration) {
        return "Integration " + integration.getId().orElse("[none]");
    }


    private static boolean isTokenExpired(Integration integration) {
        return integration.getToken().isPresent() &&
            Tokens.isTokenExpired(integration.getToken().get());
    }

    private static String storeToken(Integration integration) {
        String token = integration.getToken().orElse(null);
        Tokens.setAuthenticationToken(token);
        return token;
    }

}
