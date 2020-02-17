/*
 * Copyright 2015-2019 The OpenZipkin Authors
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

/* eslint-disable import/no-extraneous-dependencies */

import '@testing-library/jest-dom';

const Enzyme = require('enzyme');
const EnzymeAdapter = require('enzyme-adapter-react-16');

Enzyme.configure({ adapter: new EnzymeAdapter() });

// Set english as browser locale by default.
Object.defineProperty(window.navigator, 'language', { value: 'en-US', configurable: true });

// Mock out browser refresh.
const { reload } = window.location;

beforeAll(() => {
  Object.defineProperty(window.location, 'reload', {
    configurable: true,
  });
  window.location.reload = jest.fn();
});

afterAll(() => {
  window.location.reload = reload;
});
