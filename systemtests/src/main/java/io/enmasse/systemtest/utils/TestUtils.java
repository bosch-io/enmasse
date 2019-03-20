/*
 * Copyright 2016-2018, EnMasse authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */

package io.enmasse.systemtest.utils;

import io.enmasse.address.model.Address;
import io.enmasse.address.model.AddressSpace;
import io.enmasse.admin.model.v1.AddressPlan;
import io.enmasse.systemtest.*;
import io.enmasse.systemtest.apiclients.AddressApiClient;
import io.enmasse.systemtest.timemeasuring.SystemtestsOperation;
import io.enmasse.systemtest.timemeasuring.TimeMeasuringSystem;
import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ContainerStatus;
import io.fabric8.kubernetes.api.model.PersistentVolumeClaim;
import io.fabric8.kubernetes.api.model.Pod;
import io.vertx.core.VertxException;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.function.ThrowingSupplier;
import org.openqa.selenium.Capabilities;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.firefox.FirefoxOptions;
import org.openqa.selenium.remote.RemoteWebDriver;
import org.slf4j.Logger;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.URL;
import java.net.UnknownHostException;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class TestUtils {
    private static Logger log = CustomLogger.getLogger();

    /**
     * scale up/down specific pod (type: Deployment) in address space
     */
    public static void setReplicas(Kubernetes kubernetes, String infraUuid, String deployment, int numReplicas, TimeoutBudget budget) throws InterruptedException {
        kubernetes.setDeploymentReplicas(deployment, numReplicas);
        Map<String, String> labels = new HashMap<>();
        labels.put("name", deployment);
        if (infraUuid != null) {
            labels.put("infraUuid", infraUuid);
        }
        waitForNReplicas(
                kubernetes,
                numReplicas,
                labels,
                budget);
    }

    public static void waitForNReplicas(Kubernetes kubernetes, int expectedReplicas, Map<String, String> labelSelector, TimeoutBudget budget) throws InterruptedException {
        waitForNReplicas(kubernetes, expectedReplicas, labelSelector, Collections.emptyMap(), budget);
    }

    /**
     * wait for expected count of Destination replicas in address space
     */
    public static void waitForNBrokerReplicas(AddressApiClient addressApiClient, Kubernetes kubernetes, AddressSpace addressSpace, int expectedReplicas, boolean readyRequired,
                                              Address destination, TimeoutBudget budget, long checkInterval) throws Exception {
        Address address = AddressUtils.jsonToAddress(addressApiClient.getAddresses(addressSpace, Optional.of(destination.getMetadata().getName())));
        String statefulsetName = address.getAnnotation("cluster_id");
        Map<String, String> labels = new HashMap<>();
        labels.put("role", "broker");
        labels.put("infraUuid", AddressSpaceUtils.getAddressSpaceInfraUuid(addressSpace));
        waitForNReplicas(kubernetes,
                expectedReplicas,
                readyRequired,
                labels,
                Collections.singletonMap("cluster_id", statefulsetName),
                budget,
                checkInterval);
    }

    public static void waitForNBrokerReplicas(AddressApiClient addressApiClient, Kubernetes kubernetes, AddressSpace addressSpace, int expectedReplicas, Address destination, TimeoutBudget budget) throws Exception {
        waitForNBrokerReplicas(addressApiClient, kubernetes, addressSpace, expectedReplicas, true, destination, budget, 5000);
    }


    /**
     * Wait for expected count of replicas
     *
     * @param kubernetes         client for manipulation with kubernetes cluster
     * @param expectedReplicas   count of expected replicas
     * @param labelSelector      labels on scaled pod
     * @param annotationSelector annotations on sclaed pod
     * @param budget             timeout budget - throws Exception when timeout is reached
     * @throws InterruptedException
     */
    public static void waitForNReplicas(Kubernetes kubernetes, int expectedReplicas, boolean readyRequired,
                                        Map<String, String> labelSelector, Map<String, String> annotationSelector, TimeoutBudget budget, long checkInterval) throws InterruptedException {
        boolean done = false;
        int actualReplicas = 0;
        List<Pod> pods;
        do {
            if (annotationSelector.isEmpty()) {
                pods = kubernetes.listPods(labelSelector);
            } else {
                pods = kubernetes.listPods(labelSelector, annotationSelector);
            }
            if (!readyRequired) {
                actualReplicas = pods.size();
            } else {
                actualReplicas = numReady(pods);
            }
            log.info("Have {} out of {} replicas. Expecting={}, ReadyRequired={}",
                    actualReplicas, pods.size(), expectedReplicas, readyRequired);
            if (actualReplicas != expectedReplicas) {
                Thread.sleep(checkInterval);
            } else if (!readyRequired || actualReplicas == pods.size()) {
                done = true;
            }
        } while (!budget.timeoutExpired() && !done);

        if (!done) {
            String message = String.format("Only '%s' out of '%s' in state 'Running' before timeout %s", actualReplicas, expectedReplicas,
                    pods.stream().map(pod -> pod.getMetadata().getName()).collect(Collectors.joining(",")));
            throw new RuntimeException(message);
        }
    }

    public static void waitForNReplicas(Kubernetes kubernetes, int expectedReplicas, Map<String, String> labelSelector, Map<String, String> annotationSelector, TimeoutBudget budget, long checkInterval) throws InterruptedException {
        waitForNReplicas(kubernetes, expectedReplicas, true, labelSelector, annotationSelector, budget, checkInterval);
    }

    public static void waitForNReplicas(Kubernetes kubernetes, int expectedReplicas, Map<String, String> labelSelector, Map<String, String> annotationSelector, TimeoutBudget budget) throws InterruptedException {
        waitForNReplicas(kubernetes, expectedReplicas, labelSelector, annotationSelector, budget, 5000);
    }

    /**
     * Check ready status of all pods in list
     *
     * @param pods list of pods
     * @return
     */
    private static int numReady(List<Pod> pods) {
        int numReady = 0;
        for (Pod pod : pods) {
            if ("Running".equals(pod.getStatus().getPhase())) {
                numReady++;
            } else {
                log.info("POD " + pod.getMetadata().getName() + " in status : " + pod.getStatus().getPhase());
            }
        }
        return numReady;
    }

    /**
     * Wait for expected count of pods within AddressSpace
     *
     * @param client       client for manipulation with kubernetes cluster
     * @param addressSpace
     * @param numExpected  count of expected pods
     * @param budget       timeout budget - this method throws Exception when timeout is reached
     * @throws InterruptedException
     */
    public static void waitForExpectedReadyPods(Kubernetes client, AddressSpace addressSpace, int numExpected, TimeoutBudget budget) throws Exception {
        List<Pod> pods = listRunningPods(client, addressSpace);
        while (budget.timeLeft() >= 0 && pods.size() != numExpected) {
            Thread.sleep(2000);
            pods = listRunningPods(client, addressSpace);
            log.info("Got {} pods, expected: {}", pods.size(), numExpected);
        }
        if (pods.size() != numExpected) {
            throw new IllegalStateException("Unable to find " + numExpected + " pods. Found : " + printPods(pods));
        }
    }

    /**
     * Wait for expected count of pods within AddressSpace
     *
     * @param client      client for manipulation with kubernetes cluster
     * @param numExpected count of expected pods
     * @param budget      timeout budget - this method throws Exception when timeout is reached
     * @throws InterruptedException
     */
    public static void waitForExpectedReadyPods(Kubernetes client, int numExpected, TimeoutBudget budget) throws InterruptedException {
        log.info("Waiting for expected ready pods: {}", numExpected);
        List<Pod> pods = listReadyPods(client);
        while (budget.timeLeft() >= 0 && pods.size() != numExpected) {
            Thread.sleep(2000);
            pods = listReadyPods(client);
            log.info("Got {} pods, expected: {}", pods.size(), numExpected);
        }
        if (pods.size() != numExpected) {
            throw new IllegalStateException("Unable to find " + numExpected + " pods. Found : " + printPods(pods));
        }
        for (Pod pod : pods) {
            client.waitUntilPodIsReady(pod);
        }
    }

    /**
     * Print name of all pods in list
     *
     * @param pods list of pods that should be printed
     * @return
     */
    public static String printPods(List<Pod> pods) {
        return pods.stream()
                .map(pod -> "{" + pod.getMetadata().getName() + ", " + pod.getStatus().getPhase() + "}")
                .collect(Collectors.joining(","));
    }

    /**
     * Get list of all running pods from specific AddressSpace
     *
     * @param kubernetes   client for manipulation with kubernetes cluster
     * @param addressSpace
     * @return
     */
    public static List<Pod> listRunningPods(Kubernetes kubernetes, AddressSpace addressSpace) throws Exception {
        return kubernetes.listPods(Collections.singletonMap("infraUuid", AddressSpaceUtils.getAddressSpaceInfraUuid(addressSpace))).stream()
                .filter(pod -> pod.getStatus().getPhase().equals("Running")
                        && !pod.getMetadata().getName().startsWith(SystemtestsKubernetesApps.MESSAGING_CLIENTS))
                .collect(Collectors.toList());
    }

    /**
     * Get list of all running pods from specific AddressSpace
     *
     * @param kubernetes client for manipulation with kubernetes cluster
     * @return
     */
    public static List<Pod> listRunningPods(Kubernetes kubernetes) {
        return kubernetes.listPods().stream()
                .filter(pod -> pod.getStatus().getPhase().equals("Running")
                        && !pod.getMetadata().getName().startsWith(SystemtestsKubernetesApps.MESSAGING_CLIENTS))
                .collect(Collectors.toList());
    }

    /**
     * Get list of all ready pods
     *
     * @param kubernetes client for manipulation with kubernetes cluster
     * @return
     */
    public static List<Pod> listReadyPods(Kubernetes kubernetes) {
        return kubernetes.listPods().stream()
                .filter(pod -> pod.getStatus().getContainerStatuses().stream().allMatch(ContainerStatus::getReady)
                        && !pod.getMetadata().getName().startsWith(SystemtestsKubernetesApps.MESSAGING_CLIENTS))
                .collect(Collectors.toList());
    }

    /**
     * Get list of all ready pods
     *
     * @param kubernetes client for manipulation with kubernetes cluster
     * @return
     */
    public static List<Pod> listReadyPods(Kubernetes kubernetes, String namespace) {
        return kubernetes.listPods(namespace).stream()
                .filter(pod -> pod.getStatus().getContainerStatuses().stream().allMatch(ContainerStatus::getReady)
                        && !pod.getMetadata().getName().startsWith(SystemtestsKubernetesApps.MESSAGING_CLIENTS))
                .collect(Collectors.toList());
    }

    public static List<Pod> listBrokerPods(Kubernetes kubernetes) {
        return kubernetes.listPods(Collections.singletonMap("role", "broker"));
    }

    public static List<Pod> listBrokerPods(Kubernetes kubernetes, AddressSpace addressSpace) {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("role", "broker");
        labels.put("infraUuid", AddressSpaceUtils.getAddressSpaceInfraUuid(addressSpace));
        return kubernetes.listPods(labels);
    }

    public static List<Pod> listRouterPods(Kubernetes kubernetes, AddressSpace addressSpace) {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("capability", "router");
        labels.put("infraUuid", AddressSpaceUtils.getAddressSpaceInfraUuid(addressSpace));
        return kubernetes.listPods(labels);
    }

    public static List<Pod> listAdminConsolePods(Kubernetes kubernetes, AddressSpace addressSpace) {
        Map<String, String> labels = new LinkedHashMap<>();
        if (addressSpace.getSpec().getType().equals(AddressSpaceType.STANDARD.toString())) {
            labels.put("name", "admin");
        } else {
            labels.put("name", "agent");
        }
        labels.put("infraUuid", AddressSpaceUtils.getAddressSpaceInfraUuid(addressSpace));
        return kubernetes.listPods(labels);
    }

    public static List<PersistentVolumeClaim> listPersistentVolumeClaims(Kubernetes kubernetes, AddressSpace addressSpace) {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("infraUuid", AddressSpaceUtils.getAddressSpaceInfraUuid(addressSpace));
        return kubernetes.listPersistentVolumeClaims(labels);
    }

    /**
     * Check if isReady attribute is set to true
     *
     * @param address JsonObject with address
     * @return
     */
    public static boolean isAddressReady(JsonObject address) {
        boolean isReady = false;
        if (address != null) {
            isReady = address.getJsonObject("status").getBoolean("isReady");
        }
        return isReady;
    }

    public static boolean isPlanSynced(JsonObject address) {
        boolean isReady = false;
        JsonObject annotations = address.getJsonObject("metadata").getJsonObject("annotations");
        if (annotations != null) {
            String appliedPlan = annotations.getString("enmasse.io/applied-plan");
            String actualPlan = address.getJsonObject("spec").getString("plan");
            isReady = actualPlan.equals(appliedPlan);
        }
        return isReady;
    }

    public static boolean areBrokersDrained(JsonObject address) {
        boolean isReady = true;
        JsonArray brokerStatuses = address.getJsonObject("status").getJsonArray("brokerStatuses");
        for (int i = 0; i < brokerStatuses.size(); i++) {
            JsonObject brokerStatus = brokerStatuses.getJsonObject(i);
            if ("Draining".equals(brokerStatus.getString("state"))) {
                isReady = false;
                break;
            }
        }
        return isReady;
    }

    interface AddressListMatcher {
        Map<String, JsonObject> matchAddresses(JsonObject addressList);
    }

    /**
     * Wait until destinations isReady parameter is set to true with 1 MINUTE timeout for each destination
     *
     * @param apiClient    instance of AddressApiClient
     * @param addressSpace name of addressSpace
     * @param budget       the timeout budget for this operation
     * @param destinations variable count of destinations
     * @throws Exception IllegalStateException if destinations are not ready within timeout
     */
    public static void waitForDestinationsReady(AddressApiClient apiClient, AddressSpace addressSpace, TimeoutBudget budget, Address... destinations) throws Exception {
        String operationID = TimeMeasuringSystem.startOperation(SystemtestsOperation.ADDRESS_WAIT_READY);
        waitForAddressesMatched(apiClient, addressSpace, budget, destinations.length, addressList -> checkAddressesMatching(addressList, TestUtils::isAddressReady, destinations));
        TimeMeasuringSystem.stopOperation(operationID);
    }

    public static void waitForDestinationPlanApplied(AddressApiClient apiClient, AddressSpace addressSpace, TimeoutBudget budget, Address... destinations) throws Exception {
        String operationID = TimeMeasuringSystem.startOperation(SystemtestsOperation.ADDRESS_WAIT_PLAN_CHANGE);
        waitForAddressesMatched(apiClient, addressSpace, budget, destinations.length, addressList -> checkAddressesMatching(addressList, TestUtils::isPlanSynced, destinations));
        TimeMeasuringSystem.stopOperation(operationID);
    }

    public static void waitForBrokersDrained(AddressApiClient apiClient, AddressSpace addressSpace, TimeoutBudget budget, Address... destinations) throws Exception {
        String operationID = TimeMeasuringSystem.startOperation(SystemtestsOperation.ADDRESS_WAIT_BROKER_DRAINED);
        waitForAddressesMatched(apiClient, addressSpace, budget, destinations.length, addressList -> checkAddressesMatching(addressList, TestUtils::areBrokersDrained, destinations));
        TimeMeasuringSystem.stopOperation(operationID);
    }

    private static void waitForAddressesMatched(AddressApiClient apiClient, AddressSpace addressSpace, TimeoutBudget timeoutBudget, int totalDestinations, AddressListMatcher addressListMatcher) throws Exception {
        Map<String, JsonObject> notMatched = new HashMap<>();

        while (timeoutBudget.timeLeft() >= 0) {
            JsonObject addressList = apiClient.getAddresses(addressSpace, Optional.empty());
            notMatched = addressListMatcher.matchAddresses(addressList);
            if (notMatched.isEmpty()) {
                Thread.sleep(5000); //TODO: remove this sleep after fix for ready check will be available
                break;
            }
            Thread.sleep(5000);
        }

        if (!notMatched.isEmpty()) {
            JsonObject addressList = apiClient.getAddresses(addressSpace, Optional.empty());
            notMatched = addressListMatcher.matchAddresses(addressList);
            throw new IllegalStateException(notMatched.size() + " out of " + totalDestinations + " addresses are not matched: " + notMatched.values());
        }
    }

    private static Map<String, JsonObject> checkAddressesMatching(JsonObject addressList, Predicate<JsonObject> predicate, Address... destinations) {
        Map<String, JsonObject> notMatchingAddresses = new HashMap<>();
        for (Address destination : destinations) {
            JsonObject addressObject = lookupAddress(addressList, destination.getSpec().getAddress());
            if (addressObject == null) {
                notMatchingAddresses.put(destination.getSpec().getAddress(), null);
            } else if (!predicate.test(addressObject)) {
                notMatchingAddresses.put(destination.getSpec().getAddress(), addressObject);
            }
        }
        return notMatchingAddresses;
    }

    /**
     * Get address(JsonObject) from AddressList(JsonObject) by address name
     *
     * @param addressList JsonObject received from AddressApiClient
     * @param address     address name
     * @return
     */
    private static JsonObject lookupAddress(JsonObject addressList, String address) {
        JsonArray items = addressList.getJsonArray("items");
        for (int i = 0; i < items.size(); i++) {
            JsonObject addressObject = items.getJsonObject(i);
            if (addressObject.getJsonObject("spec").getString("address").equals(address)) {
                return addressObject;
            }
        }
        return null;
    }

    /**
     * Generate message body with prefix
     */
    public static List<String> generateMessages(String prefix, int numMessages) {
        return IntStream.range(0, numMessages).mapToObj(i -> prefix + i).collect(Collectors.toList());
    }

    /**
     * Generate message body with "testmessage" content and without prefix
     */
    public static List<String> generateMessages(int numMessages) {
        return generateMessages("testmessage", numMessages);
    }

    /**
     * Check if endpoint is accessible
     */
    public static boolean resolvable(Endpoint endpoint) {
        for (int i = 0; i < 10; i++) {
            try {
                InetAddress[] addresses = Inet4Address.getAllByName(endpoint.getHost());
                Thread.sleep(1000);
                return addresses.length > 0;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (UnknownHostException ignore) {
            }
        }
        return false;
    }

    /**
     * Wait until Namespace will be removed
     *
     * @param kubernetes client for manipulation with kubernetes cluster
     * @param namespace  project/namespace to remove
     */
    public static void waitForNamespaceDeleted(Kubernetes kubernetes, String namespace) throws Exception {
        TimeoutBudget budget = new TimeoutBudget(10, TimeUnit.MINUTES);
        while (budget.timeLeft() >= 0 && kubernetes.listNamespaces().contains(namespace)) {
            Thread.sleep(1000);
        }
        if (kubernetes.listNamespaces().contains(namespace)) {
            throw new TimeoutException("Timed out waiting for namespace " + namespace + " to disappear");
        }
    }

    /**
     * Repeat request n-times in a row
     *
     * @param retry count of remaining retries
     * @param fn    request function
     * @return
     */
    public static <T> T doRequestNTimes(int retry, Callable<T> fn, Optional<Runnable> reconnect) throws Exception {
        try {
            return fn.call();
        } catch (Exception ex) {
            if (ex.getCause() instanceof VertxException && ex.getCause().getMessage().contains("Connection was closed")) {
                if (reconnect.isPresent()) {
                    log.warn("connection was closed, trying to reconnect...");
                    reconnect.get().run();
                }
            }
            if (ex.getCause() instanceof UnknownHostException && retry > 0) {
                try {
                    log.info("{} remaining iterations", retry);
                    return doRequestNTimes(retry - 1, fn, reconnect);
                } catch (Exception ex2) {
                    throw ex2;
                }
            } else {
                if (ex.getCause() != null) {
                    ex.getCause().printStackTrace();
                } else {
                    ex.printStackTrace();
                }
                throw ex;
            }
        }
    }

    /**
     * Repeat command n-times
     *
     * @param retry count of remaining retries
     * @param fn    request function
     * @return
     */
    public static <T> T runUntilPass(int retry, Callable<T> fn) throws InterruptedException {
        for (int i = 0; i < retry; i++) {
            try {
                log.info("Running command, attempt: {}", i);
                return fn.call();
            } catch (Exception ex) {
                log.info("Command failed");
                ex.printStackTrace();
            }
            Thread.sleep(1000);
        }
        throw new IllegalStateException(String.format("Command wasn't pass in %s attempts", retry));
    }

    /**
     * Replace address plan in ConfigMap of already existing address
     *
     * @param kubernetes client for manipulation with kubernetes cluster
     * @param addrSpace  address space which contains ConfigMap
     * @param dest       destination which will be modified
     * @param plan       definition of AddressPlan
     */
    public static void replaceAddressConfig(Kubernetes kubernetes, AddressSpace addrSpace, Address dest, AddressPlan plan) {
        String mapKey = "config.json";
        ConfigMap destConfigMap = kubernetes.getConfigMap(addrSpace.getMetadata().getNamespace(), dest.getSpec().getAddress());

        JsonObject data = new JsonObject(destConfigMap.getData().get(mapKey));
        log.info(data.toString());
        data.getJsonObject("spec").remove("plan");
        data.getJsonObject("spec").put("plan", plan.getMetadata().getName());

        Map<String, String> modifiedData = new LinkedHashMap<>();
        modifiedData.put(mapKey, data.toString());
        destConfigMap.setData(modifiedData);
        kubernetes.replaceConfigMap(addrSpace.getMetadata().getNamespace(), destConfigMap);
    }

    public static void deleteAddressSpaceCreatedBySC(Kubernetes kubernetes, AddressSpace addressSpace, String namespace, GlobalLogCollector logCollector) throws Exception {
        String operationID = TimeMeasuringSystem.startOperation(SystemtestsOperation.DELETE_ADDRESS_SPACE);
        logCollector.collectEvents();
        logCollector.collectApiServerJmapLog();
        logCollector.collectLogsTerminatedPods();
        logCollector.collectConfigMaps();
        logCollector.collectRouterState("deleteAddressSpaceCreatedBySC");
        kubernetes.deleteNamespace(namespace);
        waitForNamespaceDeleted(kubernetes, namespace);
        AddressSpaceUtils.waitForAddressSpaceDeleted(kubernetes, addressSpace);
        TimeMeasuringSystem.stopOperation(operationID);
    }

    public static RemoteWebDriver getFirefoxDriver() throws Exception {
        Endpoint endpoint = SystemtestsKubernetesApps.getFirefoxSeleniumAppEndpoint(Kubernetes.getInstance());
        return getRemoteDriver(endpoint.getHost(), endpoint.getPort(), new FirefoxOptions());
    }

    public static RemoteWebDriver getChromeDriver() throws Exception {
        Endpoint endpoint = SystemtestsKubernetesApps.getChromeSeleniumAppEndpoint(Kubernetes.getInstance());
        return getRemoteDriver(endpoint.getHost(), endpoint.getPort(), new ChromeOptions());
    }

    private static RemoteWebDriver getRemoteDriver(String host, int port, Capabilities options) throws Exception {
        int attempts = 60;
        URL hubUrl = new URL(String.format("http://%s:%s/wd/hub", host, port));
        for (int i = 0; i < attempts; i++) {
            if (isReachable(hubUrl)) {
                return new RemoteWebDriver(hubUrl, options);
            }
            Thread.sleep(2000);
        }
        throw new IllegalStateException("Selenium webdriver cannot connect to selenium container");
    }

    public static boolean isReachable(URL url) {
        log.info("Trying to connect to {}", url.toString());
        try {
            url.openConnection();
            url.getContent();
            log.info("Client is able to connect to the selenium hub");
            return true;
        } catch (Exception ex) {
            log.warn("Cannot connect to hub: {}", ex.getMessage());
            return false;
        }
    }

    public static void waitUntilCondition(Callable<String> fn, String expected, TimeoutBudget budget) throws Exception {
        String actual = "Too small time out budget!!";
        while (!budget.timeoutExpired()) {
            actual = fn.call();
            log.debug(actual);
            if (actual.contains(expected)) {
                return;
            }
            log.debug("next iteration, remaining time: {}", budget.timeLeft());
            Thread.sleep(2000);
        }
        Assertions.fail(String.format("Expected: '%s' in content, but was: '%s'", expected, actual));
    }

    public static void waitUntilCondition(final String forWhat, final BooleanSupplier condition, final TimeoutBudget budget) throws Exception {
        Objects.requireNonNull(condition);
        Objects.requireNonNull(budget);

        log.info("Waiting {} ms for - {}", budget.timeLeft(), forWhat);

        while (!budget.timeoutExpired()) {
            if (condition.getAsBoolean()) {
                return;
            }
            log.debug("next iteration, remaining time: {}", budget.timeLeft());
            Thread.sleep(2000);
        }
        Assertions.fail("Failed to wait for: " + forWhat);
    }

    public static void waitForChangedResourceVersion(final TimeoutBudget budget, final AddressApiClient client, final String name, final String currentResourceVersion) throws Exception {
        waitForChangedResourceVersion(budget, currentResourceVersion, () -> AddressSpaceUtils.jsonToAdressSpace(client.getAddressSpace(name)).getMetadata().getResourceVersion());
    }

    public static void waitForChangedResourceVersion(final TimeoutBudget budget, final String currentResourceVersion, final ThrowingSupplier<String> provideNewResourceVersion)
            throws Exception {
        Objects.requireNonNull(currentResourceVersion, "'currentResourceVersion' must not be null");

        waitUntilCondition("Resource version to change away from: " + currentResourceVersion, () -> {
            try {
                final String newVersion = provideNewResourceVersion.get();
                return !currentResourceVersion.equals(newVersion);
            } catch (RuntimeException e) {
                throw e; // don't pollute the cause chain
            } catch (Throwable e) {
                throw new RuntimeException(e);
            }
        }, budget);
    }

    public static String getTopicPrefix(boolean topicSwitch) {
        return topicSwitch ? "topic://" : "";
    }

}