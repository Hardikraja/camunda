/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Camunda License 1.0. You may not use this file
 * except in compliance with the Camunda License 1.0.
 */

import {Page, Locator, expect} from '@playwright/test';
import {relativizePath, Paths} from 'utils/relativizePath';
import {defaultAssertionOptions} from '../utils/constants';

export class IdentityGlobalTaskListenersPage {
  private page: Page;
  readonly globalTaskListenersList: Locator;
  readonly createGlobalTaskListenerButton: Locator;
  readonly editGlobalTaskListenerButton: (rowName?: string) => Locator;
  readonly deleteGlobalTaskListenerButton: (rowName?: string) => Locator;

  readonly createGlobalTaskListenerModal: Locator;
  readonly closeCreateGlobalTaskListenerModal: Locator;
  readonly createGlobalTaskListenerIdField: Locator;
  readonly createGlobalTaskListenerTypeField: Locator;
  readonly createGlobalTaskListenerModalCancelButton: Locator;
  readonly createGlobalTaskListenerModalCreateButton: Locator;

  readonly editGlobalTaskListenerModal: Locator;
  readonly closeEditGlobalTaskListenerModal: Locator;
  readonly editGlobalTaskListenerTypeField: Locator;
  readonly editGlobalTaskListenerModalCancelButton: Locator;
  readonly editGlobalTaskListenerModalUpdateButton: Locator;

  readonly deleteGlobalTaskListenerModal: Locator;
  readonly closeDeleteGlobalTaskListenerModal: Locator;
  readonly deleteGlobalTaskListenerModalCancelButton: Locator;
  readonly deleteGlobalTaskListenerModalDeleteButton: Locator;

  readonly emptyStateLocator: Locator;
  readonly globalTaskListenerCell: (name: string) => Locator;

  constructor(page: Page) {
    this.page = page;
    this.globalTaskListenersList = page.getByRole('table');

    this.globalTaskListenerCell = (name) =>
      this.globalTaskListenersList.getByRole('cell', {name, exact: true});

    // List page button: t('createListener') = 'Create listener'
    this.createGlobalTaskListenerButton = page.getByRole('button', {
      name: 'Create listener',
    });

    // Edit: icon-only button, iconDescription = t('editGlobalTaskListener') = 'Update user task listener'
    this.editGlobalTaskListenerButton = (rowName) =>
      this.globalTaskListenersList
        .getByRole('row', {name: rowName})
        .getByLabel('Update user task listener');

    // Delete: danger button with text t('delete') = 'Delete'
    this.deleteGlobalTaskListenerButton = (rowName) =>
      this.globalTaskListenersList
        .getByRole('row', {name: rowName})
        .getByLabel('Delete');

    // Create modal headline: t('createGlobalTaskListener') = 'Create user task listener'
    this.createGlobalTaskListenerModal = page.getByRole('dialog', {
      name: 'Create user task listener',
    });
    this.closeCreateGlobalTaskListenerModal =
      this.createGlobalTaskListenerModal.getByRole('button', {name: 'Close'});
    // ID field: t('globalTaskListenerId') = 'Task listener ID'
    this.createGlobalTaskListenerIdField =
      this.createGlobalTaskListenerModal.getByRole('textbox', {
        name: 'Task listener ID',
      });
    // Type field: t('listenerType') = 'Listener type'
    this.createGlobalTaskListenerTypeField =
      this.createGlobalTaskListenerModal.getByRole('textbox', {
        name: 'Listener type',
      });
    this.createGlobalTaskListenerModalCancelButton =
      this.createGlobalTaskListenerModal.getByRole('button', {name: 'Cancel'});
    // Confirm: t('create') = 'Create'
    this.createGlobalTaskListenerModalCreateButton =
      this.createGlobalTaskListenerModal.getByRole('button', {name: 'Create'});

    // Edit modal headline: t('editGlobalTaskListener') = 'Update user task listener'
    this.editGlobalTaskListenerModal = page.getByRole('dialog', {
      name: 'Update user task listener',
    });
    this.closeEditGlobalTaskListenerModal =
      this.editGlobalTaskListenerModal.getByRole('button', {name: 'Close'});
    this.editGlobalTaskListenerTypeField =
      this.editGlobalTaskListenerModal.getByRole('textbox', {
        name: 'Listener type',
      });
    this.editGlobalTaskListenerModalCancelButton =
      this.editGlobalTaskListenerModal.getByRole('button', {name: 'Cancel'});
    // Confirm: t('update') = 'Update'
    this.editGlobalTaskListenerModalUpdateButton =
      this.editGlobalTaskListenerModal.getByRole('button', {name: 'Update'});

    // Delete modal headline: t('deleteGlobalTaskListener') = 'Delete user task listener'
    this.deleteGlobalTaskListenerModal = page.getByRole('dialog', {
      name: 'Delete user task listener',
    });
    this.closeDeleteGlobalTaskListenerModal =
      this.deleteGlobalTaskListenerModal.getByRole('button', {name: 'Close'});
    this.deleteGlobalTaskListenerModalCancelButton =
      this.deleteGlobalTaskListenerModal.getByRole('button', {name: 'Cancel'});
    // Confirm: t('delete') = 'Delete'
    this.deleteGlobalTaskListenerModalDeleteButton =
      this.deleteGlobalTaskListenerModal.getByRole('button', {name: 'Delete'});

    // t('noGlobalTaskListeners') = 'No user task listeners created yet'
    this.emptyStateLocator = page.getByText(
      'No user task listeners created yet',
    );
  }

