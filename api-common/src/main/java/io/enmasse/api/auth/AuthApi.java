/*
 * Copyright 2018, EnMasse authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.enmasse.api.auth;

import io.fabric8.kubernetes.api.model.Secret;

public interface AuthApi {
    TokenReview performTokenReview(String token);
    SubjectAccessReview performSubjectAccessReview(String user, String namespace, String verb);
    String getCert(String secretName, String namespace);
    String getNamespace();
}
