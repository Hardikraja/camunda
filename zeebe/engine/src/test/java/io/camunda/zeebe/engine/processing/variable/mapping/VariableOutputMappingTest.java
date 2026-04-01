/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Camunda License 1.0. You may not use this file
 * except in compliance with the Camunda License 1.0.
 */
package io.camunda.zeebe.engine.processing.variable.mapping;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.camunda.zeebe.engine.util.EngineRule;
import io.camunda.zeebe.model.bpmn.Bpmn;
import io.camunda.zeebe.model.bpmn.BpmnModelInstance;
import io.camunda.zeebe.protocol.record.intent.ProcessInstanceIntent;
import io.camunda.zeebe.protocol.record.intent.VariableIntent;
import io.camunda.zeebe.protocol.record.value.BpmnElementType;
import io.camunda.zeebe.test.util.record.RecordingExporter;
import io.camunda.zeebe.test.util.record.RecordingExporterTestWatcher;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.ClassRule;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;

/** Tests for variable output mapping using path based expression merged. */
public final class VariableOutputMappingTest {

  @ClassRule public static final EngineRule ENGINE_RULE = EngineRule.singlePartition();

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
  private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

  @Rule
  public final RecordingExporterTestWatcher recordingExporterTestWatcher =
      new RecordingExporterTestWatcher();

  // ===========================================================================
  // Non-nested (flat) output mappings
  // ===========================================================================

  // ---------------------------------------------------------------------------
  // F1. Simple flat mapping: source → different target name
  // ---------------------------------------------------------------------------

  @Test
  public void shouldMapFlatVariableToNewName() {
    // given
    final BpmnModelInstance process =
        Bpmn.createExecutableProcess("flatRename")
            .startEvent()
            .serviceTask("task")
            .zeebeJobType("flatRename")
            .zeebeOutputExpression("x", "y")
            .endEvent()
            .done();

    ENGINE_RULE.deployment().withXmlResource(process).deploy();

    final long processInstanceKey =
        ENGINE_RULE
            .processInstance()
            .ofBpmnProcessId("flatRename")
            .withVariables(Map.of("x", 0))
            .create();

    // when
    ENGINE_RULE
        .job()
        .ofInstance(processInstanceKey)
        .withType("flatRename")
        .withVariables(Map.of("x", 42))
        .complete();

    // then
    awaitProcessCompletion(processInstanceKey);

    final String json =
        RecordingExporter.variableRecords(VariableIntent.CREATED)
            .withProcessInstanceKey(processInstanceKey)
            .withName("y")
            .getFirst()
            .getValue()
            .getValue();

    assertThat(json).isEqualTo("42");
  }

  // ---------------------------------------------------------------------------
  // F2. Flat identity mapping: x → x
  // ---------------------------------------------------------------------------

  @Test
  public void shouldMapFlatVariableToSameName() {
    // given
    final BpmnModelInstance process =
        Bpmn.createExecutableProcess("flatIdentity")
            .startEvent()
            .serviceTask("task")
            .zeebeJobType("flatIdentity")
            .zeebeOutputExpression("x", "x")
            .endEvent()
            .done();

    ENGINE_RULE.deployment().withXmlResource(process).deploy();

    final long processInstanceKey =
        ENGINE_RULE
            .processInstance()
            .ofBpmnProcessId("flatIdentity")
            .withVariables(Map.of("x", 1))
            .create();

    // when
    ENGINE_RULE
        .job()
        .ofInstance(processInstanceKey)
        .withType("flatIdentity")
        .withVariables(Map.of("x", 99))
        .complete();

    // then
    awaitProcessCompletion(processInstanceKey);

    final String json =
        RecordingExporter.variableRecords(VariableIntent.UPDATED)
            .withProcessInstanceKey(processInstanceKey)
            .withName("x")
            .getFirst()
            .getValue()
            .getValue();

    assertThat(json).isEqualTo("99");
  }

  // ---------------------------------------------------------------------------
  // F3. Multiple flat mappings
  // ---------------------------------------------------------------------------

  @Test
  public void shouldMapMultipleFlatVariables() {
    // given
    final BpmnModelInstance process =
        Bpmn.createExecutableProcess("flatMulti")
            .startEvent()
            .serviceTask("task")
            .zeebeJobType("flatMulti")
            .zeebeOutputExpression("a", "x")
            .zeebeOutputExpression("b", "y")
            .endEvent()
            .done();

    ENGINE_RULE.deployment().withXmlResource(process).deploy();

    final long processInstanceKey =
        ENGINE_RULE
            .processInstance()
            .ofBpmnProcessId("flatMulti")
            .withVariables(Map.of("a", 0, "b", 0))
            .create();

    // when
    ENGINE_RULE
        .job()
        .ofInstance(processInstanceKey)
        .withType("flatMulti")
        .withVariables(Map.of("a", 10, "b", 20))
        .complete();

    // then
    awaitProcessCompletion(processInstanceKey);

    assertThat(
            RecordingExporter.variableRecords(VariableIntent.CREATED)
                .withProcessInstanceKey(processInstanceKey)
                .withName("x")
                .getFirst()
                .getValue()
                .getValue())
        .isEqualTo("10");

    assertThat(
            RecordingExporter.variableRecords(VariableIntent.CREATED)
                .withProcessInstanceKey(processInstanceKey)
                .withName("y")
                .getFirst()
                .getValue()
                .getValue())
        .isEqualTo("20");
  }

