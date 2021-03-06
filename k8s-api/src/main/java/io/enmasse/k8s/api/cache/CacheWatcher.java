/*
 * Copyright 2018, EnMasse authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.enmasse.k8s.api.cache;

import io.enmasse.k8s.api.ResourceCache;

public interface CacheWatcher<T> {
    void onInit(ResourceCache<T> cache);
    void onUpdate() throws Exception;
}
