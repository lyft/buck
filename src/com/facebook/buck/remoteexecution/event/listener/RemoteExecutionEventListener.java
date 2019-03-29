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

package com.facebook.buck.remoteexecution.event.listener;

import com.facebook.buck.core.build.event.BuildRuleEvent;
import com.facebook.buck.event.BuckEventListener;
import com.facebook.buck.remoteexecution.event.CasBlobDownloadEvent;
import com.facebook.buck.remoteexecution.event.CasBlobUploadEvent.Finished;
import com.facebook.buck.remoteexecution.event.LocalFallbackEvent;
import com.facebook.buck.remoteexecution.event.LocalFallbackEvent.Result;
import com.facebook.buck.remoteexecution.event.RemoteExecutionActionEvent;
import com.facebook.buck.remoteexecution.event.RemoteExecutionActionEvent.State;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import com.google.common.eventbus.Subscribe;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/** Remote execution events sent to the event bus. */
public class RemoteExecutionEventListener
    implements BuckEventListener, RemoteExecutionStatsProvider {
  private final Map<State, AtomicInteger> actionStateCount;
  private final AtomicInteger totalBuildRules;

  private final AtomicInteger downloads;
  private final AtomicLong donwloadBytes;
  private final AtomicInteger uploads;
  private final AtomicLong uploadBytes;

  private final AtomicLong remoteCpuTime;

  private final AtomicBoolean hasFirstRemoteActionStarted;

  private final AtomicInteger localFallbackTotalExecutions;
  private final AtomicInteger localFallbackLocalExecutions;
  private final AtomicInteger localFallbackSuccessfulLocalExecutions;

  public RemoteExecutionEventListener() {
    this.downloads = new AtomicInteger(0);
    this.donwloadBytes = new AtomicLong(0);
    this.uploads = new AtomicInteger(0);
    this.uploadBytes = new AtomicLong(0);
    this.remoteCpuTime = new AtomicLong(0);
    this.totalBuildRules = new AtomicInteger(0);
    this.hasFirstRemoteActionStarted = new AtomicBoolean(false);

    localFallbackTotalExecutions = new AtomicInteger(0);
    localFallbackLocalExecutions = new AtomicInteger(0);
    localFallbackSuccessfulLocalExecutions = new AtomicInteger(0);

    this.actionStateCount = Maps.newConcurrentMap();
    for (State state : RemoteExecutionActionEvent.State.values()) {
      actionStateCount.put(state, new AtomicInteger(0));
    }
  }

  /** Event specific subscriber method. */
  @Subscribe
  public void onBuildRuleEvent(@SuppressWarnings("unused") BuildRuleEvent.Finished event) {
    totalBuildRules.incrementAndGet();
  }

  /** Event specific subscriber method. */
  @Subscribe
  public void onCasUploadEvent(Finished event) {
    hasFirstRemoteActionStarted.set(true);
    uploads.addAndGet(event.getStartedEvent().getBlobCount());
    uploadBytes.addAndGet(event.getStartedEvent().getSizeBytes());
  }

  /** Event specific subscriber method. */
  @Subscribe
  public void onCasDownloadEvent(CasBlobDownloadEvent.Finished event) {
    hasFirstRemoteActionStarted.set(true);
    downloads.addAndGet(event.getStartedEvent().getBlobCount());
    donwloadBytes.addAndGet(event.getStartedEvent().getSizeBytes());
  }

  /** Event specific subscriber method. */
  @Subscribe
  public void onActionScheduled(
      @SuppressWarnings("unused") RemoteExecutionActionEvent.Scheduled event) {
    hasFirstRemoteActionStarted.set(true);
    getStateCount(State.WAITING).incrementAndGet();
  }

  /** Event specific subscriber method. */
  @Subscribe
  public void onActionEventTerminal(RemoteExecutionActionEvent.Terminal event) {
    hasFirstRemoteActionStarted.set(true);
    getStateCount(State.WAITING).decrementAndGet();
    getStateCount(event.getState()).incrementAndGet();
    if (event.getExecutedActionMetadata().isPresent()) {
      remoteCpuTime.addAndGet(
          event.getExecutedActionMetadata().get().getExecutionCompletedTimestamp().getSeconds()
              - event.getExecutedActionMetadata().get().getExecutionStartTimestamp().getSeconds());
    }
  }

  /** Event specific subscriber method. */
  @Subscribe
  public void onActionEventStarted(RemoteExecutionActionEvent.Started event) {
    hasFirstRemoteActionStarted.set(true);
    getStateCount(State.WAITING).decrementAndGet();
    getStateCount(event.getState()).incrementAndGet();
  }

  public AtomicInteger getStateCount(State waiting) {
    return Objects.requireNonNull(actionStateCount.get(waiting));
  }

  /** Event specific subscriber method. */
  @Subscribe
  public void onActionEventFinished(RemoteExecutionActionEvent.Finished event) {
    hasFirstRemoteActionStarted.set(true);
    getStateCount(State.WAITING).incrementAndGet();
    getStateCount(event.getStartedEvent().getState()).decrementAndGet();
  }

  /** Events from the LocalFallback stats. */
  @Subscribe
  public void onLocalFallbackEventFinished(LocalFallbackEvent.Finished event) {
    localFallbackTotalExecutions.incrementAndGet();

    if (event.getLocalResult() != Result.NOT_RUN) {
      localFallbackLocalExecutions.incrementAndGet();
    }

    if (event.getLocalResult() == Result.SUCCESS) {
      localFallbackSuccessfulLocalExecutions.incrementAndGet();
    }
  }

  @Override
  public ImmutableMap<State, Integer> getActionsPerState() {
    return ImmutableMap.copyOf(
        actionStateCount
            .entrySet()
            .stream()
            .collect(Collectors.toMap(entry -> entry.getKey(), entry -> entry.getValue().get())));
  }

  @Override
  public int getCasDownloads() {
    return downloads.get();
  }

  @Override
  public long getCasDownloadSizeBytes() {
    return donwloadBytes.get();
  }

  @Override
  public int getCasUploads() {
    return uploads.get();
  }

  @Override
  public long getCasUploadSizeBytes() {
    return uploadBytes.get();
  }

  @Override
  public int getTotalRulesBuilt() {
    return totalBuildRules.get();
  }

  @Override
  public LocalFallbackStats getLocalFallbackStats() {
    return LocalFallbackStats.builder()
        .setLocallyExecutedRules(localFallbackLocalExecutions.get())
        .setLocallySuccessfulRules(localFallbackSuccessfulLocalExecutions.get())
        .setTotalExecutedRules(localFallbackTotalExecutions.get())
        .build();
  }

  @Override
  public long getRemoteCpuTime() {
    return remoteCpuTime.get();
  }
}
