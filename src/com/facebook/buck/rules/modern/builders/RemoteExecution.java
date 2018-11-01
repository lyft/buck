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

package com.facebook.buck.rules.modern.builders;

import com.facebook.buck.core.cell.Cell;
import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.build.strategy.BuildRuleStrategy;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.io.file.MostFiles;
import com.facebook.buck.remoteexecution.ContentAddressedStorage;
import com.facebook.buck.remoteexecution.Protocol;
import com.facebook.buck.remoteexecution.Protocol.Digest;
import com.facebook.buck.remoteexecution.RemoteExecutionActionEvent;
import com.facebook.buck.remoteexecution.RemoteExecutionActionEvent.State;
import com.facebook.buck.remoteexecution.RemoteExecutionClients;
import com.facebook.buck.remoteexecution.RemoteExecutionService;
import com.facebook.buck.remoteexecution.RemoteExecutionService.ExecutionResult;
import com.facebook.buck.remoteexecution.util.FileTreeBuilder;
import com.facebook.buck.remoteexecution.util.FileTreeBuilder.InputFile;
import com.facebook.buck.remoteexecution.util.FileTreeBuilder.ProtocolTreeBuilder;
import com.facebook.buck.step.AbstractExecutionStep;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepFailedException;
import com.facebook.buck.util.MoreSuppliers;
import com.facebook.buck.util.Scope;
import com.facebook.buck.util.env.BuckClasspath;
import com.facebook.buck.util.function.ThrowingFunction;
import com.facebook.buck.util.function.ThrowingSupplier;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.hash.HashCode;
import com.google.common.util.concurrent.ListeningExecutorService;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * An implementation of IsolatedExecution that uses a ContentAddressedStorage and
 * RemoteExecutionService to execute build rules.
 *
 * <p>See https://docs.google.com/document/d/1AaGk7fOPByEvpAbqeXIyE8HX_A3_axxNnvroblTZ_6s/preview
 * for a high-level description of the approach to remote execution.
 */
public final class RemoteExecution implements IsolatedExecution {
  private static final Logger LOG = Logger.get(RemoteExecution.class);
  private static final Path TRAMPOLINE =
      Paths.get(
          System.getProperty(
              "buck.path_to_isolated_trampoline",
              "src/com/facebook/buck/rules/modern/builders/trampoline.sh"));

  private static final String pluginResources = System.getProperty("buck.module.resources");
  private static final String pluginRoot = System.getProperty("pf4j.pluginsDir");

  private final byte[] trampoline;
  private final BuckEventBus eventBus;

  private final ImmutableMap<Path, Supplier<InputFile>> classPath;
  private final ImmutableMap<Path, Supplier<InputFile>> bootstrapClassPath;
  private final ImmutableMap<Path, Supplier<InputFile>> pluginFiles;

  private final RemoteExecutionClients clients;

  /** Creates a BuildRuleStrategy for a particular */
  static BuildRuleStrategy createRemoteExecutionStrategy(
      BuckEventBus eventBus,
      Optional<ListeningExecutorService> remoteExecutorService,
      RemoteExecutionClients clients,
      SourcePathRuleFinder ruleFinder,
      CellPathResolver cellResolver,
      Cell rootCell,
      ThrowingFunction<Path, HashCode, IOException> fileHasher)
      throws IOException {
    return new IsolatedExecutionStrategy(
        new RemoteExecution(eventBus, clients),
        ruleFinder,
        cellResolver,
        rootCell,
        fileHasher,
        remoteExecutorService);
  }

  public RemoteExecution(BuckEventBus eventBus, RemoteExecutionClients clients) throws IOException {
    this.eventBus = eventBus;
    this.trampoline = Files.readAllBytes(TRAMPOLINE);

    this.classPath = prepareClassPath(BuckClasspath.getClasspath());
    this.bootstrapClassPath = prepareClassPath(BuckClasspath.getBootstrapClasspath());

    if (pluginResources == null || pluginRoot == null) {
      pluginFiles = ImmutableMap.of();
    } else {
      pluginFiles = prepareClassPath(findPlugins());
    }

    this.clients = clients;
  }

  protected ContentAddressedStorage getStorage() {
    return clients.getContentAddressedStorage();
  }

  protected RemoteExecutionService getExecutionService() {
    return clients.getRemoteExecutionService();
  }

  public BuckEventBus getEventBus() {
    return eventBus;
  }

