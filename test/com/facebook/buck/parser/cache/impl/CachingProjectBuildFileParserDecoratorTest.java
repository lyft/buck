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

package com.facebook.buck.parser.cache.impl;

import static org.junit.Assert.assertEquals;

import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.config.FakeBuckConfig;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.BuckEventBusForTests;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.parser.api.BuildFileManifest;
import com.facebook.buck.parser.api.ProjectBuildFileParser;
import com.facebook.buck.parser.exceptions.BuildFileParseException;
import com.facebook.buck.skylark.io.GlobSpecWithResult;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

/** Tests for the {@link CachingProjectBuildFileParserDecorator} */
public class CachingProjectBuildFileParserDecoratorTest {
  @Rule public ExpectedException expectedException = ExpectedException.none();

  private BuckEventBus buckEventBus;
  private ProjectFilesystem filesystem;
  private ProjectBuildFileParser fakeParser;
  private BuildFileManifest fakeParserManifest;

  /** Fake parser object with fake preset values */
  class FakeProjectBuildParser implements ProjectBuildFileParser {

    @Override
    public BuildFileManifest getBuildFileManifest(Path buildFile)
        throws BuildFileParseException, InterruptedException, IOException {
      ImmutableMap<String, Object> targetMap = ImmutableMap.of("foo", "foo", "bar", "bar");

      return BuildFileManifest.of(
          ImmutableMap.of("tar1", targetMap),
          ImmutableSortedSet.of(),
          ImmutableMap.of(),
          Optional.empty(),
          ImmutableList.of());
    }

    @Override
    public void reportProfile() throws IOException {}

    @Override
    public ImmutableList<String> getIncludedFiles(Path buildFile)
        throws BuildFileParseException, InterruptedException, IOException {
      return ImmutableList.of();
    }

    @Override
    public boolean globResultsMatchCurrentState(
        Path buildFile, ImmutableList<GlobSpecWithResult> existingGlobsWithResults)
        throws IOException, InterruptedException {
      return false;
    }

    @Override
    public void close() throws BuildFileParseException, InterruptedException, IOException {}
  }

  private BuckConfig getConfig(ProjectFilesystem fileSystem, String accessMode) {
    FakeBuckConfig.Builder builder = FakeBuckConfig.builder();

    builder
        .setSections(
            "[" + ParserCacheConfig.PARSER_CACHE_SECTION_NAME + "]",
            ParserCacheConfig.PARSER_CACHE_LOCAL_LOCATION_NAME + " = foobar",
            "dir_mode = " + accessMode)
        .setFilesystem(fileSystem);
    return builder.build();
  }

  @Before
  public void setUp() throws IOException, InterruptedException {
    filesystem = FakeProjectFilesystem.createRealTempFilesystem();
    fakeParser = new FakeProjectBuildParser();
    fakeParserManifest = fakeParser.getBuildFileManifest(filesystem.resolve("BUCK"));
    buckEventBus = BuckEventBusForTests.newInstance();
  }

  @Test
  public void cachingParserProducesSameResultAsDelegate() throws IOException, InterruptedException {
    BuckConfig buckConfig = getConfig(filesystem, "readwrite");
    ProjectBuildFileParser cachingParser =
        CachingProjectBuildFileParserDecorator.of(
            ParserCacheImpl.of(buckConfig, filesystem), fakeParser);

    BuildFileManifest cachingParserManifest =
        cachingParser.getBuildFileManifest(filesystem.resolve("BUCK"));

    assertEquals(fakeParserManifest, cachingParserManifest);
  }

  @Test
  public void cachingParserCreationThrowsIfCacheNotDefined() {
    expectedException.expect(IllegalStateException.class);
    BuckConfig emptyBuckConfig = FakeBuckConfig.builder().build();
    CachingProjectBuildFileParserDecorator.of(
        ParserCacheImpl.of(emptyBuckConfig, filesystem), fakeParser);
  }

  @Test
  public void cachingParserProducesSameResultCacheAccessReadOnly()
      throws IOException, InterruptedException {
    BuckConfig buckConfig = getConfig(filesystem, "readonly");
    ProjectBuildFileParser cachingParser =
        CachingProjectBuildFileParserDecorator.of(
            ParserCacheImpl.of(buckConfig, filesystem), fakeParser);

    BuildFileManifest cachingParserManifest =
        cachingParser.getBuildFileManifest(filesystem.resolve("BUCK"));

    assertEquals(fakeParserManifest, cachingParserManifest);
  }

  @Test
  public void cachingParserProducesSameResultCacheAccessWriteOnly()
      throws IOException, InterruptedException {
    BuckConfig buckConfig = getConfig(filesystem, "writeonly");
    ProjectBuildFileParser cachingParser =
        CachingProjectBuildFileParserDecorator.of(
            ParserCacheImpl.of(buckConfig, filesystem), fakeParser);

    BuildFileManifest cachingParserManifest =
        cachingParser.getBuildFileManifest(filesystem.resolve("BUCK"));

    assertEquals(fakeParserManifest, cachingParserManifest);
  }
}