  // ---------------------------------------------------------------------------
  // F4. Flat mapping with a JSON object value (whole object, not nested path)
  // ---------------------------------------------------------------------------

  @Test
  public void shouldMapFlatVariableContainingObjectValue() throws JsonProcessingException {
    // given — maps an entire object as a flat variable (no dot in target)
    final BpmnModelInstance process =
        Bpmn.createExecutableProcess("flatObject")
            .startEvent()
            .serviceTask("task")
            .zeebeJobType("flatObject")
            .zeebeOutputExpression("payload", "result")
            .endEvent()
            .done();

    ENGINE_RULE.deployment().withXmlResource(process).deploy();

    final long processInstanceKey =
        ENGINE_RULE
            .processInstance()
            .ofBpmnProcessId("flatObject")
            .withVariables(Map.of("payload", Map.of("old", true)))
            .create();

    // when
    ENGINE_RULE
        .job()
        .ofInstance(processInstanceKey)
        .withType("flatObject")
        .withVariables(Map.of("payload", Map.of("key1", "val1", "key2", 42)))
        .complete();

    // then
    awaitProcessCompletion(processInstanceKey);

    final String json =
        RecordingExporter.variableRecords(VariableIntent.CREATED)
            .withProcessInstanceKey(processInstanceKey)
            .withName("result")
            .getFirst()
            .getValue()
            .getValue();

    final Map<String, Object> result = OBJECT_MAPPER.readValue(json, MAP_TYPE);
    assertThat(result).containsEntry("key1", "val1").containsEntry("key2", 42);
  }

  // ---------------------------------------------------------------------------
  // F5. Flat mapping with computed FEEL expression source
  // ---------------------------------------------------------------------------

  @Test
  public void shouldMapComputedExpressionToFlatTarget() {
    // given
    final BpmnModelInstance process =
        Bpmn.createExecutableProcess("flatComputed")
            .startEvent()
            .serviceTask("task")
            .zeebeJobType("flatComputed")
            .zeebeOutputExpression("a + b", "sum")
            .endEvent()
            .done();

    ENGINE_RULE.deployment().withXmlResource(process).deploy();

    final long processInstanceKey =
        ENGINE_RULE
            .processInstance()
            .ofBpmnProcessId("flatComputed")
            .withVariables(Map.of("a", 0, "b", 0))
            .create();

    // when
    ENGINE_RULE
        .job()
        .ofInstance(processInstanceKey)
        .withType("flatComputed")
        .withVariables(Map.of("a", 7, "b", 3))
        .complete();

    // then
    awaitProcessCompletion(processInstanceKey);

    final String json =
        RecordingExporter.variableRecords(VariableIntent.CREATED)
            .withProcessInstanceKey(processInstanceKey)
            .withName("sum")
            .getFirst()
            .getValue()
            .getValue();

    assertThat(json).isEqualTo("10");
  }

  // ---------------------------------------------------------------------------
  // F6. Flat mapping with string value
  // ---------------------------------------------------------------------------

  @Test
  public void shouldMapFlatStringVariable() {
    // given
    final BpmnModelInstance process =
        Bpmn.createExecutableProcess("flatString")
            .startEvent()
            .serviceTask("task")
            .zeebeJobType("flatString")
            .zeebeOutputExpression("message", "output")
            .endEvent()
            .done();

    ENGINE_RULE.deployment().withXmlResource(process).deploy();

    final long processInstanceKey =
        ENGINE_RULE
            .processInstance()
            .ofBpmnProcessId("flatString")
            .withVariables(Map.of("message", "initial"))
            .create();

    // when
    ENGINE_RULE
        .job()
        .ofInstance(processInstanceKey)
        .withType("flatString")
        .withVariables(Map.of("message", "hello world"))
        .complete();

    // then
    awaitProcessCompletion(processInstanceKey);

    final String json =
        RecordingExporter.variableRecords(VariableIntent.CREATED)
            .withProcessInstanceKey(processInstanceKey)
            .withName("output")
            .getFirst()
            .getValue()
            .getValue();

    assertThat(json).isEqualTo("\"hello world\"");
  }

  // ---------------------------------------------------------------------------
  // F7. Flat mapping with boolean value
  // ---------------------------------------------------------------------------

  @Test
  public void shouldMapFlatBooleanVariable() {
    // given
    final BpmnModelInstance process =
        Bpmn.createExecutableProcess("flatBool")
            .startEvent()
            .serviceTask("task")
            .zeebeJobType("flatBool")
            .zeebeOutputExpression("done", "completed")
            .endEvent()
            .done();

    ENGINE_RULE.deployment().withXmlResource(process).deploy();

    final long processInstanceKey =
        ENGINE_RULE
            .processInstance()
            .ofBpmnProcessId("flatBool")
            .withVariables(Map.of("done", false))
            .create();

    // when
    ENGINE_RULE
        .job()
        .ofInstance(processInstanceKey)
        .withType("flatBool")
        .withVariables(Map.of("done", true))
        .complete();

    // then
    awaitProcessCompletion(processInstanceKey);

    final String json =
        RecordingExporter.variableRecords(VariableIntent.CREATED)
            .withProcessInstanceKey(processInstanceKey)
            .withName("completed")
            .getFirst()
            .getValue()
            .getValue();

    assertThat(json).isEqualTo("true");
  }

