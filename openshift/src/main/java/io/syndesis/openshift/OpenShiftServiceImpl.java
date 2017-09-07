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
package io.syndesis.openshift;

import java.io.IOException;
import java.io.StringWriter;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import io.fabric8.kubernetes.api.builder.TypedVisitor;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.client.RequestConfig;
import io.fabric8.kubernetes.client.RequestConfigBuilder;
import io.fabric8.openshift.api.model.DeploymentConfig;
import io.fabric8.openshift.api.model.DeploymentConfigBuilder;
import io.fabric8.openshift.api.model.DeploymentConfigSpecBuilder;
import io.fabric8.openshift.api.model.DeploymentConfigStatus;
import io.fabric8.openshift.client.NamespacedOpenShiftClient;
import io.fabric8.openshift.client.OpenShiftClient;
import io.syndesis.core.Names;
import io.syndesis.core.SyndesisServerException;
import io.syndesis.core.Tokens;

public class OpenShiftServiceImpl implements OpenShiftService {

    private final NamespacedOpenShiftClient openShiftClient;
    private final OpenShiftConfigurationProperties config;

    public OpenShiftServiceImpl(NamespacedOpenShiftClient openShiftClient, OpenShiftConfigurationProperties config) {
        this.openShiftClient = openShiftClient;
        this.config = config;
    }

    @Override
    public void create(OpenShiftDeployment d) {
        String sanitizedName = Names.sanitize(d.getName());
        Integer revisionNumber = d.getRevisionNumber();
        String token = d.getToken().orElse(Tokens.getAuthenticationToken());
        RequestConfig requestConfig = new RequestConfigBuilder().withOauthToken(token).build();
        openShiftClient.withRequestConfig(requestConfig).<Void>call(c -> {
            DockerImage img = new DockerImage(config.getBuilderImage());
            ensureImageStreams(openShiftClient, sanitizedName, revisionNumber, img);
            ensureDeploymentConfig(openShiftClient, sanitizedName, revisionNumber, config.getIntegrationServiceAccount());
            ensureSecret(openShiftClient, sanitizedName, revisionNumber,  d.getApplicationProperties().orElseGet(Properties::new));
            ensureBuildConfig(openShiftClient, sanitizedName, revisionNumber,
                d.getGitRepository().orElseThrow(()-> new IllegalStateException("Git repository is required")),
                img,
                d.getWebhookSecret().orElseThrow(() -> new IllegalStateException("Web hook secret is required")));
            return null;
        });
    }

    @Override
    public boolean delete(OpenShiftDeployment d) {
        String sanitizedName = Names.sanitize(d.getName());

        String token = d.getToken().orElse(Tokens.getAuthenticationToken());
        RequestConfig requestConfig = new RequestConfigBuilder().withOauthToken(token).build();

        return openShiftClient.withRequestConfig(requestConfig).call(c ->
            removeImageStreams(openShiftClient, new DockerImage(config.getBuilderImage())) &&
                removeDeploymentConfig(openShiftClient, sanitizedName) &&
                removeSecret(openShiftClient, sanitizedName) &&
                removeBuildConfig(openShiftClient, sanitizedName)
        );
    }

    @Override
    public boolean exists(OpenShiftDeployment d) {
        DeploymentConfig dc = openShiftClient.withRequestConfig(d.getRequestConfig()).call(c ->
            openShiftClient.deploymentConfigs().withName(Names.sanitize(d.getName())).get()
        );
        return matches(d, dc);
    }

    @Override
    public void scale(OpenShiftDeployment d) {
        DeploymentConfig dc = openShiftClient.withRequestConfig(d.getRequestConfig()).call(c ->
            openShiftClient.deploymentConfigs().withName(Names.sanitize(d.getName())).get()
        );

        //check if the revision number is the same, before actually scaling.
        if (!matches(d, dc)) {
            return;
        }

        DeploymentConfig update = new DeploymentConfigBuilder(dc)
            .accept(new TypedVisitor<DeploymentConfigSpecBuilder>() {
                @Override
                public void visit(DeploymentConfigSpecBuilder builder) {
                    builder.withReplicas(d.getReplicas().orElse(1));
                }
            }).build();

        openShiftClient.withRequestConfig(d.getRequestConfig()).call(c ->
            openShiftClient.deploymentConfigs().withName(Names.sanitize(Names.sanitize(d.getName())))
                .replace(update)
        );
    }


