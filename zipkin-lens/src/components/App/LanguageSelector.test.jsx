/*
 * Copyright 2015-2020 The OpenZipkin Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
import { fireEvent, waitForElement } from '@testing-library/react'
import React from 'react';

import render from '../../test/util/render-with-default-settings';
import { getLocale } from '../../util/locale';

import LanguageSelector, { LANGUAGES } from './LanguageSelector';

test('loads and displays button and no popover', async () => {
  const { queryByTestId } = render(<LanguageSelector />);

  const changeLanguageButton = queryByTestId('change-language-button');
  const languageList = queryByTestId('language-list');

  expect(changeLanguageButton).toBeInTheDocument();
  expect(changeLanguageButton).toHaveAttribute('title', 'Change Language');

  expect(languageList).not.toBeInTheDocument();

  expect(getLocale()).toEqual('en');
});

test('click displays popover', async () => {
  const { queryByTestId } = render(<LanguageSelector />);

  const changeLanguageButton = queryByTestId('change-language-button');

  expect(changeLanguageButton).toBeInTheDocument();

  fireEvent.click(changeLanguageButton);

  const languageList = await waitForElement(() => queryByTestId('language-list'));

  expect(changeLanguageButton).toBeInTheDocument();
  expect(languageList).toBeInTheDocument();
  expect(languageList.children).toHaveLength(LANGUAGES.length);

  expect(getLocale()).toEqual('en');
});

test('language select changes locale and refreshes', async () => {
  const { queryByTestId } = render(<LanguageSelector />);

  const changeLanguageButton = queryByTestId('change-language-button');

  expect(changeLanguageButton).toBeInTheDocument();

  fireEvent.click(changeLanguageButton);

  await waitForElement(() => queryByTestId('language-list'));

  fireEvent.click(queryByTestId('language-list-item-zh-cn'));

  await expect(window.location.reload).toHaveBeenCalled();

  expect(getLocale()).toEqual('zh-cn');
});