  // ---------------------------------------------------------------------------
  // F8. Flat mapping with list value
  // ---------------------------------------------------------------------------

  @Test
  public void shouldMapFlatListVariable() {
    // given
    final BpmnModelInstance process =
        Bpmn.createExecutableProcess("flatList")
            .startEvent()
            .serviceTask("task")
            .zeebeJobType("flatList")
            .zeebeOutputExpression("items", "result")
            .endEvent()
            .done();

    ENGINE_RULE.deployment().withXmlResource(process).deploy();

    final long processInstanceKey =
        ENGINE_RULE
            .processInstance()
            .ofBpmnProcessId("flatList")
            .withVariables(Map.of("items", List.of()))
            .create();

    // when
    ENGINE_RULE
        .job()
        .ofInstance(processInstanceKey)
        .withType("flatList")
        .withVariables(Map.of("items", List.of(1, 2, 3)))
        .complete();

    // then
    awaitProcessCompletion(processInstanceKey);

    final String json =
        RecordingExporter.variableRecords(VariableIntent.CREATED)
            .withProcessInstanceKey(processInstanceKey)
            .withName("result")
            .getFirst()
            .getValue()
            .getValue();

    assertThat(json).isEqualTo("[1,2,3]");
  }

  // ===========================================================================
  // Nested output mappings (existing tests 1–14)
  // ===========================================================================

  // ---------------------------------------------------------------------------
  // 1. Core scenario: unmapped sibling keys preserved in deeply nested objects
  // ---------------------------------------------------------------------------

  @Test
  public void shouldPreserveUnmappedSiblingKeysInNestedOutput() throws JsonProcessingException {
    // given — parent scope has processData with salary, humanTask, fault
    final BpmnModelInstance process =
        Bpmn.createExecutableProcess("preserveSibling")
            .startEvent()
            .serviceTask("task")
            .zeebeJobType("preserveSibling")
            .zeebeOutputExpression("processData.humanTask.outcome", "processData.humanTask.outcome")
            .zeebeOutputExpression("processData.fault.errors", "processData.fault.errors")
            .endEvent()
            .done();

    ENGINE_RULE.deployment().withXmlResource(process).deploy();

    final Map<String, Object> initialVariables =
        Map.of(
            "processData",
            Map.of(
                "salary", Map.of("pensionable", 1000, "beneficial", 2000),
                "humanTask", Map.of("outcome", "OLD_OUTCOME"),
                "fault", Map.of("errors", List.of())));

    final long processInstanceKey =
        ENGINE_RULE
            .processInstance()
            .ofBpmnProcessId("preserveSibling")
            .withVariables(initialVariables)
            .create();

    // when — job completes with modified salary (NOT mapped) and mapped keys
    ENGINE_RULE
        .job()
        .ofInstance(processInstanceKey)
        .withType("preserveSibling")
        .withVariables(
            Map.of(
                "processData",
                Map.of(
                    "salary", Map.of("pensionable", 9999, "beneficial", 9999),
                    "humanTask", Map.of("outcome", "NEW_OUTCOME"),
                    "fault", Map.of("errors", List.of("ERR1")))))
        .complete();

    // then
    final Map<String, Object> result = awaitUpdatedVariable(processInstanceKey, "processData");

    assertThat(nested(result, "humanTask", "outcome")).isEqualTo("NEW_OUTCOME");
    assertThat(nested(result, "fault", "errors")).isEqualTo(List.of("ERR1"));
    // salary must remain from the PARENT scope, not the job variables
    assertThat(nested(result, "salary", "pensionable")).isEqualTo(1000);
    assertThat(nested(result, "salary", "beneficial")).isEqualTo(2000);
  }

  // ---------------------------------------------------------------------------
  // 2. Single nested path mapping — sibling keys at the same nesting level
  // ---------------------------------------------------------------------------

  @Test
  public void shouldPreserveAllSiblingsWhenOnlyOneNestedKeyIsMapped()
      throws JsonProcessingException {
    // given — config has a, b, c; only c is mapped
    final BpmnModelInstance process =
        Bpmn.createExecutableProcess("singleNestedKey")
            .startEvent()
            .serviceTask("task")
            .zeebeJobType("singleNestedKey")
            .zeebeOutputExpression("config.c", "config.c")
            .endEvent()
            .done();

    ENGINE_RULE.deployment().withXmlResource(process).deploy();

    final long processInstanceKey =
        ENGINE_RULE
            .processInstance()
            .ofBpmnProcessId("singleNestedKey")
            .withVariables(Map.of("config", Map.of("a", 1, "b", 2, "c", 3)))
            .create();

    // when
    ENGINE_RULE
        .job()
        .ofInstance(processInstanceKey)
        .withType("singleNestedKey")
        .withVariables(Map.of("config", Map.of("a", 100, "b", 200, "c", 42)))
        .complete();

    // then
    final Map<String, Object> result = awaitUpdatedVariable(processInstanceKey, "config");

    // mapped key updated
    assertThat(result.get("c")).isEqualTo(42);
    // unmapped keys preserved from parent
    assertThat(result.get("a")).isEqualTo(1);
    assertThat(result.get("b")).isEqualTo(2);
  }

