/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.heron.scheduler.kubernetes;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import com.google.common.collect.ImmutableMap;

import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;
import org.mockito.Spy;
import org.mockito.runners.MockitoJUnitRunner;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.heron.common.basics.ByteAmount;
import org.apache.heron.common.basics.Pair;
import org.apache.heron.scheduler.TopologySubmissionException;
import org.apache.heron.scheduler.kubernetes.KubernetesUtils.TestTuple;
import org.apache.heron.spi.common.Config;
import org.apache.heron.spi.common.Key;
import org.apache.heron.spi.packing.Resource;

import io.kubernetes.client.custom.Quantity;
import io.kubernetes.client.openapi.models.V1ConfigMap;
import io.kubernetes.client.openapi.models.V1ConfigMapBuilder;
import io.kubernetes.client.openapi.models.V1Container;
import io.kubernetes.client.openapi.models.V1ContainerBuilder;
import io.kubernetes.client.openapi.models.V1ContainerPort;
import io.kubernetes.client.openapi.models.V1EnvVar;
import io.kubernetes.client.openapi.models.V1EnvVarSource;
import io.kubernetes.client.openapi.models.V1ObjectFieldSelector;
import io.kubernetes.client.openapi.models.V1PersistentVolumeClaim;
import io.kubernetes.client.openapi.models.V1PersistentVolumeClaimBuilder;
import io.kubernetes.client.openapi.models.V1PodSpec;
import io.kubernetes.client.openapi.models.V1PodSpecBuilder;
import io.kubernetes.client.openapi.models.V1PodTemplateSpec;
import io.kubernetes.client.openapi.models.V1ResourceRequirements;
import io.kubernetes.client.openapi.models.V1Toleration;
import io.kubernetes.client.openapi.models.V1Volume;
import io.kubernetes.client.openapi.models.V1VolumeBuilder;
import io.kubernetes.client.openapi.models.V1VolumeMount;
import io.kubernetes.client.openapi.models.V1VolumeMountBuilder;

import static org.apache.heron.scheduler.kubernetes.KubernetesConstants.VolumeConfigKeys;
import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.doReturn;

@RunWith(MockitoJUnitRunner.class)
public class V1ControllerTest {

  private static final String TOPOLOGY_NAME = "topology-name";
  private static final String CONFIGMAP_NAME = "CONFIG-MAP-NAME";
  private static final String POD_TEMPLATE_NAME = "POD-TEMPLATE-NAME";
  private static final String CONFIGMAP_POD_TEMPLATE_NAME =
      String.format("%s.%s", CONFIGMAP_NAME, POD_TEMPLATE_NAME);
  private static final String POD_TEMPLATE_VALID =
      "apiVersion: apps/v1\n"
          + "kind: PodTemplate\n"
          + "metadata:\n"
          + "  name: heron-tracker\n"
          + "  namespace: default\n"
          + "template:\n"
          + "  metadata:\n"
          + "    labels:\n"
          + "      app: heron-tracker\n"
          + "  spec:\n"
          + "    containers:\n"
          + "      - name: heron-tracker\n"
          + "        image: apache/heron:latest\n"
          + "        ports:\n"
          + "          - containerPort: 8888\n"
          + "            name: api-port\n"
          + "        resources:\n"
          + "          requests:\n"
          + "            cpu: \"100m\"\n"
          + "            memory: \"200M\"\n"
          + "          limits:\n"
          + "            cpu: \"400m\"\n"
          + "            memory: \"512M\"";
  private static final String POD_TEMPLATE_LOCATION_EXECUTOR =
      String.format(KubernetesContext.KUBERNETES_POD_TEMPLATE_LOCATION,
          KubernetesConstants.EXECUTOR_NAME);
  private static final String POD_TEMPLATE_LOCATION_MANAGER =
      String.format(KubernetesContext.KUBERNETES_POD_TEMPLATE_LOCATION,
          KubernetesConstants.MANAGER_NAME);

  private static final Config CONFIG = Config.newBuilder().build();
  private static final Config CONFIG_WITH_POD_TEMPLATE = Config.newBuilder()
      .put(POD_TEMPLATE_LOCATION_EXECUTOR, CONFIGMAP_POD_TEMPLATE_NAME)
      .put(POD_TEMPLATE_LOCATION_MANAGER, CONFIGMAP_POD_TEMPLATE_NAME)
      .build();
  private static final Config RUNTIME = Config.newBuilder()
      .put(Key.TOPOLOGY_NAME, TOPOLOGY_NAME)
      .build();
  private final Config configDisabledPodTemplate = Config.newBuilder()
      .put(POD_TEMPLATE_LOCATION_EXECUTOR, CONFIGMAP_POD_TEMPLATE_NAME)
      .put(POD_TEMPLATE_LOCATION_MANAGER, CONFIGMAP_POD_TEMPLATE_NAME)
      .put(KubernetesContext.KUBERNETES_POD_TEMPLATE_DISABLED, "true")
      .build();

  @Spy
  private final V1Controller v1ControllerWithPodTemplate =
      new V1Controller(CONFIG_WITH_POD_TEMPLATE, RUNTIME);

  @Spy
  private final V1Controller v1ControllerPodTemplate =
      new V1Controller(configDisabledPodTemplate, RUNTIME);

  @Rule
  public final ExpectedException expectedException = ExpectedException.none();

  @Test
  public void testLoadPodFromTemplateDefault() {
    final V1Controller v1ControllerNoPodTemplate = new V1Controller(CONFIG, RUNTIME);
    final V1PodTemplateSpec defaultPodSpec = new V1PodTemplateSpec();

    final V1PodTemplateSpec podSpecExecutor = v1ControllerNoPodTemplate.loadPodFromTemplate(true);
    Assert.assertEquals("Default Pod Spec for Executor", defaultPodSpec, podSpecExecutor);

    final V1PodTemplateSpec podSpecManager = v1ControllerNoPodTemplate.loadPodFromTemplate(false);
    Assert.assertEquals("Default Pod Spec for Manager", defaultPodSpec, podSpecManager);
  }

  @Test
  public void testLoadPodFromTemplateNullConfigMap() {
    final List<TestTuple<Boolean, String>> testCases = new LinkedList<>();
    testCases.add(new TestTuple<>("Executor not found", true, "unable to locate"));
    testCases.add(new TestTuple<>("Manager not found", false, "unable to locate"));

    for (TestTuple<Boolean, String> testCase : testCases) {
      doReturn(null)
          .when(v1ControllerWithPodTemplate)
          .getConfigMap(anyString());

      String message = "";
      try {
        v1ControllerWithPodTemplate.loadPodFromTemplate(testCase.input);
      } catch (TopologySubmissionException e) {
        message = e.getMessage();
      }
      Assert.assertTrue(testCase.description, message.contains(testCase.expected));
    }
  }

  @Test
  public void testLoadPodFromTemplateNoConfigMap() {
    final List<TestTuple<Boolean, String>> testCases = new LinkedList<>();
    testCases.add(new TestTuple<>("Executor no ConfigMap", true, "Failed to locate Pod Template"));
    testCases.add(new TestTuple<>("Manager no ConfigMap", false, "Failed to locate Pod Template"));

    for (TestTuple<Boolean, String> testCase : testCases) {
      doReturn(new V1ConfigMap())
          .when(v1ControllerWithPodTemplate)
          .getConfigMap(anyString());

      String message = "";
      try {
        v1ControllerWithPodTemplate.loadPodFromTemplate(testCase.input);
      } catch (TopologySubmissionException e) {
        message = e.getMessage();
      }
      Assert.assertTrue(testCase.description, message.contains(testCase.expected));
    }
  }

