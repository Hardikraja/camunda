/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Camunda License 1.0. You may not use this file
 * except in compliance with the Camunda License 1.0.
 */
package io.camunda.zeebe.engine.processing.variable.mapping;

import static io.camunda.zeebe.test.util.MsgPackUtil.asMsgPack;
import static io.camunda.zeebe.test.util.MsgPackUtil.assertEquality;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.core.exc.StreamConstraintsException;
import io.camunda.zeebe.msgpack.MsgPackUtil;
import org.agrona.DirectBuffer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link MsgPackUtil#mergeMsgPackDocuments(DirectBuffer, DirectBuffer)}.
 *
 * <p>These tests verify the deep-merge semantics at the MsgPack binary level, independently of FEEL
 * expression evaluation or engine integration. They guard against regressions in:
 *
 * <ul>
 *   <li>Content-based key comparison (ByteBuffer vs UnsafeBuffer equality)
 *   <li>Recursive merging of nested map values
 *   <li>Override-wins semantics for leaf values
 *   <li>Preservation of base-only keys at every nesting level
 * </ul>
 */
class MsgPackDocumentMergeTest {

  @Nested
  @DisplayName("Flat map merging")
  class FlatMapMerging {

    @Test
    void shouldReturnBaseWhenOverrideIsEmpty() {
      // given
      final DirectBuffer base = asMsgPack("{'a':1,'b':2}");
      final DirectBuffer overrides = asMsgPack("{}");

      // when
      final DirectBuffer merged = MsgPackUtil.mergeMsgPackDocuments(base, overrides);

      // then
      assertEquality(merged, "{'a':1,'b':2}");
    }

    @Test
    void shouldReturnOverrideWhenBaseIsEmpty() {
      // given
      final DirectBuffer base = asMsgPack("{}");
      final DirectBuffer overrides = asMsgPack("{'x':10}");

      // when
      final DirectBuffer merged = MsgPackUtil.mergeMsgPackDocuments(base, overrides);

      // then
      assertEquality(merged, "{'x':10}");
    }

    @Test
    void shouldMergeDisjointKeys() {
      // given
      final DirectBuffer base = asMsgPack("{'a':1}");
      final DirectBuffer overrides = asMsgPack("{'b':2}");

      // when
      final DirectBuffer merged = MsgPackUtil.mergeMsgPackDocuments(base, overrides);

      // then
      assertEquality(merged, "{'a':1,'b':2}");
    }

    @Test
    void shouldOverrideMatchingLeafKeys() {
      // given
      final DirectBuffer base = asMsgPack("{'a':1,'b':2}");
      final DirectBuffer overrides = asMsgPack("{'a':99}");

      // when
      final DirectBuffer merged = MsgPackUtil.mergeMsgPackDocuments(base, overrides);

      // then
      assertEquality(merged, "{'a':99,'b':2}");
    }

    @Test
    void shouldOverrideAllMatchingKeys() {
      // given
      final DirectBuffer base = asMsgPack("{'a':1,'b':2,'c':3}");
      final DirectBuffer overrides = asMsgPack("{'a':10,'b':20,'c':30}");

      // when
      final DirectBuffer merged = MsgPackUtil.mergeMsgPackDocuments(base, overrides);

      // then
      assertEquality(merged, "{'a':10,'b':20,'c':30}");
    }

    @Test
    void shouldHandleStringValues() {
      // given
      final DirectBuffer base = asMsgPack("{'name':'Alice','role':'admin'}");
      final DirectBuffer overrides = asMsgPack("{'name':'Bob'}");

      // when
      final DirectBuffer merged = MsgPackUtil.mergeMsgPackDocuments(base, overrides);

      // then
      assertEquality(merged, "{'name':'Bob','role':'admin'}");
    }
  }

  @Nested
  @DisplayName("One-level nested map merging")
  class OneLevelNesting {