  // ---------------------------------------------------------------------------
  // 3. Three-level deep nesting: a.b.c mapped, a.b.d preserved
  // ---------------------------------------------------------------------------

  @Test
  public void shouldPreserveSiblingKeysAtThreeLevelsDeep() throws JsonProcessingException {
    // given
    final BpmnModelInstance process =
        Bpmn.createExecutableProcess("threeLevels")
            .startEvent()
            .serviceTask("task")
            .zeebeJobType("threeLevels")
            .zeebeOutputExpression("data.level1.level2.mapped", "data.level1.level2.mapped")
            .endEvent()
            .done();

    ENGINE_RULE.deployment().withXmlResource(process).deploy();

    final long processInstanceKey =
        ENGINE_RULE
            .processInstance()
            .ofBpmnProcessId("threeLevels")
            .withVariables(
                Map.of(
                    "data",
                    Map.of(
                        "level1",
                        Map.of(
                            "level2",
                            Map.of("mapped", "old", "preserved", "keep"),
                            "sibling",
                            "untouched"))))
            .create();

    // when
    ENGINE_RULE
        .job()
        .ofInstance(processInstanceKey)
        .withType("threeLevels")
        .withVariables(
            Map.of(
                "data",
                Map.of("level1", Map.of("level2", Map.of("mapped", "new", "preserved", "gone")))))
        .complete();

    // then
    final Map<String, Object> result = awaitUpdatedVariable(processInstanceKey, "data");

    assertThat(nested(result, "level1", "level2", "mapped")).isEqualTo("new");
    assertThat(nested(result, "level1", "level2", "preserved")).isEqualTo("keep");
    assertThat(nested(result, "level1", "sibling")).isEqualTo("untouched");
  }

  // ---------------------------------------------------------------------------
  // 4. Multiple top-level variables, each with nested mappings
  // ---------------------------------------------------------------------------

  @Test
  public void shouldPreserveUnmappedKeysAcrossMultipleTopLevelVariables()
      throws JsonProcessingException {
    // given — two top-level vars: orderData and customerData
    final BpmnModelInstance process =
        Bpmn.createExecutableProcess("multiTopLevel")
            .startEvent()
            .serviceTask("task")
            .zeebeJobType("multiTopLevel")
            .zeebeOutputExpression("orderData.status", "orderData.status")
            .zeebeOutputExpression("customerData.email", "customerData.email")
            .endEvent()
            .done();

    ENGINE_RULE.deployment().withXmlResource(process).deploy();

    final long processInstanceKey =
        ENGINE_RULE
            .processInstance()
            .ofBpmnProcessId("multiTopLevel")
            .withVariables(
                Map.of(
                    "orderData", Map.of("status", "PENDING", "amount", 500),
                    "customerData", Map.of("email", "old@test.com", "name", "Alice")))
            .create();

    // when
    ENGINE_RULE
        .job()
        .ofInstance(processInstanceKey)
        .withType("multiTopLevel")
        .withVariables(
            Map.of(
                "orderData", Map.of("status", "SHIPPED", "amount", 999),
                "customerData", Map.of("email", "new@test.com", "name", "Bob")))
        .complete();

    // then
    final Map<String, Object> order = awaitUpdatedVariable(processInstanceKey, "orderData");
    assertThat(order.get("status")).isEqualTo("SHIPPED");
    assertThat(order.get("amount")).isEqualTo(500); // unmapped, preserved from parent

    final Map<String, Object> customer = awaitUpdatedVariable(processInstanceKey, "customerData");
    assertThat(customer.get("email")).isEqualTo("new@test.com");
    assertThat(customer.get("name")).isEqualTo("Alice"); // unmapped, preserved from parent
  }

  // ---------------------------------------------------------------------------
  // 5. Map value from a computed FEEL expression (not simple path copy)
  // ---------------------------------------------------------------------------

  @Test
  public void shouldApplyComputedExpressionToNestedTargetAndPreserveSiblings()
      throws JsonProcessingException {
    // given
    final BpmnModelInstance process =
        Bpmn.createExecutableProcess("computedExpr")
            .startEvent()
            .serviceTask("task")
            .zeebeJobType("computedExpr")
            // Map a computed expression into a nested target
            .zeebeOutputExpression("x + y", "result.sum")
            .endEvent()
            .done();

    ENGINE_RULE.deployment().withXmlResource(process).deploy();

    final long processInstanceKey =
        ENGINE_RULE
            .processInstance()
            .ofBpmnProcessId("computedExpr")
            .withVariables(Map.of("result", Map.of("sum", 0, "label", "initial"), "x", 0, "y", 0))
            .create();

    // when
    ENGINE_RULE
        .job()
        .ofInstance(processInstanceKey)
        .withType("computedExpr")
        .withVariables(Map.of("x", 10, "y", 20))
        .complete();

    // then
    final Map<String, Object> result = awaitUpdatedVariable(processInstanceKey, "result");
    assertThat(result.get("sum")).isEqualTo(30);
    assertThat(result.get("label")).isEqualTo("initial"); // preserved
  }