  @Test
  public void testLoadPodFromTemplateNoTargetConfigMap() {
    final List<TestTuple<Boolean, String>> testCases = new LinkedList<>();
    testCases.add(new TestTuple<>("Executor no target ConfigMap",
        true, "Failed to locate Pod Template"));
    testCases.add(new TestTuple<>("Manager no target ConfigMap",
        false, "Failed to locate Pod Template"));

    final V1ConfigMap configMapNoTargetData = new V1ConfigMapBuilder()
        .withNewMetadata()
          .withName(CONFIGMAP_NAME)
        .endMetadata()
        .addToData("Dummy Key", "Dummy Value")
        .build();

    for (TestTuple<Boolean, String> testCase : testCases) {
      doReturn(configMapNoTargetData)
          .when(v1ControllerWithPodTemplate)
          .getConfigMap(anyString());

      String message = "";
      try {
        v1ControllerWithPodTemplate.loadPodFromTemplate(testCase.input);
      } catch (TopologySubmissionException e) {
        message = e.getMessage();
      }
      Assert.assertTrue(testCase.description, message.contains(testCase.expected));
    }
  }

  @Test
  public void testLoadPodFromTemplateBadTargetConfigMap() {
    // ConfigMap with target ConfigMap and an invalid Pod Template.
    final V1ConfigMap configMapInvalidPod = new V1ConfigMapBuilder()
        .withNewMetadata()
          .withName(CONFIGMAP_NAME)
        .endMetadata()
        .addToData(POD_TEMPLATE_NAME, "Dummy Value")
        .build();

    // ConfigMap with target ConfigMaps and an empty Pod Template.
    final V1ConfigMap configMapEmptyPod = new V1ConfigMapBuilder()
        .withNewMetadata()
        .withName(CONFIGMAP_NAME)
        .endMetadata()
        .addToData(POD_TEMPLATE_NAME, "")
        .build();

    // Test case container.
    // Input: ConfigMap to setup mock V1Controller, Boolean flag for executor/manager switch.
    // Output: The expected error message.
    final List<TestTuple<Pair<V1ConfigMap, Boolean>, String>> testCases = new LinkedList<>();
    testCases.add(new TestTuple<>("Executor invalid Pod Template",
        new Pair<>(configMapInvalidPod, true), "Error parsing"));
    testCases.add(new TestTuple<>("Manager invalid Pod Template",
        new Pair<>(configMapInvalidPod, false), "Error parsing"));
    testCases.add(new TestTuple<>("Executor empty Pod Template",
        new Pair<>(configMapEmptyPod, true), "Error parsing"));
    testCases.add(new TestTuple<>("Manager empty Pod Template",
        new Pair<>(configMapEmptyPod, false), "Error parsing"));

    // Test loop.
    for (TestTuple<Pair<V1ConfigMap, Boolean>, String> testCase : testCases) {
      doReturn(testCase.input.first)
          .when(v1ControllerWithPodTemplate)
          .getConfigMap(anyString());

      String message = "";
      try {
        v1ControllerWithPodTemplate.loadPodFromTemplate(testCase.input.second);
      } catch (TopologySubmissionException e) {
        message = e.getMessage();
      }
      Assert.assertTrue(testCase.description, message.contains(testCase.expected));
    }
  }

  @Test
  public void testLoadPodFromTemplateValidConfigMap() {
    final String expected =
        "        containers: [class V1Container {\n"
        + "            args: null\n"
        + "            command: null\n"
        + "            env: null\n"
        + "            envFrom: null\n"
        + "            image: apache/heron:latest\n"
        + "            imagePullPolicy: null\n"
        + "            lifecycle: null\n"
        + "            livenessProbe: null\n"
        + "            name: heron-tracker\n"
        + "            ports: [class V1ContainerPort {\n"
        + "                containerPort: 8888\n"
        + "                hostIP: null\n"
        + "                hostPort: null\n"
        + "                name: api-port\n"
        + "                protocol: null\n"
        + "            }]\n"
        + "            readinessProbe: null\n"
        + "            resources: class V1ResourceRequirements {\n"
        + "                limits: {cpu=Quantity{number=0.400, format=DECIMAL_SI}, "
        + "memory=Quantity{number=512000000, format=DECIMAL_SI}}\n"
        + "                requests: {cpu=Quantity{number=0.100, format=DECIMAL_SI}, "
        + "memory=Quantity{number=200000000, format=DECIMAL_SI}}\n"
        + "            }\n"
        + "            securityContext: null\n"
        + "            startupProbe: null\n"
        + "            stdin: null\n"
        + "            stdinOnce: null\n"
        + "            terminationMessagePath: null\n"
        + "            terminationMessagePolicy: null\n"
        + "            tty: null\n"
        + "            volumeDevices: null\n"
        + "            volumeMounts: null\n"
        + "            workingDir: null\n"
        + "        }]";


    // ConfigMap with valid Pod Template.
    final V1ConfigMap configMapValidPod = new V1ConfigMapBuilder()
        .withNewMetadata()
          .withName(CONFIGMAP_NAME)
        .endMetadata()
        .addToData(POD_TEMPLATE_NAME, POD_TEMPLATE_VALID)
        .build();

    // Test case container.
    // Input: ConfigMap to setup mock V1Controller, Boolean flag for executor/manager switch.
    // Output: The expected Pod template as a string.
    final List<TestTuple<Pair<V1ConfigMap, Boolean>, String>> testCases = new LinkedList<>();
    testCases.add(new TestTuple<>("Executor valid Pod Template",
        new Pair<>(configMapValidPod, true), expected));
    testCases.add(new TestTuple<>("Manager valid Pod Template",
        new Pair<>(configMapValidPod, false), expected));

    // Test loop.
    for (TestTuple<Pair<V1ConfigMap, Boolean>, String> testCase : testCases) {
      doReturn(testCase.input.first)
          .when(v1ControllerWithPodTemplate)
          .getConfigMap(anyString());

      V1PodTemplateSpec podTemplateSpec = v1ControllerWithPodTemplate.loadPodFromTemplate(true);

      Assert.assertTrue(podTemplateSpec.toString().contains(testCase.expected));
    }
  }

