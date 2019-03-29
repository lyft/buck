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

import com.facebook.buck.remoteexecution.event.RemoteExecutionActionEvent.State;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import java.util.List;
import java.util.Map;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class RemoteExecutionConsoleLineProviderTest {
  private final String SESSION_ID_INFO = "super cool info about the session";

  private TestStatsProvider statsProvider;

  @Before
  public void setUp() {
    this.statsProvider = new TestStatsProvider();
  }

  @Test
  public void testConsoleOutput() {
    statsProvider.casDownladedBytes = 42;
    statsProvider.casDownloads = 21;
    statsProvider.actionsPerState.put(State.ACTION_SUCCEEDED, 84);
    RemoteExecutionConsoleLineProvider provider =
        new RemoteExecutionConsoleLineProvider(statsProvider, SESSION_ID_INFO, true);
    List<String> lines = provider.createConsoleLinesAtTime(0);
    Assert.assertEquals(4, lines.size());
    Assert.assertEquals(
        "[RE] Metadata: Session ID=[super cool info about the session]", lines.get(0));
    Assert.assertEquals(
        "[RE] Actions: Local=0 Remote=[wait=0 del=0 comp=0 upl=0 exec=0 dwl=0 suc=84 fail=0 cncl=0]",
        lines.get(1));
    Assert.assertEquals(
        "[RE] CAS: Upl=[Count:0 Size=0.00 bytes] Dwl=[Count:21 Size=42.00 bytes]", lines.get(2));
    Assert.assertEquals(
        "[RE] LocalFallback: [fallback_rate=50.00% remote=42 local=21]", lines.get(3));
  }

  @Test
  public void testNoLocalFallback() {
    statsProvider.casDownladedBytes = 42;
    statsProvider.casDownloads = 21;
    statsProvider.actionsPerState.put(State.ACTION_SUCCEEDED, 84);
    statsProvider.localFallbackStats =
        LocalFallbackStats.builder()
            .from(statsProvider.localFallbackStats)
            .setLocallyExecutedRules(0)
            .build();
    RemoteExecutionConsoleLineProvider provider =
        new RemoteExecutionConsoleLineProvider(statsProvider, SESSION_ID_INFO, true);
    List<String> lines = provider.createConsoleLinesAtTime(0);
    Assert.assertEquals(3, lines.size());
    for (String line : lines) {
      Assert.assertFalse(line.contains("LocalFallback"));
    }
  }

  @Test
  public void testNoDebug() {
    statsProvider.casDownladedBytes = 42;
    statsProvider.casDownloads = 21;
    statsProvider.actionsPerState.put(State.ACTION_SUCCEEDED, 84);
    statsProvider.localFallbackStats =
        LocalFallbackStats.builder()
            .from(statsProvider.localFallbackStats)
            .setLocallyExecutedRules(0)
            .build();
    RemoteExecutionConsoleLineProvider provider =
        new RemoteExecutionConsoleLineProvider(statsProvider, SESSION_ID_INFO, false);
    List<String> lines = provider.createConsoleLinesAtTime(0);
    Assert.assertEquals(1, lines.size());
    Assert.assertEquals(
        lines.get(0), "Building with Remote Execution: 1:05 minutes spent building remotely");
    for (String line : lines) {
      Assert.assertFalse(line.contains("LocalFallback"));
      Assert.assertFalse(line.contains("[RE]"));
    }
  }

  @Test
  public void testDebugFormatConsoleOutput() {
    statsProvider.casDownladedBytes = 42;
    statsProvider.casDownloads = 21;
    statsProvider.actionsPerState.put(State.ACTION_SUCCEEDED, 84);

    RemoteExecutionConsoleLineProvider provider =
        new RemoteExecutionConsoleLineProvider(statsProvider, SESSION_ID_INFO, true);
    List<String> lines = provider.createConsoleLinesAtTime(0);
    Assert.assertEquals(4, lines.size());
    Assert.assertEquals(
        "[RE] Metadata: Session ID=[super cool info about the session]", lines.get(0));
    Assert.assertEquals(
        "[RE] Actions: Local=0 Remote=[wait=0 del=0 comp=0 upl=0 exec=0 dwl=0 suc=84 fail=0 cncl=0]",
        lines.get(1));
    Assert.assertEquals(
        "[RE] CAS: Upl=[Count:0 Size=0.00 bytes] Dwl=[Count:21 Size=42.00 bytes]", lines.get(2));
    Assert.assertEquals(
        "[RE] LocalFallback: [fallback_rate=50.00% remote=42 local=21]", lines.get(3));
  }

  private static final class TestStatsProvider implements RemoteExecutionStatsProvider {
    public Map<State, Integer> actionsPerState = Maps.newHashMap();
    public int casDownloads = 0;
    public int casDownladedBytes = 0;
    public LocalFallbackStats localFallbackStats =
        LocalFallbackStats.builder()
            .setTotalExecutedRules(84)
            .setLocallyExecutedRules(42)
            .setLocallySuccessfulRules(21)
            .build();

    public TestStatsProvider() {
      for (State state : State.values()) {
        actionsPerState.put(state, new Integer(0));
      }
    }

    @Override
    public ImmutableMap<State, Integer> getActionsPerState() {
      return ImmutableMap.copyOf(actionsPerState);
    }

    @Override
    public int getCasDownloads() {
      return casDownloads;
    }

    @Override
    public long getCasDownloadSizeBytes() {
      return casDownladedBytes;
    }

    @Override
    public int getCasUploads() {
      return 0;
    }

    @Override
    public long getCasUploadSizeBytes() {
      return 0;
    }

    @Override
    public int getTotalRulesBuilt() {
      return 0;
    }

    @Override
    public LocalFallbackStats getLocalFallbackStats() {
      return localFallbackStats;
    }

    @Override
    public long getRemoteCpuTime() {
      return 65;
    }
  }
}