  // ---------------------------------------------------------------------------
  // 6. Mapping where parent-scope nested variable is null (first-time creation)
  // ---------------------------------------------------------------------------

  @Test
  public void shouldCreateNestedStructureWhenParentVariableIsNull() throws JsonProcessingException {
    // given — processData does not exist on the parent scope
    final BpmnModelInstance process =
        Bpmn.createExecutableProcess("nullParent")
            .startEvent()
            .serviceTask("task")
            .zeebeJobType("nullParent")
            .zeebeOutputExpression("outcome", "processData.humanTask.outcome")
            .endEvent()
            .done();

    ENGINE_RULE.deployment().withXmlResource(process).deploy();

    // No processData variable set initially
    final long processInstanceKey =
        ENGINE_RULE
            .processInstance()
            .ofBpmnProcessId("nullParent")
            .withVariables(Map.of("outcome", "PLACEHOLDER"))
            .create();

    // when
    ENGINE_RULE
        .job()
        .ofInstance(processInstanceKey)
        .withType("nullParent")
        .withVariables(Map.of("outcome", "APPROVED"))
        .complete();

    // then
    RecordingExporter.processInstanceRecords(ProcessInstanceIntent.ELEMENT_COMPLETED)
        .withProcessInstanceKey(processInstanceKey)
        .withElementType(BpmnElementType.PROCESS)
        .await();

    final String processDataJson =
        RecordingExporter.variableRecords()
            .withProcessInstanceKey(processInstanceKey)
            .withName("processData")
            .getFirst()
            .getValue()
            .getValue();

    final Map<String, Object> result = OBJECT_MAPPER.readValue(processDataJson, MAP_TYPE);
    assertThat(nested(result, "humanTask", "outcome")).isEqualTo("APPROVED");
  }

  // ---------------------------------------------------------------------------
  // 7. Mapping a list value into a nested target
  // ---------------------------------------------------------------------------

  @Test
  public void shouldMapListValueIntoNestedTargetAndPreserveSiblings()
      throws JsonProcessingException {
    // given
    final BpmnModelInstance process =
        Bpmn.createExecutableProcess("listValue")
            .startEvent()
            .serviceTask("task")
            .zeebeJobType("listValue")
            .zeebeOutputExpression("data.tags", "data.tags")
            .endEvent()
            .done();

    ENGINE_RULE.deployment().withXmlResource(process).deploy();

    final long processInstanceKey =
        ENGINE_RULE
            .processInstance()
            .ofBpmnProcessId("listValue")
            .withVariables(Map.of("data", Map.of("tags", List.of("a"), "priority", "HIGH")))
            .create();

    // when
    ENGINE_RULE
        .job()
        .ofInstance(processInstanceKey)
        .withType("listValue")
        .withVariables(Map.of("data", Map.of("tags", List.of("a", "b", "c"), "priority", "LOW")))
        .complete();

    // then
    final Map<String, Object> result = awaitUpdatedVariable(processInstanceKey, "data");
    assertThat(result.get("tags")).isEqualTo(List.of("a", "b", "c"));
    assertThat(result.get("priority")).isEqualTo("HIGH"); // preserved
  }

  // ---------------------------------------------------------------------------
  // 8. Mapping with mixed nesting depths in the same element
  // ---------------------------------------------------------------------------

  @Test
  public void shouldHandleMixedNestingDepthMappings() throws JsonProcessingException {
    // given — one flat mapping and one nested mapping on the same top-level variable
    final BpmnModelInstance process =
        Bpmn.createExecutableProcess("mixedDepths")
            .startEvent()
            .serviceTask("task")
            .zeebeJobType("mixedDepths")
            .zeebeOutputExpression("result.status", "result.status")
            .zeebeOutputExpression("result.detail.code", "result.detail.code")
            .endEvent()
            .done();

    ENGINE_RULE.deployment().withXmlResource(process).deploy();

    final long processInstanceKey =
        ENGINE_RULE
            .processInstance()
            .ofBpmnProcessId("mixedDepths")
            .withVariables(
                Map.of(
                    "result",
                    Map.of(
                        "status", "INIT",
                        "detail", Map.of("code", 0, "message", "none"),
                        "extra", "keep_me")))
            .create();

    // when
    ENGINE_RULE
        .job()
        .ofInstance(processInstanceKey)
        .withType("mixedDepths")
        .withVariables(
            Map.of(
                "result",
                Map.of(
                    "status", "DONE",
                    "detail", Map.of("code", 200, "message", "success"),
                    "extra", "should_not_change")))
        .complete();

    // then
    final Map<String, Object> result = awaitUpdatedVariable(processInstanceKey, "result");
    assertThat(result.get("status")).isEqualTo("DONE");
    assertThat(nested(result, "detail", "code")).isEqualTo(200);
    assertThat(nested(result, "detail", "message")).isEqualTo("none"); // preserved
    assertThat(result.get("extra")).isEqualTo("keep_me"); // preserved
  }

  // ---------------------------------------------------------------------------
  // 9. Multiple mappings targeting different branches of the same parent
  // ---------------------------------------------------------------------------