  @Test
  public void testLoadPodFromTemplateInvalidConfigMap() {
    // ConfigMap with an invalid Pod Template.
    final String invalidPodTemplate =
        "apiVersion: apps/v1\n"
            + "kind: InvalidTemplate\n"
            + "metadata:\n"
            + "  name: heron-tracker\n"
            + "  namespace: default\n"
            + "template:\n"
            + "  metadata:\n"
            + "    labels:\n"
            + "      app: heron-tracker\n"
            + "  spec:\n";
    final V1ConfigMap configMap = new V1ConfigMapBuilder()
        .withNewMetadata()
          .withName(CONFIGMAP_NAME)
        .endMetadata()
        .addToData(POD_TEMPLATE_NAME, invalidPodTemplate)
        .build();


    // Test case container.
    // Input: ConfigMap to setup mock V1Controller, Boolean flag for executor/manager switch.
    // Output: The expected Pod template as a string.
    final List<TestTuple<Pair<V1ConfigMap, Boolean>, String>> testCases = new LinkedList<>();
    testCases.add(new TestTuple<>("Executor invalid Pod Template",
        new Pair<>(configMap, true), "Error parsing"));
    testCases.add(new TestTuple<>("Manager invalid Pod Template",
        new Pair<>(configMap, false), "Error parsing"));

    // Test loop.
    for (TestTuple<Pair<V1ConfigMap, Boolean>, String> testCase : testCases) {
      doReturn(testCase.input.first)
          .when(v1ControllerWithPodTemplate)
          .getConfigMap(anyString());

      String message = "";
      try {
        v1ControllerWithPodTemplate.loadPodFromTemplate(testCase.input.second);
      } catch (TopologySubmissionException e) {
        message = e.getMessage();
      }
      Assert.assertTrue(message.contains(testCase.expected));
    }
  }

  @Test
  public void testDisablePodTemplates() {
    // ConfigMap with valid Pod Template.
    V1ConfigMap configMapValidPod = new V1ConfigMapBuilder()
        .withNewMetadata()
          .withName(CONFIGMAP_NAME)
        .endMetadata()
        .addToData(POD_TEMPLATE_NAME, POD_TEMPLATE_VALID)
        .build();
    final String expected = "Pod Templates are disabled";
    String message = "";
    doReturn(configMapValidPod)
        .when(v1ControllerPodTemplate)
        .getConfigMap(anyString());

    try {
      v1ControllerPodTemplate.loadPodFromTemplate(true);
    } catch (TopologySubmissionException e) {
      message = e.getMessage();
    }
    Assert.assertTrue(message.contains(expected));
  }

  @Test
  public void testGetPodTemplateLocationPassing() {
    final Config testConfig = Config.newBuilder()
        .put(POD_TEMPLATE_LOCATION_EXECUTOR, CONFIGMAP_POD_TEMPLATE_NAME)
        .build();
    final V1Controller v1Controller = new V1Controller(testConfig, RUNTIME);
    final Pair<String, String> expected = new Pair<>(CONFIGMAP_NAME, POD_TEMPLATE_NAME);

    // Correct parsing
    final Pair<String, String> actual = v1Controller.getPodTemplateLocation(true);
    Assert.assertEquals(expected, actual);
  }

  @Test
  public void testGetPodTemplateLocationNoConfigMap() {
    expectedException.expect(TopologySubmissionException.class);
    final Config testConfig = Config.newBuilder()
        .put(POD_TEMPLATE_LOCATION_EXECUTOR, ".POD-TEMPLATE-NAME").build();
    V1Controller v1Controller = new V1Controller(testConfig, RUNTIME);
    v1Controller.getPodTemplateLocation(true);
  }

  @Test
  public void testGetPodTemplateLocationNoPodTemplate() {
    expectedException.expect(TopologySubmissionException.class);
    final Config testConfig = Config.newBuilder()
        .put(POD_TEMPLATE_LOCATION_EXECUTOR, "CONFIGMAP-NAME.").build();
    V1Controller v1Controller = new V1Controller(testConfig, RUNTIME);
    v1Controller.getPodTemplateLocation(true);
  }

  @Test
  public void testGetPodTemplateLocationNoDelimiter() {
    expectedException.expect(TopologySubmissionException.class);
    final Config testConfig = Config.newBuilder()
        .put(POD_TEMPLATE_LOCATION_EXECUTOR, "CONFIGMAP-NAMEPOD-TEMPLATE-NAME").build();
    V1Controller v1Controller = new V1Controller(testConfig, RUNTIME);
    v1Controller.getPodTemplateLocation(true);
  }

  @Test
  public void testConfigureContainerPorts() {
    final String portNamekept = "random-port-to-be-kept";
    final int portNumberkept = 1111;
    final int numInstances = 3;
    final List<V1ContainerPort> expectedPortsBase =
        Collections.unmodifiableList(V1Controller.getExecutorPorts());
    final List<V1ContainerPort> debugPorts =
        Collections.unmodifiableList(V1Controller.getDebuggingPorts(numInstances));
    final List<V1ContainerPort> inputPortsBase = Collections.unmodifiableList(
        Arrays.asList(
            new V1ContainerPort()
                .name("server-port-to-replace").containerPort(KubernetesConstants.SERVER_PORT),
            new V1ContainerPort()
                .name("shell-port-to-replace").containerPort(KubernetesConstants.SHELL_PORT),
            new V1ContainerPort().name(portNamekept).containerPort(portNumberkept)
        )
    );

    // Null ports. This is the default case.
    final V1Container inputContainerWithNullPorts = new V1ContainerBuilder().build();
    v1ControllerWithPodTemplate.configureContainerPorts(false, 0, inputContainerWithNullPorts);
    Assert.assertTrue("Server and/or shell PORTS for container with null ports list",
        CollectionUtils.containsAll(inputContainerWithNullPorts.getPorts(), expectedPortsBase));

    // Empty ports.
    final V1Container inputContainerWithEmptyPorts = new V1ContainerBuilder()
        .withPorts(new LinkedList<>())
        .build();
    v1ControllerWithPodTemplate.configureContainerPorts(false, 0, inputContainerWithEmptyPorts);
    Assert.assertTrue("Server and/or shell PORTS for container with empty ports list",
        CollectionUtils.containsAll(inputContainerWithEmptyPorts.getPorts(), expectedPortsBase));

    // Port overriding.
    final List<V1ContainerPort> inputPorts = new LinkedList<>(inputPortsBase);
    final V1Container inputContainerWithPorts = new V1ContainerBuilder()
        .withPorts(inputPorts)
        .build();
    final List<V1ContainerPort> expectedPortsOverriding = new LinkedList<>(expectedPortsBase);
    expectedPortsOverriding
        .add(new V1ContainerPort().name(portNamekept).containerPort(portNumberkept));

    v1ControllerWithPodTemplate.configureContainerPorts(false, 0, inputContainerWithPorts);
    Assert.assertTrue("Server and/or shell PORTS for container should be overwritten.",
        CollectionUtils.containsAll(inputContainerWithPorts.getPorts(), expectedPortsOverriding));

    // Port overriding with debug ports.
    final List<V1ContainerPort> inputPortsWithDebug = new LinkedList<>(debugPorts);
    inputPortsWithDebug.addAll(inputPortsBase);
    final V1Container inputContainerWithDebug = new V1ContainerBuilder()
        .withPorts(inputPortsWithDebug)
        .build();
    final List<V1ContainerPort> expectedPortsDebug = new LinkedList<>(expectedPortsBase);
    expectedPortsDebug.add(new V1ContainerPort().name(portNamekept).containerPort(portNumberkept));
    expectedPortsDebug.addAll(debugPorts);

    v1ControllerWithPodTemplate.configureContainerPorts(
        true, numInstances, inputContainerWithDebug);
    Assert.assertTrue("Server and/or shell with debug PORTS for container should be overwritten.",
        CollectionUtils.containsAll(inputContainerWithDebug.getPorts(), expectedPortsDebug));
  }