  async navigateToGlobalTaskListeners() {
    await this.page.goto(relativizePath(Paths.globalTaskListeners()));
  }

  async createGlobalTaskListener(
    listenerId: string,
    listenerType: string,
    eventTypeLabel: string,
  ) {
    await this.createGlobalTaskListenerButton.click();
    await expect(this.createGlobalTaskListenerModal).toBeVisible();
    await this.createGlobalTaskListenerIdField.fill(listenerId);
    await this.createGlobalTaskListenerTypeField.fill(listenerType);
    // Carbon MultiSelect: click the combobox (titleText='Event type') then click the option
    await this.createGlobalTaskListenerModal
      .getByRole('combobox', {name: 'Event type'})
      .click();
    await this.page.getByRole('option', {name: eventTypeLabel}).click();
    await this.createGlobalTaskListenerModalCreateButton.click();
    await expect(this.createGlobalTaskListenerModal).toBeHidden();
  }

  async editGlobalTaskListener(
    currentListenerId: string,
    newType: string,
    newEventTypeLabel?: string,
  ) {
    await this.editGlobalTaskListenerButton(currentListenerId).click();
    await expect(this.editGlobalTaskListenerModal).toBeVisible();
    await this.editGlobalTaskListenerTypeField.clear();
    await this.editGlobalTaskListenerTypeField.fill(newType);
    if (newEventTypeLabel) {
      await this.editGlobalTaskListenerModal
        .getByRole('combobox', {name: 'Event type'})
        .click();
      await this.page.getByRole('option', {name: newEventTypeLabel}).click();
    }
    await this.editGlobalTaskListenerModalUpdateButton.click();
    await expect(this.editGlobalTaskListenerModal).toBeHidden();
  }

  async deleteGlobalTaskListener(listenerId: string) {
    await expect(this.deleteGlobalTaskListenerButton(listenerId)).toBeVisible({
      timeout: 20000,
    });
    await expect(async () => {
      await this.deleteGlobalTaskListenerButton(listenerId).click();
    }).toPass(defaultAssertionOptions);
    await expect(this.deleteGlobalTaskListenerModal).toBeVisible();
    await this.deleteGlobalTaskListenerModalDeleteButton.click();
    await expect(this.deleteGlobalTaskListenerModal).toBeHidden();
  }
}