  @Test
  public void shouldPreserveUnmappedBranchesWhenMultipleBranchesMapped()
      throws JsonProcessingException {
    // given
    final BpmnModelInstance process =
        Bpmn.createExecutableProcess("multiBranch")
            .startEvent()
            .serviceTask("task")
            .zeebeJobType("multiBranch")
            .zeebeOutputExpression("processData.humanTask.outcome", "processData.humanTask.outcome")
            .zeebeOutputExpression(
                "processData.humanTask.assignee", "processData.humanTask.assignee")
            .zeebeOutputExpression("processData.fault.errors", "processData.fault.errors")
            .endEvent()
            .done();

    ENGINE_RULE.deployment().withXmlResource(process).deploy();

    final Map<String, Object> initialHumanTask = new HashMap<>();
    initialHumanTask.put("outcome", "PENDING");
    initialHumanTask.put("assignee", "user1");
    initialHumanTask.put("dueDate", "2026-12-31");

    final Map<String, Object> initialProcessData = new HashMap<>();
    initialProcessData.put("humanTask", initialHumanTask);
    initialProcessData.put("fault", Map.of("errors", List.of()));
    initialProcessData.put("salary", Map.of("pensionable", 1000));

    final long processInstanceKey =
        ENGINE_RULE
            .processInstance()
            .ofBpmnProcessId("multiBranch")
            .withVariables(Map.of("processData", initialProcessData))
            .create();

    // when
    final Map<String, Object> jobHumanTask = new HashMap<>();
    jobHumanTask.put("outcome", "APPROVED");
    jobHumanTask.put("assignee", "user2");
    jobHumanTask.put("dueDate", "2027-01-15");

    final Map<String, Object> jobProcessData = new HashMap<>();
    jobProcessData.put("humanTask", jobHumanTask);
    jobProcessData.put("fault", Map.of("errors", List.of("E1", "E2")));
    jobProcessData.put("salary", Map.of("pensionable", 9999));

    ENGINE_RULE
        .job()
        .ofInstance(processInstanceKey)
        .withType("multiBranch")
        .withVariables(Map.of("processData", jobProcessData))
        .complete();

    // then
    final Map<String, Object> result = awaitUpdatedVariable(processInstanceKey, "processData");

    // Mapped keys updated
    assertThat(nested(result, "humanTask", "outcome")).isEqualTo("APPROVED");
    assertThat(nested(result, "humanTask", "assignee")).isEqualTo("user2");
    assertThat(nested(result, "fault", "errors")).isEqualTo(List.of("E1", "E2"));

    // Unmapped keys preserved
    assertThat(nested(result, "humanTask", "dueDate")).isEqualTo("2026-12-31");
    assertThat(nested(result, "salary", "pensionable")).isEqualTo(1000);
  }

  // ---------------------------------------------------------------------------
  // 10. Mapping with boolean values
  // ---------------------------------------------------------------------------

  @Test
  public void shouldHandleBooleanValuesInNestedMapping() throws JsonProcessingException {
    // given
    final BpmnModelInstance process =
        Bpmn.createExecutableProcess("bool")
            .startEvent()
            .serviceTask("task")
            .zeebeJobType("bool")
            .zeebeOutputExpression("flags.active", "flags.active")
            .endEvent()
            .done();

    ENGINE_RULE.deployment().withXmlResource(process).deploy();

    final long processInstanceKey =
        ENGINE_RULE
            .processInstance()
            .ofBpmnProcessId("bool")
            .withVariables(Map.of("flags", Map.of("active", false, "verified", true)))
            .create();

    // when
    ENGINE_RULE
        .job()
        .ofInstance(processInstanceKey)
        .withType("bool")
        .withVariables(Map.of("flags", Map.of("active", true, "verified", false)))
        .complete();

    // then
    final Map<String, Object> result = awaitUpdatedVariable(processInstanceKey, "flags");
    assertThat(result.get("active")).isEqualTo(true);
    assertThat(result.get("verified")).isEqualTo(true); // preserved from parent
  }

  // ---------------------------------------------------------------------------
  // 11. Two sequential tasks — second task should not lose first task's output
  // ---------------------------------------------------------------------------

  @Test
  public void shouldPreserveFirstTaskOutputWhenSecondTaskHasDifferentMappings()
      throws JsonProcessingException {
    // given — two tasks in sequence with different output mappings on the same top-level variable
    final BpmnModelInstance process =
        Bpmn.createExecutableProcess("twoTasks")
            .startEvent()
            .serviceTask("task1")
            .zeebeJobType("twoTasks1")
            .zeebeOutputExpression("data.step1Result", "data.step1Result")
            .serviceTask("task2")
            .zeebeJobType("twoTasks2")
            .zeebeOutputExpression("data.step2Result", "data.step2Result")
            .endEvent()
            .done();

    ENGINE_RULE.deployment().withXmlResource(process).deploy();

    final long processInstanceKey =
        ENGINE_RULE
            .processInstance()
            .ofBpmnProcessId("twoTasks")
            .withVariables(
                Map.of(
                    "data",
                    Map.of("step1Result", "none", "step2Result", "none", "baseline", "keep")))
            .create();

    // when — first task completes
    ENGINE_RULE
        .job()
        .ofInstance(processInstanceKey)
        .withType("twoTasks1")
        .withVariables(Map.of("data", Map.of("step1Result", "DONE_1")))
        .complete();

    // when — second task completes
    ENGINE_RULE
        .job()
        .ofInstance(processInstanceKey)
        .withType("twoTasks2")
        .withVariables(Map.of("data", Map.of("step2Result", "DONE_2")))
        .complete();

    // then
    RecordingExporter.processInstanceRecords(ProcessInstanceIntent.ELEMENT_COMPLETED)
        .withProcessInstanceKey(processInstanceKey)
        .withElementType(BpmnElementType.PROCESS)
        .await();

    // Find the last UPDATED record for data
    final String dataJson =
        RecordingExporter.variableRecords(VariableIntent.UPDATED)
            .withProcessInstanceKey(processInstanceKey)
            .withName("data")
            .skip(1) // skip first update from task1, get second from task2
            .getFirst()
            .getValue()
            .getValue();

    final Map<String, Object> result = OBJECT_MAPPER.readValue(dataJson, MAP_TYPE);

    // Both task results should be present
    assertThat(result.get("step1Result")).isEqualTo("DONE_1");
    assertThat(result.get("step2Result")).isEqualTo("DONE_2");
    assertThat(result.get("baseline")).isEqualTo("keep");
  }