  @Test
  public void testConfigureContainerEnvVars() {
    final List<V1EnvVar> heronEnvVars =
        Collections.unmodifiableList(V1Controller.getExecutorEnvVars());
    final V1EnvVar additionEnvVar = new V1EnvVar()
        .name("env-variable-to-be-kept")
        .valueFrom(new V1EnvVarSource()
            .fieldRef(new V1ObjectFieldSelector()
                .fieldPath("env-variable-was-kept")));
    final List<V1EnvVar> inputEnvVars = Arrays.asList(
        new V1EnvVar()
            .name(KubernetesConstants.ENV_HOST)
            .valueFrom(new V1EnvVarSource()
                .fieldRef(new V1ObjectFieldSelector()
                    .fieldPath("env-host-to-be-replaced"))),
        new V1EnvVar()
            .name(KubernetesConstants.ENV_POD_NAME)
            .valueFrom(new V1EnvVarSource()
                .fieldRef(new V1ObjectFieldSelector()
                    .fieldPath("pod-name-to-be-replaced"))),
        additionEnvVar
    );

    // Null env vars. This is the default case.
    V1Container containerWithNullEnvVars = new V1ContainerBuilder().build();
    v1ControllerWithPodTemplate.configureContainerEnvVars(containerWithNullEnvVars);
    Assert.assertTrue("ENV_HOST & ENV_POD_NAME in container with null Env Vars should match",
        CollectionUtils.containsAll(containerWithNullEnvVars.getEnv(), heronEnvVars));

    // Empty env vars.
    V1Container containerWithEmptyEnvVars = new V1ContainerBuilder()
        .withEnv(new LinkedList<>())
        .build();
    v1ControllerWithPodTemplate.configureContainerEnvVars(containerWithEmptyEnvVars);
    Assert.assertTrue("ENV_HOST & ENV_POD_NAME in container with empty Env Vars should match",
        CollectionUtils.containsAll(containerWithEmptyEnvVars.getEnv(), heronEnvVars));

    // Env Var overriding.
    final List<V1EnvVar> expectedOverriding = new LinkedList<>(heronEnvVars);
    expectedOverriding.add(additionEnvVar);
    V1Container containerWithEnvVars = new V1ContainerBuilder()
        .withEnv(inputEnvVars)
        .build();
    v1ControllerWithPodTemplate.configureContainerEnvVars(containerWithEnvVars);
    Assert.assertTrue("ENV_HOST & ENV_POD_NAME in container with Env Vars should be overridden",
        CollectionUtils.containsAll(containerWithEnvVars.getEnv(), expectedOverriding));
  }

  @Test
  public void testConfigureContainerResources() {
    final boolean isExecutor = true;

    final Resource resourceDefault = new Resource(
        9, ByteAmount.fromMegabytes(19000), ByteAmount.fromMegabytes(99000));
    final Resource resourceCustom = new Resource(
        4, ByteAmount.fromMegabytes(34000), ByteAmount.fromMegabytes(400000));

    final Quantity defaultRAM = Quantity.fromString(
        KubernetesUtils.Megabytes(resourceDefault.getRam()));
    final Quantity defaultCPU = Quantity.fromString(
        Double.toString(KubernetesUtils.roundDecimal(resourceDefault.getCpu(), 3)));
    final Quantity customRAM = Quantity.fromString(
        KubernetesUtils.Megabytes(resourceCustom.getRam()));
    final Quantity customCPU = Quantity.fromString(
        Double.toString(KubernetesUtils.roundDecimal(resourceCustom.getCpu(), 3)));
    final Quantity customDisk = Quantity.fromString(
        KubernetesUtils.Megabytes(resourceCustom.getDisk()));

    final Config configNoLimit = Config.newBuilder()
        .put(KubernetesContext.KUBERNETES_RESOURCE_REQUEST_MODE, "NOT_SET")
        .build();
    final Config configWithLimit = Config.newBuilder()
        .put(KubernetesContext.KUBERNETES_RESOURCE_REQUEST_MODE, "EQUAL_TO_LIMIT")
        .build();

    final V1ResourceRequirements expectDefaultRequirements = new V1ResourceRequirements()
        .putLimitsItem(KubernetesConstants.MEMORY, defaultRAM)
        .putLimitsItem(KubernetesConstants.CPU, defaultCPU);

    final V1ResourceRequirements expectCustomRequirements = new V1ResourceRequirements()
        .putLimitsItem(KubernetesConstants.MEMORY, defaultRAM)
        .putLimitsItem(KubernetesConstants.CPU, defaultCPU)
        .putLimitsItem("disk", customDisk);

    final V1ResourceRequirements customRequirements = new V1ResourceRequirements()
        .putLimitsItem(KubernetesConstants.MEMORY, customRAM)
        .putLimitsItem(KubernetesConstants.CPU, customCPU)
        .putLimitsItem("disk", customDisk);

    // Default. Null resources.
    V1Container containerNull = new V1ContainerBuilder().build();
    v1ControllerWithPodTemplate.configureContainerResources(
        containerNull, configNoLimit, resourceDefault, isExecutor);
    Assert.assertTrue("Default LIMITS should be set in container with null LIMITS",
        containerNull.getResources().getLimits().entrySet()
            .containsAll(expectDefaultRequirements.getLimits().entrySet()));

    // Empty resources.
    V1Container containerEmpty = new V1ContainerBuilder().withNewResources().endResources().build();
    v1ControllerWithPodTemplate.configureContainerResources(
        containerEmpty, configNoLimit, resourceDefault, isExecutor);
    Assert.assertTrue("Default LIMITS should be set in container with empty LIMITS",
        containerNull.getResources().getLimits().entrySet()
            .containsAll(expectDefaultRequirements.getLimits().entrySet()));

    // Custom resources.
    V1Container containerCustom = new V1ContainerBuilder()
        .withResources(customRequirements)
        .build();
    v1ControllerWithPodTemplate.configureContainerResources(
        containerCustom, configNoLimit, resourceDefault, isExecutor);
    Assert.assertTrue("Custom LIMITS should be set in container with custom LIMITS",
        containerCustom.getResources().getLimits().entrySet()
            .containsAll(expectCustomRequirements.getLimits().entrySet()));

    // Custom resources with request.
    V1Container containerRequests = new V1ContainerBuilder()
        .withResources(customRequirements)
        .build();
    v1ControllerWithPodTemplate.configureContainerResources(
        containerRequests, configWithLimit, resourceDefault, isExecutor);
    Assert.assertTrue("Custom LIMITS should be set in container with custom LIMITS and REQUEST",
        containerRequests.getResources().getLimits().entrySet()
            .containsAll(expectCustomRequirements.getLimits().entrySet()));
    Assert.assertTrue("Custom REQUEST should be set in container with custom LIMITS and REQUEST",
        containerRequests.getResources().getRequests().entrySet()
            .containsAll(expectCustomRequirements.getLimits().entrySet()));
  }

