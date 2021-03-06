/*
 * Copyright 2017 The Bazel Authors. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.idea.sdkcompat.smrunner;

import com.intellij.execution.testframework.TestConsoleProperties;
import com.intellij.execution.testframework.sm.runner.OutputToGeneralTestEventsConverter;

/** Adapter to bridge different SDK versions. */
public abstract class OutputToGeneralTestEventsConverterAdapter
    extends OutputToGeneralTestEventsConverter {

  public OutputToGeneralTestEventsConverterAdapter(
      String testFrameworkName, TestConsoleProperties consoleProperties) {
    super(testFrameworkName, consoleProperties);
  }

  public OutputToGeneralTestEventsConverterAdapter(String testFrameworkName, boolean stdinEnabled) {
    super(testFrameworkName, stdinEnabled);
  }

  protected abstract void processTestSuites();

  @Override
  public void flushBufferOnProcessTermination(int exitCode) {
    super.flushBufferOnProcessTermination(exitCode);
    processTestSuites();
  }
}