  // ---------------------------------------------------------------------------
  // 12. Mapping with nested source expressions (source path != target path)
  // ---------------------------------------------------------------------------

  @Test
  public void shouldMapFromDifferentSourcePathToNestedTargetAndPreserveSiblings()
      throws JsonProcessingException {
    // given — source and target paths differ
    final BpmnModelInstance process =
        Bpmn.createExecutableProcess("diffPaths")
            .startEvent()
            .serviceTask("task")
            .zeebeJobType("diffPaths")
            .zeebeOutputExpression("taskOutput.newStatus", "record.status")
            .endEvent()
            .done();

    ENGINE_RULE.deployment().withXmlResource(process).deploy();

    final long processInstanceKey =
        ENGINE_RULE
            .processInstance()
            .ofBpmnProcessId("diffPaths")
            .withVariables(
                Map.of(
                    "record", Map.of("status", "DRAFT", "createdBy", "admin"),
                    "taskOutput", Map.of("newStatus", "placeholder")))
            .create();

    // when
    ENGINE_RULE
        .job()
        .ofInstance(processInstanceKey)
        .withType("diffPaths")
        .withVariables(Map.of("taskOutput", Map.of("newStatus", "PUBLISHED")))
        .complete();

    // then
    final Map<String, Object> result = awaitUpdatedVariable(processInstanceKey, "record");
    assertThat(result.get("status")).isEqualTo("PUBLISHED");
    assertThat(result.get("createdBy")).isEqualTo("admin"); // preserved
  }

  // ---------------------------------------------------------------------------
  // 13. Many mappings on a wide object — test for deep merge
  // ---------------------------------------------------------------------------

  @Test
  public void shouldHandleManyMappingsOnWideObject() throws JsonProcessingException {
    // given — object with many keys, only 2 mapped
    final BpmnModelInstance process =
        Bpmn.createExecutableProcess("wideObject")
            .startEvent()
            .serviceTask("task")
            .zeebeJobType("wideObject")
            .zeebeOutputExpression("obj.key2", "obj.key2")
            .zeebeOutputExpression("obj.key5", "obj.key5")
            .endEvent()
            .done();

    ENGINE_RULE.deployment().withXmlResource(process).deploy();

    final Map<String, Object> wideObj = new HashMap<>();
    for (int i = 1; i <= 10; i++) {
      wideObj.put("key" + i, i * 100);
    }

    final long processInstanceKey =
        ENGINE_RULE
            .processInstance()
            .ofBpmnProcessId("wideObject")
            .withVariables(Map.of("obj", wideObj))
            .create();

    // when — job returns modified values for ALL keys
    final Map<String, Object> jobObj = new HashMap<>();
    for (int i = 1; i <= 10; i++) {
      jobObj.put("key" + i, i * 999);
    }

    ENGINE_RULE
        .job()
        .ofInstance(processInstanceKey)
        .withType("wideObject")
        .withVariables(Map.of("obj", jobObj))
        .complete();

    // then
    final Map<String, Object> result = awaitUpdatedVariable(processInstanceKey, "obj");

    // Mapped keys updated
    assertThat(result.get("key2")).isEqualTo(2 * 999);
    assertThat(result.get("key5")).isEqualTo(5 * 999);

    // All other keys preserved from parent
    assertThat(result.get("key1")).isEqualTo(100);
    assertThat(result.get("key3")).isEqualTo(300);
    assertThat(result.get("key4")).isEqualTo(400);
    assertThat(result.get("key6")).isEqualTo(600);
    assertThat(result.get("key7")).isEqualTo(700);
    assertThat(result.get("key8")).isEqualTo(800);
    assertThat(result.get("key9")).isEqualTo(900);
    assertThat(result.get("key10")).isEqualTo(1000);
  }

  // ---------------------------------------------------------------------------
  // 14. Mapping string value to nested target (non-numeric value)
  // ---------------------------------------------------------------------------