  @Override
  public Protocol getProtocol() {
    return clients.getProtocol();
  }

  private static ImmutableList<Path> findPlugins() throws IOException {
    ImmutableList.Builder<Path> pathsBuilder = ImmutableList.builder();
    try (Stream<Path> files = Files.walk(Paths.get(pluginRoot))) {
      for (Path file : (Iterable<Path>) files::iterator) {
        if (Files.isRegularFile(file)) {
          pathsBuilder.add(file);
        }
      }
    }
    try (Stream<Path> files = Files.walk(Paths.get(pluginResources))) {
      for (Path file : (Iterable<Path>) files::iterator) {
        if (Files.isRegularFile(file)) {
          pathsBuilder.add(file);
        }
      }
    }
    return pathsBuilder.build();
  }

  @Override
  public void close() throws IOException {
    clients.close();
  }

  @Override
  public void build(
      ExecutionContext executionContext,
      FileTreeBuilder inputsBuilder,
      Set<Path> outputs,
      Path projectRoot,
      HashCode hash,
      BuildTarget buildTarget,
      Path cellPrefixRoot)
      throws IOException, InterruptedException, StepFailedException {

    HashMap<Digest, ThrowingSupplier<InputStream, IOException>> requiredDataBuilder;
    Digest actionDigest;

    try (Scope ignored =
        RemoteExecutionActionEvent.sendEvent(
            eventBus, State.DELETING_STALE_OUTPUTS, buildTarget, Optional.empty())) {
      for (Path path : outputs) {
        MostFiles.deleteRecursivelyIfExists(cellPrefixRoot.resolve(path));
      }
    }

    try (Scope ignored =
        RemoteExecutionActionEvent.sendEvent(
            eventBus, State.COMPUTING_ACTION, buildTarget, Optional.empty())) {
      ImmutableList<Path> isolatedClasspath =
          processClasspath(inputsBuilder, cellPrefixRoot, classPath);
      ImmutableList<Path> isolatedBootstrapClasspath =
          processClasspath(inputsBuilder, cellPrefixRoot, bootstrapClassPath);

      processClasspath(inputsBuilder, cellPrefixRoot, pluginFiles);

      Path trampolinePath = Paths.get("./__trampoline__.sh");
      ImmutableList<String> command =
          getBuilderCommand(trampolinePath, projectRoot, hash.toString());
      ImmutableSortedMap<String, String> commandEnvironment =
          getBuilderEnvironmentOverrides(
              isolatedBootstrapClasspath, isolatedClasspath, cellPrefixRoot);

      inputsBuilder.addFile(
          trampolinePath,
          () -> trampoline,
          data -> getProtocol().getHashFunction().hashBytes(data).toString(),
          true);

      Protocol.Command actionCommand =
          getProtocol().newCommand(command, commandEnvironment, outputs);

      requiredDataBuilder = new HashMap<>();
      ProtocolTreeBuilder grpcTreeBuilder =
          new ProtocolTreeBuilder(requiredDataBuilder::put, directory -> {}, getProtocol());
      Digest inputsRootDigest = inputsBuilder.buildTree(grpcTreeBuilder);
      byte[] commandData = getProtocol().toByteArray(actionCommand);
      Digest commandDigest = getProtocol().computeDigest(commandData);
      requiredDataBuilder.put(commandDigest, () -> new ByteArrayInputStream(commandData));

      Protocol.Action action = getProtocol().newAction(commandDigest, inputsRootDigest);
      byte[] actionData = getProtocol().toByteArray(action);
      actionDigest = getProtocol().computeDigest(actionData);
      requiredDataBuilder.put(actionDigest, () -> new ByteArrayInputStream(actionData));
    }

    try (Scope scope =
        RemoteExecutionActionEvent.sendEvent(
            eventBus, State.UPLOADING_INPUTS, buildTarget, Optional.of(actionDigest))) {
      getStorage().addMissing(ImmutableMap.copyOf(requiredDataBuilder));
    }

    ExecutionResult result = null;
    try (Scope scope =
        RemoteExecutionActionEvent.sendEvent(
            eventBus, State.EXECUTING, buildTarget, Optional.of(actionDigest))) {
      result = getExecutionService().execute(actionDigest);
    }

    if (result.getExitCode() == 0) {
      try (Scope scope =
          RemoteExecutionActionEvent.sendEvent(
              eventBus, State.MATERIALIZING_OUTPUTS, buildTarget, Optional.of(actionDigest))) {
        getStorage()
            .materializeOutputs(
                result.getOutputDirectories(), result.getOutputFiles(), cellPrefixRoot);
        RemoteExecutionActionEvent.sendTerminalEvent(
            eventBus, State.ACTION_SUCCEEDED, buildTarget, Optional.of(actionDigest));
      }
    } else {
      LOG.error(
          "Failed to build target [%s] with exit code [%d]. stderr: %s",
          buildTarget.getFullyQualifiedName(),
          result.getExitCode(),
          result.getStderr().orElse("<empty>"));
      RemoteExecutionActionEvent.sendTerminalEvent(
          eventBus, State.ACTION_FAILED, buildTarget, Optional.empty());
      throw StepFailedException.createForFailingStepWithExitCode(
          new AbstractExecutionStep("remote_execution") {
            @Override
            public StepExecutionResult execute(ExecutionContext context) {
              throw new RuntimeException();
            }
          },
          executionContext,
          StepExecutionResult.of(result.getExitCode(), result.getStderr()));
    }
  }

