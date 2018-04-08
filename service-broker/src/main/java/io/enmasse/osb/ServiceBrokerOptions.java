/*
 * Copyright 2016-2018, EnMasse authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.enmasse.osb;

import java.time.Duration;
import java.util.Map;

public class ServiceBrokerOptions {
    private Duration resyncInterval = Duration.ofMinutes(10);
    private String impersonateUser = null;
    private String certDir = null;
    private boolean enableRbac = false;
    private String keycloakUrl = null;
    private String keycloakAdminUser = null;
    private String keycloakAdminPassword = null;

    public Duration getResyncInterval() {
        return resyncInterval;
    }

    public ServiceBrokerOptions setResyncInterval(Duration resyncInterval) {
        this.resyncInterval = resyncInterval;
        return this;
    }

    public String getImpersonateUser() {
        return impersonateUser;
    }

    public String getCertDir() {
        return certDir;
    }

    public boolean getEnableRbac() {
        return enableRbac;
    }

    public String getKeycloakUrl() {
        return keycloakUrl;
    }

    public String getKeycloakAdminUser() {
        return keycloakAdminUser;
    }

    public String getKeycloakAdminPassword() {
        return keycloakAdminPassword;
    }

    public ServiceBrokerOptions setImpersonateUser(String impersonateUser) {
        this.impersonateUser = impersonateUser;
        return this;
    }

    public ServiceBrokerOptions setCertDir(String certDir) {
        this.certDir = certDir;
        return this;
    }

    public ServiceBrokerOptions setEnableRbac(boolean enableRbac) {
        this.enableRbac = enableRbac;
        return this;
    }

    public ServiceBrokerOptions setKeycloakUrl(String keycloakUrl) {
        this.keycloakUrl = keycloakUrl;
        return this;
    }

    public ServiceBrokerOptions setKeycloakAdminUser(String keycloakAdminUser) {
        this.keycloakAdminUser = keycloakAdminUser;
        return this;
    }

    public ServiceBrokerOptions setKeycloakAdminPassword(String keycloakAdminPassword) {
        this.keycloakAdminPassword = keycloakAdminPassword;
        return this;
    }

    public static ServiceBrokerOptions fromEnv(Map<String, String> env) {
        ServiceBrokerOptions options = new ServiceBrokerOptions();

        options.setKeycloakUrl(getEnvOrThrow(env, "KEYCLOAK_URL"));
        options.setKeycloakAdminUser(getEnvOrThrow(env, "KEYCLOAK_ADMIN_USER"));
        options.setKeycloakAdminPassword(getEnvOrThrow(env, "KEYCLOAK_ADMIN_PASSWORD"));

        String resyncInterval = env.get("RESYNC_INTERVAL");
        if (resyncInterval != null) {
            options.setResyncInterval(Duration.ofSeconds(Long.parseLong(resyncInterval)));
        }

        return options;
    }

    private static String getEnvOrThrow(Map<String, String> env, String envVar) {
        String var = env.get(envVar);
        if (var == null) {
            throw new IllegalArgumentException(String.format("Unable to find value for required environment var '%s'", envVar));
        }
        return var;
    }
}