  @Test
  public void testConfigureContainerResourcesCLI() {
    final boolean isExecutor = true;
    final String customLimitMEMStr = "120Gi";
    final String customLimitCPUStr = "5";
    final String customRequestMEMStr = "100Mi";
    final String customRequestCPUStr = "4";

    final Resource resources = new Resource(
        6, ByteAmount.fromMegabytes(34000), ByteAmount.fromGigabytes(400));

    final Quantity customLimitMEM = Quantity.fromString(customLimitMEMStr);
    final Quantity customLimitCPU = Quantity.fromString(customLimitCPUStr);
    final Quantity customRequestMEM = Quantity.fromString(customRequestMEMStr);
    final Quantity customRequestCPU = Quantity.fromString(customRequestCPUStr);

    final Config config = Config.newBuilder()
        .put(String.format(KubernetesContext.KUBERNETES_RESOURCE_LIMITS_PREFIX
                + KubernetesConstants.CPU, KubernetesConstants.EXECUTOR_NAME), customLimitCPUStr)
        .put(String.format(KubernetesContext.KUBERNETES_RESOURCE_LIMITS_PREFIX
                + KubernetesConstants.MEMORY, KubernetesConstants.EXECUTOR_NAME), customLimitMEMStr)
        .put(String.format(KubernetesContext.KUBERNETES_RESOURCE_REQUESTS_PREFIX
                + KubernetesConstants.CPU, KubernetesConstants.EXECUTOR_NAME), customRequestCPUStr)
        .put(String.format(KubernetesContext.KUBERNETES_RESOURCE_REQUESTS_PREFIX
                + KubernetesConstants.MEMORY, KubernetesConstants.EXECUTOR_NAME),
            customRequestMEMStr)
        .put(KubernetesContext.KUBERNETES_RESOURCE_REQUEST_MODE, "EQUAL_TO_LIMIT")
        .build();

    final V1Container expected = new V1ContainerBuilder()
        .withNewResources()
          .addToLimits(KubernetesConstants.CPU, customLimitCPU)
          .addToLimits(KubernetesConstants.MEMORY, customLimitMEM)
          .addToRequests(KubernetesConstants.CPU, customRequestCPU)
          .addToRequests(KubernetesConstants.MEMORY, customRequestMEM)
        .endResources()
        .build();

    final V1Container actual = new V1Container();
    v1ControllerWithPodTemplate.configureContainerResources(actual, config, resources, isExecutor);
    Assert.assertEquals("Container Resources are set from CLI.", expected, actual);
  }

  @Test
  public void testMountVolumeIfPresent() {
    final String pathDefault = "config-host-volume-path";
    final String pathNameDefault = "config-host-volume-name";
    final Config configWithVolumes = Config.newBuilder()
        .put(KubernetesContext.KUBERNETES_CONTAINER_VOLUME_MOUNT_NAME, pathNameDefault)
        .put(KubernetesContext.KUBERNETES_CONTAINER_VOLUME_MOUNT_PATH, pathDefault)
        .build();
    final V1Controller controllerWithMounts = new V1Controller(configWithVolumes, RUNTIME);
    final V1VolumeMount volumeDefault = new V1VolumeMountBuilder()
        .withName(pathNameDefault)
        .withMountPath(pathDefault)
        .build();
    final V1VolumeMount volumeCustom = new V1VolumeMountBuilder()
        .withName("custom-volume-mount")
        .withMountPath("should-be-kept")
        .build();

    final List<V1VolumeMount> expectedMountsDefault = Collections.singletonList(volumeDefault);
    final List<V1VolumeMount> expectedMountsCustom = Arrays.asList(volumeCustom, volumeDefault);
    final List<V1VolumeMount> volumeMountsCustomList = Arrays.asList(
        new V1VolumeMountBuilder()
            .withName(pathNameDefault)
            .withMountPath("should-be-replaced")
            .build(),
        volumeCustom
    );

    // No Volume Mounts set.
    V1Controller controllerDoNotSetMounts = new V1Controller(Config.newBuilder().build(), RUNTIME);
    V1Container containerNoSetMounts = new V1Container();
    controllerDoNotSetMounts.mountVolumeIfPresent(containerNoSetMounts);
    Assert.assertNull(containerNoSetMounts.getVolumeMounts());

    // Default. Null Volume Mounts.
    V1Container containerNull = new V1ContainerBuilder().build();
    controllerWithMounts.mountVolumeIfPresent(containerNull);
    Assert.assertTrue("Default VOLUME MOUNTS should be set in container with null VOLUME MOUNTS",
        CollectionUtils.containsAll(expectedMountsDefault, containerNull.getVolumeMounts()));

    // Empty Volume Mounts.
    V1Container containerEmpty = new V1ContainerBuilder()
        .withVolumeMounts(new LinkedList<>())
        .build();
    controllerWithMounts.mountVolumeIfPresent(containerEmpty);
    Assert.assertTrue("Default VOLUME MOUNTS should be set in container with empty VOLUME MOUNTS",
        CollectionUtils.containsAll(expectedMountsDefault, containerEmpty.getVolumeMounts()));

    // Custom Volume Mounts.
    V1Container containerCustom = new V1ContainerBuilder()
        .withVolumeMounts(volumeMountsCustomList)
        .build();
    controllerWithMounts.mountVolumeIfPresent(containerCustom);
    Assert.assertTrue("Default VOLUME MOUNTS should be set in container with custom VOLUME MOUNTS",
        CollectionUtils.containsAll(expectedMountsCustom, containerCustom.getVolumeMounts()));
  }

  @Test
  public void testConfigureTolerations() {
    final V1Toleration keptToleration = new V1Toleration()
        .key("kept toleration")
        .operator("Some Operator")
        .effect("Some Effect")
        .tolerationSeconds(5L);
    final List<V1Toleration> expectedTolerationBase =
        Collections.unmodifiableList(V1Controller.getTolerations());
    final List<V1Toleration> inputTolerationsBase = Collections.unmodifiableList(
        Arrays.asList(
            new V1Toleration()
                .key(KubernetesConstants.TOLERATIONS.get(0)).operator("replace").effect("replace"),
            new V1Toleration()
                .key(KubernetesConstants.TOLERATIONS.get(1)).operator("replace").effect("replace"),
            keptToleration
        )
    );

    // Null Tolerations. This is the default case.
    final V1PodSpec podSpecNullTolerations = new V1PodSpecBuilder().build();
    v1ControllerWithPodTemplate.configureTolerations(podSpecNullTolerations);
    Assert.assertTrue("Pod Spec has null TOLERATIONS and should be set to Heron's defaults",
        CollectionUtils.containsAll(podSpecNullTolerations.getTolerations(),
            expectedTolerationBase));

    // Empty Tolerations.
    final V1PodSpec podSpecWithEmptyTolerations = new V1PodSpecBuilder()
        .withTolerations(new LinkedList<>())
        .build();
    v1ControllerWithPodTemplate.configureTolerations(podSpecWithEmptyTolerations);
    Assert.assertTrue("Pod Spec has empty TOLERATIONS and should be set to Heron's defaults",
        CollectionUtils.containsAll(podSpecWithEmptyTolerations.getTolerations(),
            expectedTolerationBase));

    // Toleration overriding.
    final V1PodSpec podSpecWithTolerations = new V1PodSpecBuilder()
        .withTolerations(inputTolerationsBase)
        .build();
    final List<V1Toleration> expectedTolerationsOverriding =
        new LinkedList<>(expectedTolerationBase);
    expectedTolerationsOverriding.add(keptToleration);

    v1ControllerWithPodTemplate.configureTolerations(podSpecWithTolerations);
    Assert.assertTrue("Pod Spec has TOLERATIONS and should be overridden with Heron's defaults",
        CollectionUtils.containsAll(podSpecWithTolerations.getTolerations(),
            expectedTolerationsOverriding));
  }

