/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Camunda License 1.0. You may not use this file
 * except in compliance with the Camunda License 1.0.
 */

import {APIResponse, expect, test} from '@playwright/test';
import {cancelProcessInstance, createSingleInstance, deploy} from '../../../../utils/zeebeClient';
import {
  assertBadRequest,
  assertStatusCode,
  assertUnauthorizedRequest,
  buildUrl,
  jsonHeaders,
} from '../../../../utils/http';
import {defaultAssertionOptions} from '../../../../utils/constants';
import {validateResponse} from '../../../../json-body-assertions';
import {createSingleIncidentProcessInstance, verifyIncidentsForProcessInstance} from '@requestHelpers';

test.describe('Get Process Instance Statistics By Error API Tests', () => {
    const processInstanceKeys: string[] = [];
    const errorMessage = 'Expected result of the expression \'goUp < 0\' to be \'BOOLEAN\', but was \'NULL\'. The evaluation reported the following warnings:\n[NO_VARIABLE_FOUND] No variable found with name \'goUp\'\n[NOT_COMPARABLE] Can\'t compare \'null\' with \'0\'';
    test.beforeAll(async ({request}) => {
        await deploy([
            './resources/processWithAnError.bpmn',
        ]);
    });

    test.afterAll(async () => {
    for (const processInstanceKey of processInstanceKeys) {
      try {
        await cancelProcessInstance(processInstanceKey);
      } catch (error) {
        console.warn(
          `Failed to cancel process instance with key ${processInstanceKey}: ${error}`,
        );
      }
    }
  });

  test('should return statistics for process instances with errors', async ({request}) => {
    let processInstanceKeyToSearch: string;
    await test.step('Start a process instance that will throw an error', async () => {
        const instance = await createSingleInstance('singleIncidentProcess', 1);
        processInstanceKeyToSearch =
            instance.processInstanceKey as string;
        console.log(`Started process instance with key: ${processInstanceKeyToSearch}`);
        processInstanceKeys.push(processInstanceKeyToSearch);
    });

    await test.step('Verify that the process instance has incidents', async () => {
        await verifyIncidentsForProcessInstance(
            request,
            processInstanceKeyToSearch,
            1,
        );
    });

    await test.step('Get process instance statistics by error', async () => {
        const res = await request.post(buildUrl(`/incidents/statistics/process-instances-by-error`), {
            headers: jsonHeaders(),
        });
        assertStatusCode(res, 200);
        const responseBody = await res.json();
        await validateResponse({
            path: '/incidents/statistics/process-instances-by-error',
            method: 'POST',
            status: '200',
        }, res);
        expect(responseBody.page.totalItems).toBeGreaterThanOrEqual(1);
        const responseItems = responseBody.items;
        const matchingItem = responseItems.find(
            (item: { errorMessage: string }) => item.errorMessage === errorMessage,
        );
        expect(matchingItem).toBeDefined();
        expect(matchingItem.activeInstancesWithErrorCount).toBeGreaterThanOrEqual(1);
    });
  });
});