    @Test
    void shouldDeepMergeNestedMapsPreservingBaseOnlyKeys() {
      // given — both have 'data' as a map, with different nested keys
      final DirectBuffer base = asMsgPack("{'data':{'a':1,'b':2}}");
      final DirectBuffer overrides = asMsgPack("{'data':{'c':3}}");

      // when
      final DirectBuffer merged = MsgPackUtil.mergeMsgPackDocuments(base, overrides);

      // then — a and b from base, c from override
      assertEquality(merged, "{'data':{'a':1,'b':2,'c':3}}");
    }

    @Test
    void shouldDeepMergeOverridingMatchingNestedKeys() {
      // given
      final DirectBuffer base = asMsgPack("{'data':{'a':1,'b':2}}");
      final DirectBuffer overrides = asMsgPack("{'data':{'b':99}}");

      // when
      final DirectBuffer merged = MsgPackUtil.mergeMsgPackDocuments(base, overrides);

      // then
      assertEquality(merged, "{'data':{'a':1,'b':99}}");
    }

    @Test
    void shouldPreserveSiblingMapWhenOnlyOneBranchIsOverridden() {
      // given — base has salary and humanTask, override only has humanTask
      final DirectBuffer base =
          asMsgPack("{'processData':{'salary':{'beneficial':2},'humanTask':{'outcome':'OLD'}}}");
      final DirectBuffer overrides = asMsgPack("{'processData':{'humanTask':{'outcome':'NEW'}}}");

      // when
      final DirectBuffer merged = MsgPackUtil.mergeMsgPackDocuments(base, overrides);

      // then — salary preserved, humanTask overridden
      assertEquality(
          merged, "{'processData':{'salary':{'beneficial':2},'humanTask':{'outcome':'NEW'}}}");
    }
  }

  @Nested
  @DisplayName("Multi-level deep nesting")
  class MultiLevelNesting {

    @Test
    void shouldDeepMergeAtThreeLevels() {
      // given
      final DirectBuffer base = asMsgPack("{'a':{'b':{'c':1,'d':2},'e':3}}");
      final DirectBuffer overrides = asMsgPack("{'a':{'b':{'c':99}}}");

      // when
      final DirectBuffer merged = MsgPackUtil.mergeMsgPackDocuments(base, overrides);

      // then — d and e preserved, c overridden
      assertEquality(merged, "{'a':{'b':{'c':99,'d':2},'e':3}}");
    }

    @Test
    void shouldDeepMergeAtFourLevels() {
      // given
      final DirectBuffer base =
          asMsgPack("{'l1':{'l2':{'l3':{'l4_a':'keep','l4_b':'keep'},'l3_sib':'keep'}}}");
      final DirectBuffer overrides = asMsgPack("{'l1':{'l2':{'l3':{'l4_a':'changed'}}}}");

      // when
      final DirectBuffer merged = MsgPackUtil.mergeMsgPackDocuments(base, overrides);

      // then
      assertEquality(
          merged, "{'l1':{'l2':{'l3':{'l4_a':'changed','l4_b':'keep'},'l3_sib':'keep'}}}");
    }
  }

  @Nested
  @DisplayName("Mixed value types")
  class MixedValueTypes {

    @Test
    void shouldOverrideMapWithScalar() {
      // given — base has a map at 'x', override replaces with a scalar
      final DirectBuffer base = asMsgPack("{'x':{'nested':1}}");
      final DirectBuffer overrides = asMsgPack("{'x':42}");

      // when
      final DirectBuffer merged = MsgPackUtil.mergeMsgPackDocuments(base, overrides);

      // then — override wins, scalar replaces map
      assertEquality(merged, "{'x':42}");
    }

    @Test
    void shouldOverrideScalarWithMap() {
      // given — base has a scalar at 'x', override replaces with a map
      final DirectBuffer base = asMsgPack("{'x':42}");
      final DirectBuffer overrides = asMsgPack("{'x':{'nested':1}}");

      // when
      final DirectBuffer merged = MsgPackUtil.mergeMsgPackDocuments(base, overrides);

      // then — override wins, map replaces scalar
      assertEquality(merged, "{'x':{'nested':1}}");
    }