  @Test
  public void testCreatePersistentVolumeClaims() {
    final String topologyName = "topology-name";
    final String volumeNameOne = "volume-name-one";
    final String volumeNameTwo = "volume-name-two";
    final String volumeNameStatic = "volume-name-static";
    final String claimNameOne = "OnDemand";
    final String claimNameTwo = "claim-name-two";
    final String claimNameStatic = "OnDEmaND";
    final String storageClassName = "storage-class-name";
    final String sizeLimit = "555Gi";
    final String accessModesList = "ReadWriteOnce,ReadOnlyMany,ReadWriteMany";
    final String accessModes = "ReadOnlyMany";
    final String volumeMode = "VolumeMode";
    final String path = "/path/to/mount/";
    final String subPath = "/sub/path/to/mount/";
    final Map<String, Map<VolumeConfigKeys, String>> mapPVCOpts =
        ImmutableMap.of(
            volumeNameOne, new HashMap<VolumeConfigKeys, String>() {
              {
                put(VolumeConfigKeys.claimName, claimNameOne);
                put(VolumeConfigKeys.storageClassName, storageClassName);
                put(VolumeConfigKeys.sizeLimit, sizeLimit);
                put(VolumeConfigKeys.accessModes, accessModesList);
                put(VolumeConfigKeys.volumeMode, volumeMode);
                put(VolumeConfigKeys.path, path);
              }
            },
            volumeNameTwo, new HashMap<VolumeConfigKeys, String>() {
              {
                put(VolumeConfigKeys.claimName, claimNameTwo);
                put(VolumeConfigKeys.storageClassName, storageClassName);
                put(VolumeConfigKeys.sizeLimit, sizeLimit);
                put(VolumeConfigKeys.accessModes, accessModes);
                put(VolumeConfigKeys.volumeMode, volumeMode);
                put(VolumeConfigKeys.path, path);
                put(VolumeConfigKeys.subPath, subPath);
              }
            },
            volumeNameStatic, new HashMap<VolumeConfigKeys, String>() {
              {
                put(VolumeConfigKeys.claimName, claimNameStatic);
                put(VolumeConfigKeys.sizeLimit, sizeLimit);
                put(VolumeConfigKeys.accessModes, accessModes);
                put(VolumeConfigKeys.volumeMode, volumeMode);
                put(VolumeConfigKeys.path, path);
                put(VolumeConfigKeys.subPath, subPath);
              }
            }
        );

    final V1PersistentVolumeClaim claimOne = new V1PersistentVolumeClaimBuilder()
        .withNewMetadata()
          .withName(volumeNameOne)
          .withLabels(V1Controller.getPersistentVolumeClaimLabels(topologyName))
        .endMetadata()
        .withNewSpec()
          .withStorageClassName(storageClassName)
          .withAccessModes(Arrays.asList(accessModesList.split(",")))
          .withVolumeMode(volumeMode)
          .withNewResources()
            .addToRequests("storage", new Quantity(sizeLimit))
          .endResources()
        .endSpec()
        .build();

    final V1PersistentVolumeClaim claimStatic = new V1PersistentVolumeClaimBuilder()
        .withNewMetadata()
          .withName(volumeNameStatic)
          .withLabels(V1Controller.getPersistentVolumeClaimLabels(topologyName))
        .endMetadata()
        .withNewSpec()
          .withStorageClassName("")
          .withAccessModes(Collections.singletonList(accessModes))
          .withVolumeMode(volumeMode)
          .withNewResources()
            .addToRequests("storage", new Quantity(sizeLimit))
          .endResources()
        .endSpec()
        .build();

    final List<V1PersistentVolumeClaim> expectedClaims =
        new LinkedList<>(Arrays.asList(claimOne, claimStatic));

    final List<V1PersistentVolumeClaim> actualClaims =
        v1ControllerWithPodTemplate.createPersistentVolumeClaims(mapPVCOpts);

    Assert.assertEquals("Generated claim sizes match", expectedClaims.size(), actualClaims.size());
    Assert.assertTrue(expectedClaims.containsAll(actualClaims));
  }

  @Test
  public void testCreatePersistentVolumeClaimVolumesAndMounts() {
    final String volumeNameOne = "VolumeNameONE";
    final String volumeNameTwo = "VolumeNameTWO";
    final String claimNameOne = "claim-name-one";
    final String claimNameTwo = "OnDemand";
    final String mountPathOne = "/mount/path/ONE";
    final String mountPathTwo = "/mount/path/TWO";
    final String mountSubPathTwo = "/mount/sub/path/TWO";
    Map<String, Map<VolumeConfigKeys, String>> mapOfOpts =
        ImmutableMap.of(
            volumeNameOne, ImmutableMap.of(
                VolumeConfigKeys.claimName, claimNameOne,
                VolumeConfigKeys.path, mountPathOne),
            volumeNameTwo, ImmutableMap.of(
                VolumeConfigKeys.claimName, claimNameTwo,
                VolumeConfigKeys.path, mountPathTwo,
                VolumeConfigKeys.subPath, mountSubPathTwo)
        );
    final V1Volume volumeOne = new V1VolumeBuilder()
        .withName(volumeNameOne)
        .withNewPersistentVolumeClaim()
          .withClaimName(claimNameOne)
        .endPersistentVolumeClaim()
        .build();
    final V1Volume volumeTwo = new V1VolumeBuilder()
        .withName(volumeNameTwo)
        .withNewPersistentVolumeClaim()
          .withClaimName(claimNameTwo)
        .endPersistentVolumeClaim()
        .build();
    final V1VolumeMount volumeMountOne = new V1VolumeMountBuilder()
        .withName(volumeNameOne)
        .withMountPath(mountPathOne)
        .build();
    final V1VolumeMount volumeMountTwo = new V1VolumeMountBuilder()
        .withName(volumeNameTwo)
        .withMountPath(mountPathTwo)
        .withSubPath(mountSubPathTwo)
        .build();

    // Test case container.
    // Input: Map of Volume configurations.
    // Output: The expected lists of Volumes and Volume Mounts.
    final List<TestTuple<Map<String, Map<VolumeConfigKeys, String>>,
        Pair<List<V1Volume>, List<V1VolumeMount>>>> testCases = new LinkedList<>();

    // Default case: No PVC provided.
    testCases.add(new TestTuple<>("Generated an empty list of Volumes", new HashMap<>(),
        new Pair<>(new LinkedList<>(), new LinkedList<>())));

    // PVC Provided.
    final Pair<List<V1Volume>, List<V1VolumeMount>> expectedFull =
        new Pair<>(
            new LinkedList<>(Arrays.asList(volumeOne, volumeTwo)),
            new LinkedList<>(Arrays.asList(volumeMountOne, volumeMountTwo)));
    testCases.add(new TestTuple<>("Generated a list of Volumes", mapOfOpts,
        new Pair<>(expectedFull.first, expectedFull.second)));

    // Testing loop.
    for (TestTuple<Map<String, Map<VolumeConfigKeys, String>>,
             Pair<List<V1Volume>, List<V1VolumeMount>>> testCase : testCases) {
      List<V1Volume> actualVolume = new LinkedList<>();
      List<V1VolumeMount> actualVolumeMount = new LinkedList<>();
      v1ControllerPodTemplate.createVolumeAndMountsPersistentVolumeClaimCLI(testCase.input,
          actualVolume, actualVolumeMount);

      Assert.assertTrue(testCase.description,
          (testCase.expected.first).containsAll(actualVolume));
      Assert.assertTrue(testCase.description + " Mounts",
          (testCase.expected.second).containsAll(actualVolumeMount));
    }
  }

