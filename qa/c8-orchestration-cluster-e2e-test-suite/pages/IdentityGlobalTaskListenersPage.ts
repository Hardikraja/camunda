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

    // Two possible create buttons depending on page state:
    //   - Toolbar (records exist):  t('createListener') = 'Create listener'
    //   - Empty state (no records): t('emptyStateButtonCreate', {resourceType: 'global user task listener'})
    //                               = 'Create global user task listener'
    this.createGlobalTaskListenerButton = page.getByRole('button', {
      name: /^Create (listener|global user task listener)$/,
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

    // PageEmptyState uses t('emptyStateTitleCreate', {resourceType: t('globalTaskListener').toLowerCase()})
    // = 'No global user task listeners created yet'
    this.emptyStateLocator = page.getByText(
      'No global user task listeners created yet',
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
    // Blur the type field first: Carbon's downshift loses the MultiSelect click if a
    // blur-triggered re-render fires simultaneously with the toggle button click.
    await this.createGlobalTaskListenerTypeField.blur();
    // Carbon MultiSelect: the toggle <button> sits inside a div[role="combobox"], so its
    // implicit button ARIA role is hidden. Use a CSS locator to find the <button> directly.
    const createEventTypeToggle = this.createGlobalTaskListenerModal.locator(
      '#event-type-multiselect button',
    );
    await createEventTypeToggle.click();
    // Wait for the listbox menu to open (Carbon renders it conditionally on isOpen).
    const createEventTypeMenu = this.createGlobalTaskListenerModal.locator(
      '#event-type-multiselect .cds--list-box__menu',
    );
    await expect(createEventTypeMenu).toBeVisible();
    // Carbon portals the listbox to document.body outside the dialog; the browser marks it as
    // aria-hidden via aria-modal, so getByRole('option') finds nothing. Use a CSS attribute
    // selector to bypass the aria-modal ARIA tree exclusion and find the item directly.
    await this.page
      .locator('[role="option"]', {hasText: eventTypeLabel})
      .click();
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
      await this.editGlobalTaskListenerTypeField.blur();
      const editEventTypeToggle = this.editGlobalTaskListenerModal.locator(
        '#event-type-multiselect-edit button',
      );
      await editEventTypeToggle.click();
      const editEventTypeMenu = this.editGlobalTaskListenerModal.locator(
        '#event-type-multiselect-edit .cds--list-box__menu',
      );
      await expect(editEventTypeMenu).toBeVisible();
      // Same aria-modal portal exclusion fix as in createGlobalTaskListener.
      await this.page
        .locator('[role="option"]', {hasText: newEventTypeLabel})
        .click();
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