    @Test
    void shouldHandleArrayValues() {
      // given — arrays are leaf values, not recursively merged
      final DirectBuffer base = asMsgPack("{'arr':[1,2],'other':'keep'}");
      final DirectBuffer overrides = asMsgPack("{'arr':[3,4,5]}");

      // when
      final DirectBuffer merged = MsgPackUtil.mergeMsgPackDocuments(base, overrides);

      // then — array replaced entirely, other preserved
      assertEquality(merged, "{'arr':[3,4,5],'other':'keep'}");
    }

    @Test
    void shouldHandleBooleanValues() {
      // given
      final DirectBuffer base = asMsgPack("{'active':false,'name':'test'}");
      final DirectBuffer overrides = asMsgPack("{'active':true}");

      // when
      final DirectBuffer merged = MsgPackUtil.mergeMsgPackDocuments(base, overrides);

      // then
      assertEquality(merged, "{'active':true,'name':'test'}");
    }

    @Test
    void shouldHandleNullValues() {
      // given
      final DirectBuffer base = asMsgPack("{'a':1,'b':2}");
      final DirectBuffer overrides = asMsgPack("{'a':null}");

      // when
      final DirectBuffer merged = MsgPackUtil.mergeMsgPackDocuments(base, overrides);

      // then — null override replaces the base value
      assertEquality(merged, "{'a':null,'b':2}");
    }
  }

  @Nested
  @DisplayName("Edge cases")
  class EdgeCases {

    @Test
    void shouldMergeTwoEmptyDocuments() {
      // given
      final DirectBuffer base = asMsgPack("{}");
      final DirectBuffer overrides = asMsgPack("{}");

      // when
      final DirectBuffer merged = MsgPackUtil.mergeMsgPackDocuments(base, overrides);

      // then
      assertEquality(merged, "{}");
    }

    @Test
    void shouldMergeIdenticalDocuments() {
      // given
      final DirectBuffer base = asMsgPack("{'a':1,'b':{'c':2}}");
      final DirectBuffer overrides = asMsgPack("{'a':1,'b':{'c':2}}");

      // when
      final DirectBuffer merged = MsgPackUtil.mergeMsgPackDocuments(base, overrides);

      // then
      assertEquality(merged, "{'a':1,'b':{'c':2}}");
    }

    @Test
    void shouldHandleLargeNumberOfKeys() {
      // given — 20 keys, override changes only one
      final StringBuilder baseJson = new StringBuilder("{");
      for (int i = 0; i < 20; i++) {
        if (i > 0) {
          baseJson.append(",");
        }
        baseJson.append("\"k").append(i).append("\":").append(i);
      }
      baseJson.append("}");

      final DirectBuffer base = asMsgPack(baseJson.toString());
      final DirectBuffer overrides = asMsgPack("{\"k10\":999}");

      // when
      final DirectBuffer merged = MsgPackUtil.mergeMsgPackDocuments(base, overrides);

      // then — verify a few keys
      final var expectedJson = new StringBuilder("{");
      for (int i = 0; i < 20; i++) {
        if (i > 0) {
          expectedJson.append(",");
        }
        expectedJson.append("\"k").append(i).append("\":").append(i == 10 ? 999 : i);
      }
      expectedJson.append("}");
      assertEquality(merged, expectedJson.toString());
    }

    @Test
    void shouldHandleNestedEmptyMaps() {
      // given
      final DirectBuffer base = asMsgPack("{'a':{},'b':{'c':1}}");
      final DirectBuffer overrides = asMsgPack("{'a':{'x':1},'b':{}}");

      // when
      final DirectBuffer merged = MsgPackUtil.mergeMsgPackDocuments(base, overrides);

      // then
      assertEquality(merged, "{'a':{'x':1},'b':{'c':1}}");
    }