  @Test
  public void testConfigurePodWithVolumesAndMountsFromCLI() {
    final String volumeNameClashing = "clashing-volume";
    final String volumeMountNameClashing = "original-volume-mount";
    V1Volume baseVolume = new V1VolumeBuilder()
        .withName(volumeNameClashing)
        .withNewPersistentVolumeClaim()
        .withClaimName("Original Base Claim Name")
        .endPersistentVolumeClaim()
        .build();
    V1VolumeMount baseVolumeMount = new V1VolumeMountBuilder()
        .withName(volumeMountNameClashing)
        .withMountPath("/original/mount/path")
        .build();
    V1Volume clashingVolume = new V1VolumeBuilder()
        .withName(volumeNameClashing)
        .withNewPersistentVolumeClaim()
        .withClaimName("Clashing Claim Replaced")
        .endPersistentVolumeClaim()
        .build();
    V1VolumeMount clashingVolumeMount = new V1VolumeMountBuilder()
        .withName(volumeMountNameClashing)
        .withMountPath("/clashing/mount/path")
        .build();
    V1Volume secondaryVolume = new V1VolumeBuilder()
        .withName("secondary-volume")
        .withNewPersistentVolumeClaim()
        .withClaimName("Original Secondary Claim Name")
        .endPersistentVolumeClaim()
        .build();
    V1VolumeMount secondaryVolumeMount = new V1VolumeMountBuilder()
        .withName("secondary-volume-mount")
        .withMountPath("/secondary/mount/path")
        .build();

    // Test case container.
    // Input: [0] Pod Spec to modify, [1] Heron container to modify, [2] List of Volumes
    // [3] List of Volume Mounts.
    // Output: The expected <V1PodSpec> and <V1Container>.
    final List<TestTuple<Object[], Pair<V1PodSpec, V1Container>>> testCases = new LinkedList<>();

    // No Persistent Volume Claim.
    final V1PodSpec podSpecEmptyCase = new V1PodSpecBuilder().withVolumes(baseVolume).build();
    final V1Container executorEmptyCase =
        new V1ContainerBuilder().withVolumeMounts(baseVolumeMount).build();
    final V1PodSpec expectedEmptyPodSpec = new V1PodSpecBuilder().withVolumes(baseVolume).build();
    final V1Container expectedEmptyExecutor =
        new V1ContainerBuilder().withVolumeMounts(baseVolumeMount).build();

    testCases.add(new TestTuple<>("Empty",
        new Object[]{podSpecEmptyCase, executorEmptyCase, new LinkedList<>(), new LinkedList<>()},
        new Pair<>(expectedEmptyPodSpec, expectedEmptyExecutor)));

    // Non-clashing Persistent Volume Claim.
    final V1PodSpec podSpecNoClashCase = new V1PodSpecBuilder()
        .withVolumes(baseVolume)
        .build();
    final V1Container executorNoClashCase = new V1ContainerBuilder()
        .withVolumeMounts(baseVolumeMount)
        .build();
    final V1PodSpec expectedNoClashPodSpec = new V1PodSpecBuilder()
        .addToVolumes(baseVolume)
        .addToVolumes(secondaryVolume)
        .build();
    final V1Container expectedNoClashExecutor = new V1ContainerBuilder()
        .addToVolumeMounts(baseVolumeMount)
        .addToVolumeMounts(secondaryVolumeMount)
        .build();

    testCases.add(new TestTuple<>("No Clash",
        new Object[]{podSpecNoClashCase, executorNoClashCase,
            Collections.singletonList(secondaryVolume),
            Collections.singletonList(secondaryVolumeMount)},
        new Pair<>(expectedNoClashPodSpec, expectedNoClashExecutor)));

    // Clashing Persistent Volume Claim.
    final V1PodSpec podSpecClashCase = new V1PodSpecBuilder()
        .withVolumes(baseVolume)
        .build();
    final V1Container executorClashCase = new V1ContainerBuilder()
        .withVolumeMounts(baseVolumeMount)
        .build();
    final V1PodSpec expectedClashPodSpec = new V1PodSpecBuilder()
        .addToVolumes(clashingVolume)
        .addToVolumes(secondaryVolume)
        .build();
    final V1Container expectedClashExecutor = new V1ContainerBuilder()
        .addToVolumeMounts(clashingVolumeMount)
        .addToVolumeMounts(secondaryVolumeMount)
        .build();

    testCases.add(new TestTuple<>("Clashing",
        new Object[]{podSpecClashCase, executorClashCase,
            Arrays.asList(clashingVolume, secondaryVolume),
            Arrays.asList(clashingVolumeMount, secondaryVolumeMount)},
        new Pair<>(expectedClashPodSpec, expectedClashExecutor)));

    // Testing loop.
    for (TestTuple<Object[], Pair<V1PodSpec, V1Container>> testCase : testCases) {
      v1ControllerWithPodTemplate
          .configurePodWithVolumesAndMountsFromCLI((V1PodSpec) testCase.input[0],
              (V1Container) testCase.input[1], (List<V1Volume>) testCase.input[2],
              (List<V1VolumeMount>) testCase.input[3]);

      Assert.assertEquals("Pod Specs match " + testCase.description,
          testCase.input[0], testCase.expected.first);
      Assert.assertEquals("Executors match " + testCase.description,
          testCase.input[1], testCase.expected.second);
    }
  }

  @Test
  public void testSetShardIdEnvironmentVariableCommand() {

    List<TestTuple<Boolean, String>> testCases = new LinkedList<>();

    testCases.add(new TestTuple<>("Executor command is set correctly",
        true, "SHARD_ID=$((${POD_NAME##*-} + 1)) && echo shardId=${SHARD_ID}"));
    testCases.add(new TestTuple<>("Manager command is set correctly",
        false, "SHARD_ID=${POD_NAME##*-} && echo shardId=${SHARD_ID}"));

    for (TestTuple<Boolean, String> testCase : testCases) {
      Assert.assertEquals(testCase.description, testCase.expected,
          v1ControllerWithPodTemplate.setShardIdEnvironmentVariableCommand(testCase.input));
    }
  }

