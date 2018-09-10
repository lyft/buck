/*
 * Copyright 2018-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.facebook.buck.support.bgtasks;

import com.facebook.buck.core.model.BuildId;
import com.google.common.collect.ImmutableList;
import java.util.concurrent.TimeUnit;

/**
 * BackgroundTaskManager schedules and runs background bgtasks like cleanup/logging. A manager
 * should be notified when a new command starts and when it finishes so that it can schedule bgtasks
 * appropriately. Tasks should typically be scheduled through a {@link TaskManagerScope}.
 */
public interface BackgroundTaskManager {

  /**
   * Code passed to notify(). COMMAND_START: when buck command is started COMMAND_END: when buck
   * command has finished, used to trigger background task execution
   */
  enum Notification {
    COMMAND_START,
    COMMAND_END
  }

  /**
   * Returns a new {@link TaskManagerScope} for a build on this manager.
   *
   * @return new scope
   */
  TaskManagerScope getNewScope(BuildId buildId);

  /**
   * Schedule a task to be run in the background.
   *
   * @param task {@link ManagedBackgroundTask} object to be run
   */
  void schedule(ManagedBackgroundTask task);

  // todo removed in stacked diff
  void schedule(BackgroundTask<?> task);

  // todo removed in stacked diff
  void schedule(ImmutableList<? extends BackgroundTask<?>> taskList);

  /**
   * Notify the manager of some event, e.g. command start/end. Exceptions should generally be caught
   * and handled by the manager, except in test implementations.
   *
   * @param code Type of event to notify of
   */
  void notify(Notification code);

  /** Shut down manager, without waiting for tasks to finish. */
  void shutdownNow();

  /**
   * Shut down manager, waiting until given timeout for tasks to finish.
   *
   * @param timeout timeout for tasks to finish
   * @param units units of timeout
   */
  void shutdown(long timeout, TimeUnit units) throws InterruptedException;
}
