/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Camunda License 1.0. You may not use this file
 * except in compliance with the Camunda License 1.0.
 */
package io.camunda.tasklist.store.elasticsearch;

import static org.assertj.core.api.Assertions.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.InstanceOfAssertFactories.STRING;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.elasticsearch.core.search.HitsMetadata;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.camunda.tasklist.CommonUtils;
import io.camunda.tasklist.queries.TaskQuery;
import io.camunda.tasklist.util.ElasticsearchTenantHelper;
import io.camunda.tasklist.views.TaskSearchView;
import io.camunda.webapps.schema.descriptors.template.TaskTemplate;
import io.camunda.webapps.schema.entities.usertask.TaskEntity;
import io.camunda.webapps.schema.entities.usertask.TaskEntity.TaskImplementation;
import io.camunda.webapps.schema.entities.usertask.TaskState;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TaskStoreElasticSearchTest {

  @Captor private ArgumentCaptor<SearchRequest> searchRequestCaptor;

  @Mock private ElasticsearchClient esClient;

  @Mock private ElasticsearchTenantHelper tenantHelper;

  @Spy private TaskTemplate taskTemplate = new TaskTemplate("test", true);

  @Spy private ObjectMapper objectMapper = CommonUtils.OBJECT_MAPPER;

  @InjectMocks private TaskStoreElasticSearch instance;

  @ParameterizedTest
  @CsvSource({
    "CREATED,test-tasklist-task-,_",
    "COMPLETED,test-tasklist-task-,_alias",
    "CANCELED,test-tasklist-task-,_alias"
  })
  void getTasksForDifferentStates(
      final TaskState taskState, final String expectedIndexPrefix, final String expectedIndexSuffix)
      throws Exception {
    // Given
    final TaskQuery taskQuery = new TaskQuery().setPageSize(50).setState(taskState);

    // Mock tenant helper to return query as-is
    when(tenantHelper.makeQueryTenantAware(any(Query.class))).thenAnswer(i -> i.getArgument(0));

    final SearchResponse<TaskEntity> mockedResponse = mockSearchResponse(taskState);
    when(esClient.search(searchRequestCaptor.capture(), eq(TaskEntity.class)))
        .thenReturn(mockedResponse);

    // When
    final List<TaskSearchView> result = instance.getTasks(taskQuery);

    // Then
    assertThat(searchRequestCaptor.getValue().index())
        .singleElement(as(STRING))
        .satisfies(
            index -> {
              assertThat(index).startsWith(expectedIndexPrefix);
              assertThat(index).endsWith(expectedIndexSuffix);
            });
    assertThat(result).hasSize(1);
    assertThat(result.get(0).getImplementation()).isEqualTo(TaskImplementation.JOB_WORKER);
    verify(esClient).search(any(SearchRequest.class), eq(TaskEntity.class));
  }

  @Test
  void queryTasksWithProvidedTenantIds() throws IOException {
    final TaskQuery taskQuery =
        new TaskQuery()
            .setTenantIds(new String[] {"tenant_a", "tenant_b"})
            .setPageSize(50)
            .setState(TaskState.CREATED);

    // Mock tenant helper with specific tenant ids
    when(tenantHelper.makeQueryTenantAware(any(Query.class), eq(Set.of("tenant_a", "tenant_b"))))
        .thenAnswer(i -> i.getArgument(0));

    final SearchResponse<TaskEntity> mockedResponse = mockSearchResponse(TaskState.CREATED);
    when(esClient.search(any(SearchRequest.class), eq(TaskEntity.class)))
        .thenReturn(mockedResponse);

    // When
    final List<TaskSearchView> result = instance.getTasks(taskQuery);

    // Then
    verify(tenantHelper).makeQueryTenantAware(any(Query.class), eq(Set.of("tenant_a", "tenant_b")));
    assertThat(result).hasSize(1);
  }

  @Test
  void shouldReturnPreviousPageInClientOrderWhenSearchingBefore() throws Exception {
    // Given
    final TaskQuery taskQuery =
        new TaskQuery()
            .setPageSize(2)
            .setState(TaskState.CREATED)
            .setSearchBefore(new String[] {"2000", "3"});
    final SearchResponse<TaskEntity> pagedResponse =
        mockSearchResponse(
            createTaskEntity(TaskState.CREATED, 1L),
            createTaskEntity(TaskState.CREATED, 2L),
            createTaskEntity(TaskState.CREATED, 3L));

    when(tenantHelper.makeQueryTenantAware(any(Query.class))).thenAnswer(i -> i.getArgument(0));
    when(esClient.search(any(SearchRequest.class), eq(TaskEntity.class))).thenReturn(pagedResponse);

    // When
    final List<TaskSearchView> result = instance.getTasks(taskQuery);

    // Then
    assertThat(result).extracting(TaskSearchView::getId).containsExactly("2", "1");
  }

  @Test
  void shouldKeepBoundaryTaskAtBeginningWhenSearchingAfterOrEqual() throws Exception {
    // Given
    final TaskQuery taskQuery =
        new TaskQuery()
            .setPageSize(2)
            .setState(TaskState.CREATED)
            .setSearchAfterOrEqual(new String[] {"1000", "10"});
    final SearchResponse<TaskEntity> pagedResponse =
        mockSearchResponse(
            createTaskEntity(TaskState.CREATED, 1L), createTaskEntity(TaskState.CREATED, 2L));
    final SearchResponse<TaskEntity> boundaryResponse =
        mockSearchResponse(createTaskEntity(TaskState.CREATED, 10L));
    final SearchResponse<TaskEntity> firstCheckResponse =
        mockSearchResponse(createTaskEntity(TaskState.CREATED, 10L));

    when(tenantHelper.makeQueryTenantAware(any(Query.class))).thenAnswer(i -> i.getArgument(0));
    when(esClient.search(any(SearchRequest.class), eq(TaskEntity.class)))
        .thenReturn(pagedResponse, boundaryResponse, firstCheckResponse);

    // When
    final List<TaskSearchView> result = instance.getTasks(taskQuery);

    // Then
    assertThat(result).extracting(TaskSearchView::getId).containsExactly("10", "1");
    assertThat(result.get(0).isFirst()).isTrue();
  }

  @Test
  void shouldKeepBoundaryTaskAtEndWhenSearchingBeforeOrEqual() throws Exception {
    // Given
    final TaskQuery taskQuery =
        new TaskQuery()
            .setPageSize(2)
            .setState(TaskState.CREATED)
            .setSearchBeforeOrEqual(new String[] {"1000", "10"});
    final SearchResponse<TaskEntity> pagedResponse =
        mockSearchResponse(
            createTaskEntity(TaskState.CREATED, 1L),
            createTaskEntity(TaskState.CREATED, 2L),
            createTaskEntity(TaskState.CREATED, 3L));
    final SearchResponse<TaskEntity> boundaryResponse =
        mockSearchResponse(createTaskEntity(TaskState.CREATED, 10L));

    when(tenantHelper.makeQueryTenantAware(any(Query.class))).thenAnswer(i -> i.getArgument(0));
    when(esClient.search(any(SearchRequest.class), eq(TaskEntity.class)))
        .thenReturn(pagedResponse, boundaryResponse);

    // When
    final List<TaskSearchView> result = instance.getTasks(taskQuery);

    // Then
    assertThat(result).extracting(TaskSearchView::getId).containsExactly("1", "10");
  }

  @SuppressWarnings("unchecked")
  private SearchResponse<TaskEntity> mockSearchResponse(final TaskState taskState) {
    final SearchResponse<TaskEntity> mockedResponse = mock(SearchResponse.class);
    final HitsMetadata<TaskEntity> mockedHits = mock(HitsMetadata.class);
    final Hit<TaskEntity> mockedHit = mock(Hit.class);

    when(mockedResponse.hits()).thenReturn(mockedHits);
    when(mockedHits.hits()).thenReturn(List.of(mockedHit));
    when(mockedHit.source()).thenReturn(createTaskEntity(taskState));
    when(mockedHit.sort()).thenReturn(List.of());

    return mockedResponse;
  }

  @SuppressWarnings("unchecked")
  private SearchResponse<TaskEntity> mockSearchResponse(final TaskEntity... taskEntities) {
    final SearchResponse<TaskEntity> mockedResponse = mock(SearchResponse.class);
    final HitsMetadata<TaskEntity> mockedHits = mock(HitsMetadata.class);
    final List<Hit<TaskEntity>> hits = List.of(taskEntities).stream().map(this::mockHit).toList();

    when(mockedResponse.hits()).thenReturn(mockedHits);
    when(mockedHits.hits()).thenReturn(hits);

    return mockedResponse;
  }

  @SuppressWarnings("unchecked")
  private Hit<TaskEntity> mockHit(final TaskEntity taskEntity) {
    final Hit<TaskEntity> mockedHit = mock(Hit.class);

    when(mockedHit.source()).thenReturn(taskEntity);
    when(mockedHit.sort()).thenReturn(List.of(FieldValue.of(String.valueOf(taskEntity.getKey()))));

    return mockedHit;
  }

  private TaskEntity createTaskEntity(final TaskState taskState) {
    return createTaskEntity(taskState, 123456789L);
  }

  private TaskEntity createTaskEntity(final TaskState taskState, final long key) {
    final TaskEntity entity = new TaskEntity();
    entity.setId(String.valueOf(key));
    entity.setKey(key);
    entity.setPartitionId(2);
    entity.setBpmnProcessId("bigFormProcess");
    entity.setProcessDefinitionId("00000000000");
    entity.setFlowNodeBpmnId("Activity_0aaaaa");
    entity.setFlowNodeInstanceId("11111111111");
    entity.setProcessInstanceId("2222222222");
    entity.setState(taskState);
    entity.setFormKey("camunda-forms:bpmn:userTaskForm_1111111");
    entity.setImplementation(TaskImplementation.JOB_WORKER);
    return entity;
  }
}