  @Test
  public void testCreateResourcesRequirement() {
    final String managerCpuLimit = "3000m";
    final String managerMemLimit = "256Gi";
    final Quantity memory = Quantity.fromString(managerMemLimit);
    final Quantity cpu = Quantity.fromString(managerCpuLimit);
    final List<TestTuple<Map<String, String>, Map<String, Quantity>>> testCases =
        new LinkedList<>();

    // No input.
    Map<String, String> inputEmpty = new HashMap<>();
    testCases.add(new TestTuple<>("Empty input.", inputEmpty, new HashMap<>()));

    // Only memory.
    Map<String, String> inputMemory = new HashMap<String, String>() {
      {
        put(KubernetesConstants.MEMORY, managerMemLimit);
      }
    };
    Map<String, Quantity> expectedMemory = new HashMap<String, Quantity>() {
      {
        put(KubernetesConstants.MEMORY, memory);
      }
    };
    testCases.add(new TestTuple<>("Only memory input.", inputMemory, expectedMemory));

    // Only CPU.
    Map<String, String> inputCPU = new HashMap<String, String>() {
      {
        put(KubernetesConstants.CPU, managerCpuLimit);
      }
    };
    Map<String, Quantity> expectedCPU = new HashMap<String, Quantity>() {
      {
        put(KubernetesConstants.CPU, cpu);
      }
    };
    testCases.add(new TestTuple<>("Only CPU input.", inputCPU, expectedCPU));

    // CPU and memory.
    Map<String, String> inputMemoryCPU = new HashMap<String, String>() {
      {
        put(KubernetesConstants.MEMORY, managerMemLimit);
        put(KubernetesConstants.CPU, managerCpuLimit);
      }
    };
    Map<String, Quantity> expectedMemoryCPU = new HashMap<String, Quantity>() {
      {
        put(KubernetesConstants.MEMORY, memory);
        put(KubernetesConstants.CPU, cpu);
      }
    };
    testCases.add(new TestTuple<>("Memory and CPU input.", inputMemoryCPU, expectedMemoryCPU));

    // Invalid.
    Map<String, String> inputInvalid = new HashMap<String, String>() {
      {
        put("invalid input", "will not be ignored");
        put(KubernetesConstants.CPU, managerCpuLimit);
      }
    };
    Map<String, Quantity> expectedInvalid = new HashMap<String, Quantity>() {
      {
        put(KubernetesConstants.CPU, cpu);
      }
    };
    testCases.add(new TestTuple<>("Invalid input.", inputInvalid, expectedInvalid));

    // Test loop.
    for (TestTuple<Map<String, String>, Map<String, Quantity>> testCase : testCases) {
      Map<String, Quantity> actual =
          v1ControllerPodTemplate.createResourcesRequirement(testCase.input);
      Assert.assertEquals(testCase.description, testCase.expected, actual);
    }
  }

  @Test
  public void testCreateVolumeAndMountsEmptyDirCLI() {
    final String volumeName = "volume-name-empty-dir";
    final String medium = "Memory";
    final String sizeLimit = "1Gi";
    final String path = "/path/to/mount";
    final String subPath = "/sub/path/to/mount";

    // Empty Dir.
    final Map<String, Map<VolumeConfigKeys, String>> config =
        ImmutableMap.of(volumeName, new HashMap<VolumeConfigKeys, String>() {
          {
            put(VolumeConfigKeys.sizeLimit, sizeLimit);
            put(VolumeConfigKeys.medium, "Memory");
            put(VolumeConfigKeys.path, path);
            put(VolumeConfigKeys.subPath, subPath);
          }
        });
    final List<V1Volume> expectedVolumes = Collections.singletonList(
        new V1VolumeBuilder()
            .withName(volumeName)
            .withNewEmptyDir()
              .withMedium(medium)
              .withNewSizeLimit(sizeLimit)
            .endEmptyDir()
            .build()
    );
    final List<V1VolumeMount> expectedMounts = Collections.singletonList(
        new V1VolumeMountBuilder()
            .withName(volumeName)
              .withMountPath(path)
              .withSubPath(subPath)
            .build()
    );

    List<V1Volume> actualVolumes = new LinkedList<>();
    List<V1VolumeMount> actualMounts = new LinkedList<>();
    v1ControllerPodTemplate.createVolumeAndMountsEmptyDirCLI(config, actualVolumes, actualMounts);
    Assert.assertEquals("Empty Dir Volume populated", expectedVolumes, actualVolumes);
    Assert.assertEquals("Empty Dir Volume Mount populated", expectedMounts, actualMounts);
  }

  @Test
  public void testCreateVolumeAndMountsHostPathCLI() {
    final String volumeName = "volume-name-host-path";
    final String type = "DirectoryOrCreate";
    final String pathOnHost = "path.on.host";
    final String path = "/path/to/mount";
    final String subPath = "/sub/path/to/mount";

    // Host Path.
    final Map<String, Map<VolumeConfigKeys, String>> config =
        ImmutableMap.of(volumeName, new HashMap<VolumeConfigKeys, String>() {
          {
            put(VolumeConfigKeys.type, type);
            put(VolumeConfigKeys.pathOnHost, pathOnHost);
            put(VolumeConfigKeys.path, path);
            put(VolumeConfigKeys.subPath, subPath);
          }
        });
    final List<V1Volume> expectedVolumes = Collections.singletonList(
        new V1VolumeBuilder()
            .withName(volumeName)
            .withNewHostPath()
              .withNewType(type)
              .withNewPath(pathOnHost)
            .endHostPath()
            .build()
    );
    final List<V1VolumeMount> expectedMounts = Collections.singletonList(
        new V1VolumeMountBuilder()
            .withName(volumeName)
              .withMountPath(path)
              .withSubPath(subPath)
            .build()
    );

    List<V1Volume> actualVolumes = new LinkedList<>();
    List<V1VolumeMount> actualMounts = new LinkedList<>();
    v1ControllerPodTemplate.createVolumeAndMountsHostPathCLI(config, actualVolumes, actualMounts);
    Assert.assertEquals("Host Path Volume populated", expectedVolumes, actualVolumes);
    Assert.assertEquals("Host Path Volume Mount populated", expectedMounts, actualMounts);
  }

  @Test
  public void testCreateVolumeAndMountsNFSCLI() {
    final String volumeName = "volume-name-nfs";
    final String server = "nfs.server.address";
    final String pathOnNFS = "path.on.host";
    final String readOnly = "true";
    final String path = "/path/to/mount";
    final String subPath = "/sub/path/to/mount";

    // NFS.
    final Map<String, Map<VolumeConfigKeys, String>> config =
        ImmutableMap.of(volumeName, new HashMap<VolumeConfigKeys, String>() {
          {
            put(VolumeConfigKeys.server, server);
            put(VolumeConfigKeys.readOnly, readOnly);
            put(VolumeConfigKeys.pathOnNFS, pathOnNFS);
            put(VolumeConfigKeys.path, path);
            put(VolumeConfigKeys.subPath, subPath);
          }
        });
    final List<V1Volume> expectedVolumes = Collections.singletonList(
        new V1VolumeBuilder()
            .withName(volumeName)
            .withNewNfs()
              .withServer(server)
              .withPath(pathOnNFS)
              .withReadOnly(Boolean.parseBoolean(readOnly))
            .endNfs()
            .build()
    );
    final List<V1VolumeMount> expectedMounts = Collections.singletonList(
        new V1VolumeMountBuilder()
            .withName(volumeName)
            .withMountPath(path)
            .withSubPath(subPath)
            .withReadOnly(true)
            .build()
    );

    List<V1Volume> actualVolumes = new LinkedList<>();
    List<V1VolumeMount> actualMounts = new LinkedList<>();
    v1ControllerPodTemplate.createVolumeAndMountsNFSCLI(config, actualVolumes, actualMounts);
    Assert.assertEquals("NFS Volume populated", expectedVolumes, actualVolumes);
    Assert.assertEquals("NFS Volume Mount populated", expectedMounts, actualMounts);
  }
}