    @Override
    public boolean isScaled(OpenShiftDeployment d) {
        DeploymentConfig dc = openShiftClient.withRequestConfig(d.getRequestConfig()).call(c ->
            openShiftClient.deploymentConfigs().withName(Names.sanitize(d.getName())).get()
        );

        if (!matches(d, dc)) {
            return false;
        }

        int allReplicas = 0;
        int readyReplicas = 0;
        if (dc != null && dc.getStatus() != null) {
            DeploymentConfigStatus status = dc.getStatus();
            allReplicas = nullSafe(status.getReplicas());
            readyReplicas = nullSafe(status.getReadyReplicas());
        }

        int desiredReplicas = d.getReplicas().orElse(1);
        return desiredReplicas == allReplicas && desiredReplicas == readyReplicas;
    }

    @Override
    public String getGitHubWebHookUrl(OpenShiftDeployment d, String secret) {
        return String.format("%s/namespaces/%s/buildconfigs/%s/webhooks/%s/github",
                             config.getApiBaseUrl(), config.getNamespace(), Names.sanitize(d.getName()), secret);
    }

    private int nullSafe(Integer nr) {
        return nr != null ? nr : 0;
    }

//==================================================================================================

    private static boolean matches(OpenShiftDeployment deployment, DeploymentConfig dc) {
        return dc != null &&
            dc.getMetadata() != null &&
            dc.getMetadata().getAnnotations() != null &&
            String.valueOf(deployment.getRevisionNumber()).equals(dc.getMetadata().getAnnotations().get(REVISION_NUMBER_ANNOTATION));
    }

    private static void ensureImageStreams(OpenShiftClient client, String projectName, Integer revisionNumber, DockerImage img) {
        client.imageStreams().withName(img.getShortName()).createOrReplaceWithNew()
                .withNewMetadata()
                    .withName(img.shortName)
                    .addToAnnotations(REVISION_NUMBER_ANNOTATION, String.valueOf(revisionNumber))
                .endMetadata()
                .withNewSpec().addNewTag().withNewFrom().withKind("DockerImage").withName(img.getImage()).endFrom().withName(img.getTag()).endTag().endSpec()
                .done();

        client.imageStreams().withName(projectName).createOrReplaceWithNew()
            .withNewMetadata().withName(projectName).endMetadata().done();
    }

    private static boolean removeImageStreams(OpenShiftClient client, DockerImage img) {
        return client.imageStreams().withName(img.getShortName()).delete();
    }

    private static void ensureDeploymentConfig(OpenShiftClient client, String projectName, Integer revisionNumber, String serviceAccount) {
        client.deploymentConfigs().withName(projectName).createOrReplaceWithNew()
            .withNewMetadata()
                .withName(projectName)
                .addToAnnotations(REVISION_NUMBER_ANNOTATION, String.valueOf(revisionNumber))
            .endMetadata()
            .withNewSpec()
            .withReplicas(1)
            .addToSelector("integration", projectName)
            .withNewTemplate()
            .withNewMetadata().addToLabels("integration", projectName).endMetadata()
            .withNewSpec()
            .withServiceAccount(serviceAccount)
            .withServiceAccountName(serviceAccount)
            .addNewContainer()
            .withImage(" ").withImagePullPolicy("Always").withName(projectName)
            .addNewPort().withName("jolokia").withContainerPort(8778).endPort()
            .addNewVolumeMount()
                .withName("secret-volume")
                .withMountPath("/deployments/config")
                .withReadOnly(false)
            .endVolumeMount()
            .endContainer()
            .addNewVolume()
                .withName("secret-volume")
                .withNewSecret()
                    .withSecretName(projectName)
                .endSecret()
            .endVolume()
            .endSpec()
            .endTemplate()
            .addNewTrigger().withType("ConfigChange").endTrigger()
            .addNewTrigger().withType("ImageChange")
            .withNewImageChangeParams()
            .withAutomatic(true).addToContainerNames(projectName)
            .withNewFrom().withKind("ImageStreamTag").withName(projectName + ":latest").endFrom()
            .endImageChangeParams()
            .endTrigger()
            .endSpec()
            .done();
    }