    @Test
    void shouldHandleOverrideAddingNewNestedBranch() {
      // given — base has no 'newBranch', override adds it
      final DirectBuffer base = asMsgPack("{'existing':{'a':1}}");
      final DirectBuffer overrides = asMsgPack("{'newBranch':{'x':10}}");

      // when
      final DirectBuffer merged = MsgPackUtil.mergeMsgPackDocuments(base, overrides);

      // then
      assertEquality(merged, "{'existing':{'a':1},'newBranch':{'x':10}}");
    }

    @Test
    @DisplayName("Deep recursion (1000 levels) with siblings preserved at every level")
    void shouldDeepMerge1000LevelsWithSiblingsPreserved() {
      // given — build 1000-level deep nesting where each level has a sibling key:
      //   {"l0":{"sibling0":0,"l1":{"sibling1":1, ... "leaf":"old"}}...}
      // override only touches the deepest leaf
      final int depth = 999;

      // Build base JSON — one outer object; each "lN" opens a nested map with "siblingN"
      // e.g. depth=2 → {"l0":{"sibling0":0,"l1":{"sibling1":1,"leaf":"old"}}}
      final var baseJson = new StringBuilder("{");
      for (int i = 0; i < depth; i++) {
        if (i > 0) {
          baseJson.append(",");
        }
        baseJson.append("\"l").append(i).append("\":{\"sibling").append(i).append("\":").append(i);
      }
      baseJson.append(",\"leaf\":\"old\"");
      baseJson.append("}".repeat(depth + 1)); // close each nested map + outer

      // Build override JSON — same nesting path, only overrides leaf
      final var overrideJson = new StringBuilder();
      for (int i = 0; i < depth; i++) {
        overrideJson.append("{\"l").append(i).append("\":");
      }
      overrideJson.append("{\"leaf\":\"new\"}");
      overrideJson.append("}".repeat(depth));

      final DirectBuffer base = asMsgPack(baseJson.toString());
      final DirectBuffer overrides = asMsgPack(overrideJson.toString());

      // when — must not throw an Exception
      final DirectBuffer merged = MsgPackUtil.mergeMsgPackDocuments(base, overrides);

      // then — build expected: siblings preserved at every level, leaf overridden
      final var expectedJson = new StringBuilder("{");
      for (int i = 0; i < depth; i++) {
        if (i > 0) {
          expectedJson.append(",");
        }
        expectedJson
            .append("\"l")
            .append(i)
            .append("\":{\"sibling")
            .append(i)
            .append("\":")
            .append(i);
      }
      expectedJson.append(",\"leaf\":\"new\"");
      expectedJson.append("}".repeat(depth + 1));
      assertEquality(merged, expectedJson.toString());
    }

    @Test
    @DisplayName("Deep recursion (1001 levels) throws StreamConstraintsException")
    void shouldDeepMerge1001LevelsThrowsStreamConstraintsException() {
      // given — build 1001-level deep nesting (depth=1000 "lN" maps + 1 outer map).
      // Jackson's default maxNestingDepth is 1000, so parsing this JSON must fail.
      final int depth = 1000;

      final var baseJson = new StringBuilder("{");
      for (int i = 0; i < depth; i++) {
        if (i > 0) {
          baseJson.append(",");
        }
        baseJson.append("\"l").append(i).append("\":{\"sibling").append(i).append("\":").append(i);
      }
      baseJson.append(",\"leaf\":\"old\"");
      baseJson.append("}".repeat(depth + 1));

      final String json = baseJson.toString();

      // when / then — asMsgPack parses through Jackson's JSON_MAPPER (default limit = 1000).
      // 1001 nesting levels exceeds that limit → RuntimeException wrapping
      // StreamConstraintsException
      assertThatThrownBy(() -> asMsgPack(json))
          .isInstanceOf(RuntimeException.class)
          .hasCauseInstanceOf(StreamConstraintsException.class);
    }
  }
}