  @Test
  public void shouldMapStringValueIntoNestedTargetPreservingOtherKeys()
      throws JsonProcessingException {
    // given
    final BpmnModelInstance process =
        Bpmn.createExecutableProcess("stringNested")
            .startEvent()
            .serviceTask("task")
            .zeebeJobType("stringNested")
            .zeebeOutputExpression("info.description", "info.description")
            .endEvent()
            .done();

    ENGINE_RULE.deployment().withXmlResource(process).deploy();

    final long processInstanceKey =
        ENGINE_RULE
            .processInstance()
            .ofBpmnProcessId("stringNested")
            .withVariables(Map.of("info", Map.of("description", "old desc", "category", "A")))
            .create();

    // when
    ENGINE_RULE
        .job()
        .ofInstance(processInstanceKey)
        .withType("stringNested")
        .withVariables(Map.of("info", Map.of("description", "new desc", "category", "B")))
        .complete();

    // then
    final Map<String, Object> result = awaitUpdatedVariable(processInstanceKey, "info");
    assertThat(result.get("description")).isEqualTo("new desc");
    assertThat(result.get("category")).isEqualTo("A"); // preserved
  }

  // ===========================================================================
  // Edge cases
  // ===========================================================================

  // ---------------------------------------------------------------------------
  // E1. Extremely deep nesting — must not throw StackOverflowError
  // ---------------------------------------------------------------------------

  @Test
  @Ignore("Working, excluded from CI to reduce runtime")
  public void shouldHandleExtremelyDeepNestingWithoutStackOverflow()
      throws JsonProcessingException {
    // given — a 15-level deep nesting: root.l1.l2...l15.leaf
    final int depth = 15;
    final var pathSegments = new ArrayList<String>();
    pathSegments.add("root");
    for (int i = 1; i < depth; i++) {
      pathSegments.add("l" + i);
    }
    pathSegments.add("leaf");
    final String targetPath = String.join(".", pathSegments);

    final BpmnModelInstance process =
        Bpmn.createExecutableProcess("deepNest")
            .startEvent()
            .serviceTask("task")
            .zeebeJobType("deepNest")
            .zeebeOutputExpression("value", targetPath)
            .endEvent()
            .done();

    ENGINE_RULE.deployment().withXmlResource(process).deploy();

    // Build a deeply nested initial variable: root -> l1 -> ... -> l19 -> {leaf: "old", sibling:
    // "keep"}
    Map<String, Object> deepMap = new HashMap<>();
    deepMap.put("leaf", "old");
    deepMap.put("sibling", "keep");
    for (int i = depth - 1; i >= 1; i--) {
      deepMap = Map.of("l" + i, deepMap);
    }

    final long processInstanceKey =
        ENGINE_RULE
            .processInstance()
            .ofBpmnProcessId("deepNest")
            .withVariables(Map.of("root", deepMap, "value", "placeholder"))
            .create();

    // when
    ENGINE_RULE
        .job()
        .ofInstance(processInstanceKey)
        .withType("deepNest")
        .withVariables(Map.of("value", "new"))
        .complete();

    // then — process completes without StackOverflowError
    awaitProcessCompletion(processInstanceKey);

    final String rootJson =
        RecordingExporter.variableRecords(VariableIntent.UPDATED)
            .withProcessInstanceKey(processInstanceKey)
            .withName("root")
            .getFirst()
            .getValue()
            .getValue();

    // Navigate to the deepest level and verify
    Object current = OBJECT_MAPPER.readValue(rootJson, MAP_TYPE);
    for (int i = 1; i < depth; i++) {
      current = ((Map<?, ?>) current).get("l" + i);
      assertThat(current).as("Level l%d should not be null", i).isNotNull();
    }
    @SuppressWarnings("unchecked")
    final Map<String, Object> deepest = (Map<String, Object>) current;
    assertThat(deepest.get("leaf")).isEqualTo("new");
    assertThat(deepest.get("sibling")).isEqualTo("keep");
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  /** Waits for the process instance to complete. */
  private void awaitProcessCompletion(final long processInstanceKey) {
    RecordingExporter.processInstanceRecords(ProcessInstanceIntent.ELEMENT_COMPLETED)
        .withProcessInstanceKey(processInstanceKey)
        .withElementType(BpmnElementType.PROCESS)
        .limit(1)
        .await();
  }

  /**
   * Waits for the process to complete and retrieves the UPDATED variable record for the given
   * variable name. Returns the deserialized JSON map.
   */
  private Map<String, Object> awaitUpdatedVariable(
      final long processInstanceKey, final String variableName) throws JsonProcessingException {

    RecordingExporter.processInstanceRecords(ProcessInstanceIntent.ELEMENT_COMPLETED)
        .withProcessInstanceKey(processInstanceKey)
        .withElementType(BpmnElementType.PROCESS)
        .await();

    final String json =
        RecordingExporter.variableRecords(VariableIntent.UPDATED)
            .withProcessInstanceKey(processInstanceKey)
            .withName(variableName)
            .limit(1)
            .getFirst()
            .getValue()
            .getValue();

    return OBJECT_MAPPER.readValue(json, MAP_TYPE);
  }

  @SuppressWarnings("unchecked")
  private static Object nested(final Map<String, Object> map, final String... path) {
    Object current = map;
    for (final String segment : path) {
      current = ((Map<String, Object>) current).get(segment);
    }
    return current;
  }
}