    private static boolean removeDeploymentConfig(OpenShiftClient client, String projectName) {
        return client.deploymentConfigs().withName(projectName).delete();
    }

    private static void ensureBuildConfig(OpenShiftClient client, String projectName, Integer revisionNumber, String gitRepo, DockerImage img, String webhookSecret) {
        client.buildConfigs().withName(projectName).createOrReplaceWithNew()
            .withNewMetadata()
                .withName(projectName)
                .addToAnnotations(REVISION_NUMBER_ANNOTATION, String.valueOf(revisionNumber))
            .endMetadata()
            .withNewSpec()
            .withRunPolicy("SerialLatestOnly")
            .addNewTrigger().withType("ConfigChange").endTrigger()
            .addNewTrigger().withType("ImageChange").endTrigger()
            .addNewTrigger().withType("GitHub").withNewGithub().withSecret(webhookSecret).endGithub().endTrigger()
            .withNewSource().withType("git").withNewGit().withUri(gitRepo).endGit().endSource()
            .withNewStrategy()
            .withType("Source")
            .withNewSourceStrategy()
            .withNewFrom().withKind("ImageStreamTag").withName(img.getShortName() + ":" + img.getTag()).endFrom()
            .withIncremental(true)
              .withEnv(new EnvVar("MAVEN_OPTS","-XX:+UseG1GC -XX:+UseStringDeduplication -Xmx500m", null))
            .endSourceStrategy()
            .endStrategy()
            .withNewOutput().withNewTo().withKind("ImageStreamTag").withName(projectName + ":latest").endTo().endOutput()
            .endSpec()
            .done();
    }

    private static boolean removeBuildConfig(OpenShiftClient client, String projectName) {
        return client.buildConfigs().withName(projectName).delete();
    }

    private static void ensureSecret(OpenShiftClient client, String projectName, Integer revisionNumber, Properties data) {
        Map<String, String> wrapped = new HashMap<>();
        wrapped.put("application.properties", toString(data));

        client.secrets().withName(projectName).createOrReplaceWithNew()
            .withNewMetadata()
                .withName(projectName)
                .addToAnnotations(REVISION_NUMBER_ANNOTATION, String.valueOf(revisionNumber))
            .endMetadata()
            .withStringData(wrapped)
            .done();
    }

    private static String toString(Properties data) {
        try {
            StringWriter w = new StringWriter();
            data.store(w, "");
            return w.toString();
        } catch (IOException e) {
            throw SyndesisServerException.launderThrowable(e);
        }
    }

    private static boolean removeSecret(OpenShiftClient client, String projectName) {
       return client.secrets().withName(projectName).delete();
    }


    /* default */ static class DockerImage {
        private final String image;

        private String tag = "latest";

        private final String shortName;

        /* default */ DockerImage(String fullImage) {
            image = fullImage;

            int colonIndex = fullImage.lastIndexOf(':');

            String builderImageStreamName = fullImage;
            if (colonIndex > -1) {
                builderImageStreamName = fullImage.substring(0, colonIndex);
                tag = fullImage.substring(colonIndex + 1);
            }
            shortName = builderImageStreamName.substring(builderImageStreamName.lastIndexOf('/') + 1);
        }

        public String getImage() {
            return image;
        }

        public String getTag() {
            return tag;
        }

        public String getShortName() {
            return shortName;
        }
    }

}