  private ImmutableSortedMap<String, String> getBuilderEnvironmentOverrides(
      ImmutableList<Path> bootstrapClasspath, Iterable<Path> classpath, Path cellPrefixRoot) {

    // TODO(shivanker): Pass all user environment overrides to remote workers.
    String relativePluginRoot = "";
    if (pluginRoot != null) {
      Path rootPath = Paths.get(pluginRoot);
      relativePluginRoot =
          (rootPath.isAbsolute() ? cellPrefixRoot.relativize(Paths.get(pluginRoot)) : pluginRoot)
              .toString();
    }
    String relativePluginResources =
        pluginResources == null
            ? ""
            : cellPrefixRoot.relativize(Paths.get(pluginResources)).toString();
    return ImmutableSortedMap.of(
        "CLASSPATH",
        classpathArg(bootstrapClasspath),
        "BUCK_CLASSPATH",
        classpathArg(classpath),
        "BUCK_PLUGIN_ROOT",
        relativePluginRoot,
        "BUCK_PLUGIN_RESOURCES",
        relativePluginResources,
        // TODO(cjhopman): This shouldn't be done here, it's not a Buck thing.
        "BUCK_DISTCC",
        "0");
  }

  private ImmutableList<String> getBuilderCommand(
      Path trampolinePath, Path projectRoot, String hash) {
    String rootString = projectRoot.toString();
    if (rootString.isEmpty()) {
      rootString = "./";
    }
    return ImmutableList.of(trampolinePath.toString(), rootString, hash);
  }

  private ImmutableMap<Path, Supplier<InputFile>> prepareClassPath(ImmutableList<Path> classpath) {
    ImmutableMap.Builder<Path, Supplier<InputFile>> resultBuilder = ImmutableMap.builder();
    for (Path path : classpath) {
      resultBuilder.put(
          path,
          MoreSuppliers.memoize(
              () -> {
                try {
                  return new InputFile(
                      getProtocol()
                          .getHashFunction()
                          .hashBytes(Files.readAllBytes(path))
                          .toString(),
                      (int) Files.size(path),
                      false,
                      () -> new FileInputStream(path.toFile()));
                } catch (IOException e) {
                  throw new RuntimeException(e);
                }
              }));
    }
    return resultBuilder.build();
  }

  private String classpathArg(Iterable<Path> classpath) {
    return Joiner.on(File.pathSeparator).join(classpath);
  }

  private ImmutableList<Path> processClasspath(
      FileTreeBuilder inputsBuilder,
      Path cellPrefix,
      ImmutableMap<Path, Supplier<InputFile>> classPath)
      throws IOException {
    ImmutableList.Builder<Path> resolvedBuilder = ImmutableList.builder();
    for (Map.Entry<Path, Supplier<InputFile>> entry : classPath.entrySet()) {
      Path path = entry.getKey();
      Preconditions.checkState(path.isAbsolute());
      if (!path.startsWith(cellPrefix)) {
        resolvedBuilder.add(path);
      } else {
        Path relative = cellPrefix.relativize(path);
        inputsBuilder.addFile(relative, () -> entry.getValue().get());
        resolvedBuilder.add(relative);
      }
    }
    return resolvedBuilder.build();
  }
}
