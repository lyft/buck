/*
 * Copyright 2012-present Facebook, Inc.
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

package com.facebook.buck.cli;

import static com.facebook.buck.util.AnsiEnvironmentChecking.NAILGUN_STDERR_ISTTY_ENV;
import static com.facebook.buck.util.AnsiEnvironmentChecking.NAILGUN_STDOUT_ISTTY_ENV;
import static com.facebook.buck.util.string.MoreStrings.linesToText;
import static com.google.common.util.concurrent.MoreExecutors.listeningDecorator;
import static com.google.common.util.concurrent.MoreExecutors.newDirectExecutorService;

import com.facebook.buck.artifact_cache.ArtifactCaches;
import com.facebook.buck.artifact_cache.ClientCertificateHandler;
import com.facebook.buck.artifact_cache.config.ArtifactCacheBuckConfig;
import com.facebook.buck.artifact_cache.config.ArtifactCacheBuckConfig.Executor;
import com.facebook.buck.cli.DaemonLifecycleManager.DaemonStatus;
import com.facebook.buck.cli.exceptions.handlers.ExceptionHandlerRegistryFactory;
import com.facebook.buck.command.config.BuildBuckConfig;
import com.facebook.buck.core.build.engine.cache.manager.BuildInfoStoreManager;
import com.facebook.buck.core.cell.Cell;
import com.facebook.buck.core.cell.CellName;
import com.facebook.buck.core.cell.InvalidCellOverrideException;
import com.facebook.buck.core.cell.impl.DefaultCellPathResolver;
import com.facebook.buck.core.cell.impl.LocalCellProviderFactory;
import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.exceptions.config.ErrorHandlingBuckConfig;
import com.facebook.buck.core.exceptions.handler.HumanReadableExceptionAugmentor;
import com.facebook.buck.core.model.BuildId;
import com.facebook.buck.core.model.EmptyTargetConfiguration;
import com.facebook.buck.core.model.TargetConfigurationSerializer;
import com.facebook.buck.core.model.actiongraph.computation.ActionGraphCache;
import com.facebook.buck.core.model.actiongraph.computation.ActionGraphFactory;
import com.facebook.buck.core.model.actiongraph.computation.ActionGraphProvider;
import com.facebook.buck.core.model.impl.JsonTargetConfigurationSerializer;
import com.facebook.buck.core.module.BuckModuleManager;
import com.facebook.buck.core.module.impl.BuckModuleJarHashProvider;
import com.facebook.buck.core.module.impl.DefaultBuckModuleManager;
import com.facebook.buck.core.parser.buildtargetparser.ParsingUnconfiguredBuildTargetFactory;
import com.facebook.buck.core.parser.buildtargetparser.UnconfiguredBuildTargetFactory;
import com.facebook.buck.core.plugin.impl.BuckPluginManagerFactory;
import com.facebook.buck.core.resources.ResourcesConfig;
import com.facebook.buck.core.rulekey.RuleKey;
import com.facebook.buck.core.rules.config.ConfigurationRuleDescription;
import com.facebook.buck.core.rules.config.impl.PluginBasedKnownConfigurationDescriptionsFactory;
import com.facebook.buck.core.rules.knowntypes.DefaultKnownRuleTypesFactory;
import com.facebook.buck.core.rules.knowntypes.KnownRuleTypesFactory;
import com.facebook.buck.core.rules.knowntypes.KnownRuleTypesProvider;
import com.facebook.buck.core.toolchain.ToolchainProviderFactory;
import com.facebook.buck.core.toolchain.impl.DefaultToolchainProviderFactory;
import com.facebook.buck.core.util.immutables.BuckStyleTuple;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.counters.CounterBuckConfig;
import com.facebook.buck.counters.CounterRegistry;
import com.facebook.buck.counters.CounterRegistryImpl;
import com.facebook.buck.distributed.DistBuildConfig;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.BuckEventListener;
import com.facebook.buck.event.BuckInitializationDurationEvent;
import com.facebook.buck.event.CacheStatsEvent;
import com.facebook.buck.event.CommandEvent;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.event.DaemonEvent;
import com.facebook.buck.event.DefaultBuckEventBus;
import com.facebook.buck.event.chrome_trace.ChromeTraceBuckConfig;
import com.facebook.buck.event.listener.AbstractConsoleEventBusListener;
import com.facebook.buck.event.listener.CacheRateStatsListener;
import com.facebook.buck.event.listener.ChromeTraceBuildListener;
import com.facebook.buck.event.listener.FileSerializationEventBusListener;
import com.facebook.buck.event.listener.JavaUtilsLoggingBuildListener;
import com.facebook.buck.event.listener.LoadBalancerEventsListener;
import com.facebook.buck.event.listener.LogUploaderListener;
import com.facebook.buck.event.listener.LoggingBuildListener;
import com.facebook.buck.event.listener.MachineReadableLoggerListener;
import com.facebook.buck.event.listener.ParserProfilerLoggerListener;
import com.facebook.buck.event.listener.PublicAnnouncementManager;
import com.facebook.buck.event.listener.RenderingConsole;
import com.facebook.buck.event.listener.RuleKeyDiagnosticsListener;
import com.facebook.buck.event.listener.RuleKeyLoggerListener;
import com.facebook.buck.event.listener.SilentConsoleEventBusListener;
import com.facebook.buck.event.listener.SimpleConsoleEventBusListener;
import com.facebook.buck.event.listener.SuperConsoleConfig;
import com.facebook.buck.event.listener.SuperConsoleEventBusListener;
import com.facebook.buck.event.listener.devspeed.DevspeedTelemetryPlugin;
import com.facebook.buck.event.listener.interfaces.AdditionalConsoleLineProvider;
import com.facebook.buck.event.listener.util.ProgressEstimator;
import com.facebook.buck.httpserver.WebServer;
import com.facebook.buck.io.AsynchronousDirectoryContentsCleaner;
import com.facebook.buck.io.ExecutableFinder;
import com.facebook.buck.io.file.MostFiles;
import com.facebook.buck.io.filesystem.BuckPaths;
import com.facebook.buck.io.filesystem.ExactPathMatcher;
import com.facebook.buck.io.filesystem.FileExtensionMatcher;
import com.facebook.buck.io.filesystem.GlobPatternMatcher;
import com.facebook.buck.io.filesystem.PathMatcher;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.ProjectFilesystemFactory;
import com.facebook.buck.io.filesystem.impl.DefaultProjectFilesystemFactory;
import com.facebook.buck.io.watchman.Watchman;
import com.facebook.buck.io.watchman.WatchmanDiagnosticEventListener;
import com.facebook.buck.io.watchman.WatchmanFactory;
import com.facebook.buck.io.watchman.WatchmanWatcher;
import com.facebook.buck.io.watchman.WatchmanWatcher.FreshInstanceAction;
import com.facebook.buck.io.watchman.WatchmanWatcherException;
import com.facebook.buck.jvm.java.JavaBuckConfig;
import com.facebook.buck.log.ConsoleHandlerState;
import com.facebook.buck.log.GlobalStateManager;
import com.facebook.buck.log.InvocationInfo;
import com.facebook.buck.log.LogConfig;
import com.facebook.buck.manifestservice.ManifestService;
import com.facebook.buck.manifestservice.ManifestServiceConfig;
import com.facebook.buck.parser.DaemonicParserState;
import com.facebook.buck.parser.Parser;
import com.facebook.buck.parser.ParserConfig;
import com.facebook.buck.parser.ParserFactory;
import com.facebook.buck.parser.ParserPythonInterpreterProvider;
import com.facebook.buck.parser.TargetSpecResolver;
import com.facebook.buck.remoteexecution.MetadataProviderFactory;
import com.facebook.buck.remoteexecution.config.RemoteExecutionConfig;
import com.facebook.buck.remoteexecution.event.listener.RemoteExecutionConsoleLineProvider;
import com.facebook.buck.remoteexecution.event.listener.RemoteExecutionEventListener;
import com.facebook.buck.remoteexecution.interfaces.MetadataProvider;
import com.facebook.buck.rules.coercer.DefaultConstructorArgMarshaller;
import com.facebook.buck.rules.coercer.DefaultTypeCoercerFactory;
import com.facebook.buck.rules.coercer.TypeCoercerFactory;
import com.facebook.buck.rules.keys.RuleKeyCacheRecycler;
import com.facebook.buck.rules.keys.config.RuleKeyConfiguration;
import com.facebook.buck.rules.keys.config.impl.ConfigRuleKeyConfigurationFactory;
import com.facebook.buck.rules.modern.config.ModernBuildRuleBuildStrategy;
import com.facebook.buck.rules.modern.config.ModernBuildRuleConfig;
import com.facebook.buck.rules.modern.config.ModernBuildRuleStrategyConfig;
import com.facebook.buck.sandbox.SandboxExecutionStrategyFactory;
import com.facebook.buck.sandbox.impl.PlatformSandboxExecutionStrategyFactory;
import com.facebook.buck.support.bgtasks.TaskManagerScope;
import com.facebook.buck.support.cli.args.BuckArgsMethods;
import com.facebook.buck.support.cli.args.GlobalCliOptions;
import com.facebook.buck.support.log.LogBuckConfig;
import com.facebook.buck.test.config.TestBuckConfig;
import com.facebook.buck.test.config.TestResultSummaryVerbosity;
import com.facebook.buck.util.Ansi;
import com.facebook.buck.util.AnsiEnvironmentChecking;
import com.facebook.buck.util.BgProcessKiller;
import com.facebook.buck.util.CloseableMemoizedSupplier;
import com.facebook.buck.util.CloseableWrapper;
import com.facebook.buck.util.CommandLineException;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.DefaultProcessExecutor;
import com.facebook.buck.util.ErrorLogger;
import com.facebook.buck.util.ErrorLogger.LogImpl;
import com.facebook.buck.util.ExitCode;
import com.facebook.buck.util.Libc;
import com.facebook.buck.util.PkillProcessManager;
import com.facebook.buck.util.PrintStreamProcessExecutorFactory;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.ProcessManager;
import com.facebook.buck.util.Scope;
import com.facebook.buck.util.ThrowingCloseableMemoizedSupplier;
import com.facebook.buck.util.ThrowingCloseableWrapper;
import com.facebook.buck.util.Verbosity;
import com.facebook.buck.util.cache.FileHashCache;
import com.facebook.buck.util.cache.InstrumentingCacheStatsTracker;
import com.facebook.buck.util.cache.ProjectFileHashCache;
import com.facebook.buck.util.cache.impl.DefaultFileHashCache;
import com.facebook.buck.util.cache.impl.StackedFileHashCache;
import com.facebook.buck.util.concurrent.CommandThreadFactory;
import com.facebook.buck.util.concurrent.CommonThreadFactoryState;
import com.facebook.buck.util.concurrent.ExecutorPool;
import com.facebook.buck.util.concurrent.MostExecutors;
import com.facebook.buck.util.config.Config;
import com.facebook.buck.util.config.Configs;
import com.facebook.buck.util.config.RawConfig;
import com.facebook.buck.util.environment.Architecture;
import com.facebook.buck.util.environment.BuildEnvironmentDescription;
import com.facebook.buck.util.environment.CommandMode;
import com.facebook.buck.util.environment.DefaultExecutionEnvironment;
import com.facebook.buck.util.environment.EnvVariablesProvider;
import com.facebook.buck.util.environment.ExecutionEnvironment;
import com.facebook.buck.util.environment.NetworkInfo;
import com.facebook.buck.util.environment.Platform;
import com.facebook.buck.util.network.MacIpv6BugWorkaround;
import com.facebook.buck.util.network.RemoteLogBuckConfig;
import com.facebook.buck.util.perf.PerfStatsTracking;
import com.facebook.buck.util.perf.ProcessTracker;
import com.facebook.buck.util.shutdown.NonReentrantSystemExit;
import com.facebook.buck.util.timing.Clock;
import com.facebook.buck.util.timing.DefaultClock;
import com.facebook.buck.util.timing.NanosAdjustedClock;
import com.facebook.buck.util.types.Pair;
import com.facebook.buck.util.versioncontrol.DelegatingVersionControlCmdLineInterface;
import com.facebook.buck.util.versioncontrol.VersionControlBuckConfig;
import com.facebook.buck.util.versioncontrol.VersionControlStatsGenerator;
import com.facebook.buck.versions.InstrumentedVersionedTargetGraphCache;
import com.facebook.buck.versions.VersionedTargetGraphCache;
import com.facebook.buck.worker.WorkerProcessPool;
import com.facebook.nailgun.NGClientDisconnectReason;
import com.facebook.nailgun.NGContext;
import com.facebook.nailgun.NGListeningAddress;
import com.facebook.nailgun.NGServer;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Lists;
import com.google.common.eventbus.EventBus;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.sun.jna.LastErrorException;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;
import java.io.Closeable;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.nio.channels.ClosedByInterruptException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.time.Duration;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.function.Supplier;
import javax.annotation.Nullable;
import org.immutables.value.Value;
import org.kohsuke.args4j.CmdLineException;
import org.pf4j.PluginManager;

public final class Main {

  /**
   * Force JNA to be initialized early to avoid deadlock race condition.
   *
   * <p>
   *
   * <p>See: https://github.com/java-native-access/jna/issues/652
   */
  public static final int JNA_POINTER_SIZE = Pointer.SIZE;

  private static final Optional<String> BUCKD_LAUNCH_TIME_NANOS =
      Optional.ofNullable(System.getProperty("buck.buckd_launch_time_nanos"));
  private static final String BUCK_BUILD_ID_ENV_VAR = "BUCK_BUILD_ID";

  private static final String BUCKD_COLOR_DEFAULT_ENV_VAR = "BUCKD_COLOR_DEFAULT";

  private static final Duration DAEMON_SLAYER_TIMEOUT = Duration.ofDays(1);

  private static final Duration HANG_DETECTOR_TIMEOUT = Duration.ofMinutes(5);

  /** Path to a directory of static content that should be served by the {@link WebServer}. */
  private static final int DISK_IO_STATS_TIMEOUT_SECONDS = 10;

  private static final int EXECUTOR_SERVICES_TIMEOUT_SECONDS = 60;
  private static final int EVENT_BUS_TIMEOUT_SECONDS = 15;
  private static final int COUNTER_AGGREGATOR_SERVICE_TIMEOUT_SECONDS = 20;

  private final InputStream stdIn;
  private final PrintStream stdOut;
  private final PrintStream stdErr;

  private final Architecture architecture;

  private static final Semaphore commandSemaphore = new Semaphore(1);
  private static AtomicReference<ImmutableList<String>> activeCommandArgs = new AtomicReference<>();

  private static volatile Optional<NGContext> commandSemaphoreNgClient = Optional.empty();

  private static final DaemonLifecycleManager daemonLifecycleManager = new DaemonLifecycleManager();

  // Ensure we only have one instance of this, so multiple trash cleaning
  // operations are serialized on one queue.
  private static final AsynchronousDirectoryContentsCleaner TRASH_CLEANER =
      new AsynchronousDirectoryContentsCleaner();

  private final Platform platform;

  private Console console;

  private Optional<NGContext> context;

  // Ignore changes to generated Xcode project files and editors' backup files
  // so we don't dump buckd caches on every command.
  private static final ImmutableSet<PathMatcher> DEFAULT_IGNORE_GLOBS =
      ImmutableSet.of(
          FileExtensionMatcher.of("pbxproj"),
          FileExtensionMatcher.of("xcscheme"),
          FileExtensionMatcher.of("xcworkspacedata"),
          // Various editors' temporary files
          GlobPatternMatcher.of("**/*~"),
          // Emacs
          GlobPatternMatcher.of("**/#*#"),
          GlobPatternMatcher.of("**/.#*"),
          // Vim
          FileExtensionMatcher.of("swo"),
          FileExtensionMatcher.of("swp"),
          FileExtensionMatcher.of("swpx"),
          FileExtensionMatcher.of("un~"),
          FileExtensionMatcher.of("netrhwist"),
          // Eclipse
          ExactPathMatcher.of(".idea"),
          ExactPathMatcher.of(".iml"),
          FileExtensionMatcher.of("pydevproject"),
          ExactPathMatcher.of(".project"),
          ExactPathMatcher.of(".metadata"),
          FileExtensionMatcher.of("tmp"),
          FileExtensionMatcher.of("bak"),
          FileExtensionMatcher.of("nib"),
          ExactPathMatcher.of(".classpath"),
          ExactPathMatcher.of(".settings"),
          ExactPathMatcher.of(".loadpath"),
          ExactPathMatcher.of(".externalToolBuilders"),
          ExactPathMatcher.of(".cproject"),
          ExactPathMatcher.of(".buildpath"),
          // Mac OS temp files
          ExactPathMatcher.of(".DS_Store"),
          ExactPathMatcher.of(".AppleDouble"),
          ExactPathMatcher.of(".LSOverride"),
          ExactPathMatcher.of(".Spotlight-V100"),
          ExactPathMatcher.of(".Trashes"),
          // Windows
          ExactPathMatcher.of("$RECYCLE.BIN"),
          // Sublime
          FileExtensionMatcher.of("sublime-workspace"));

  private static final Logger LOG = Logger.get(Main.class);

  private static boolean isSessionLeader;
  private static PluginManager pluginManager;
  private static BuckModuleManager moduleManager;

  private static final HangMonitor.AutoStartInstance HANG_MONITOR =
      new HangMonitor.AutoStartInstance(
          (input) -> {
            LOG.info(
                "No recent activity, dumping thread stacks (`tr , '\\n'` to decode): %s", input);
          },
          HANG_DETECTOR_TIMEOUT);

  private static final NonReentrantSystemExit NON_REENTRANT_SYSTEM_EXIT =
      new NonReentrantSystemExit();

  public interface KnownRuleTypesFactoryFactory {

    KnownRuleTypesFactory create(
        ProcessExecutor executor,
        PluginManager pluginManager,
        SandboxExecutionStrategyFactory sandboxExecutionStrategyFactory,
        ImmutableList<ConfigurationRuleDescription<?>> knownConfigurationDescriptions);
  }

  private final KnownRuleTypesFactoryFactory knownRuleTypesFactoryFactory;

  private Optional<BuckConfig> parsedRootConfig = Optional.empty();

  static {
    MacIpv6BugWorkaround.apply();
  }

  /**
   * This constructor allows integration tests to add/remove/modify known build rules (aka
   * descriptions).
   */
  @VisibleForTesting
  public Main(
      PrintStream stdOut,
      PrintStream stdErr,
      InputStream stdIn,
      KnownRuleTypesFactoryFactory knownRuleTypesFactoryFactory,
      Optional<NGContext> context) {
    this.stdOut = stdOut;
    this.stdErr = stdErr;
    this.stdIn = stdIn;
    this.knownRuleTypesFactoryFactory = knownRuleTypesFactoryFactory;
    this.architecture = Architecture.detect();
    this.platform = Platform.detect();
    this.context = context;

    // Create default console to start outputting errors immediately, if any
    // console may be overridden with custom console later once we have enough information to
    // construct it
    this.console =
        new Console(
            Verbosity.STANDARD_INFORMATION,
            stdOut,
            stdErr,
            new Ansi(
                AnsiEnvironmentChecking.environmentSupportsAnsiEscapes(
                    platform, getClientEnvironment(context))));
  }

  @VisibleForTesting
  public Main(
      PrintStream stdOut, PrintStream stdErr, InputStream stdIn, Optional<NGContext> context) {
    this(stdOut, stdErr, stdIn, DefaultKnownRuleTypesFactory::new, context);
  }

  /* Define all error handling surrounding main command */
  void runMainThenExit(String[] args, long initTimestamp) {

    ExitCode exitCode = ExitCode.SUCCESS;

    try {
      installUncaughtExceptionHandler(context);

      Path projectRoot = Paths.get(".");
      BuildId buildId = getBuildId(context);

      // Only post an overflow event if Watchman indicates a fresh instance event
      // after our initial query.
      WatchmanWatcher.FreshInstanceAction watchmanFreshInstanceAction =
          daemonLifecycleManager.hasDaemon()
              ? WatchmanWatcher.FreshInstanceAction.POST_OVERFLOW_EVENT
              : WatchmanWatcher.FreshInstanceAction.NONE;

      // Get the client environment, either from this process or from the Nailgun context.
      ImmutableMap<String, String> clientEnvironment = getClientEnvironment(context);

      CommandMode commandMode = CommandMode.RELEASE;

      exitCode =
          runMainWithExitCode(
              buildId,
              projectRoot,
              clientEnvironment,
              commandMode,
              watchmanFreshInstanceAction,
              initTimestamp,
              ImmutableList.copyOf(args));
    } catch (Throwable t) {

      HumanReadableExceptionAugmentor augmentor;
      try {
        augmentor =
            new HumanReadableExceptionAugmentor(
                parsedRootConfig
                    .map(buckConfig -> buckConfig.getView(ErrorHandlingBuckConfig.class))
                    .map(ErrorHandlingBuckConfig::getErrorMessageAugmentations)
                    .orElse(ImmutableMap.of()));
      } catch (HumanReadableException e) {
        console.printErrorText(e.getHumanReadableErrorMessage());
        augmentor = new HumanReadableExceptionAugmentor(ImmutableMap.of());
      }
      ErrorLogger logger =
          new ErrorLogger(
              new LogImpl() {
                @Override
                public void logUserVisible(String message) {
                  console.printFailure(message);
                }

                @Override
                public void logUserVisibleInternalError(String message) {
                  console.printFailure(linesToText("Buck encountered an internal error", message));
                }

                @Override
                public void logVerbose(Throwable e) {
                  String message = "Command failed:";
                  if (e instanceof InterruptedException
                      || e instanceof ClosedByInterruptException) {
                    message = "Command was interrupted:";
                  }
                  LOG.info(e, message);
                }
              },
              augmentor);
      logger.logException(t);
      exitCode = ExceptionHandlerRegistryFactory.create().handleException(t);
    } finally {
      LOG.debug("Done.");
      LogConfig.flushLogs();
      // Exit explicitly so that non-daemon threads (of which we use many) don't
      // keep the VM alive.
      System.exit(exitCode.getCode());
    }
  }

  private void setupLogging(
      CommandMode commandMode, BuckCommand command, ImmutableList<String> args) throws IOException {
    // Setup logging.
    if (commandMode.isLoggingEnabled()) {
      // Reset logging each time we run a command while daemonized.
      // This will cause us to write a new log per command.
      LOG.debug("Rotating log.");
      LogConfig.flushLogs();
      LogConfig.setupLogging(command.getLogConfig());

      if (LOG.isDebugEnabled()) {
        Long gitCommitTimestamp = Long.getLong("buck.git_commit_timestamp");
        String buildDateStr;
        if (gitCommitTimestamp == null) {
          buildDateStr = "(unknown)";
        } else {
          buildDateStr =
              new SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US)
                  .format(new Date(TimeUnit.SECONDS.toMillis(gitCommitTimestamp)));
        }
        String buildRev = System.getProperty("buck.git_commit", "(unknown)");
        LOG.debug("Starting up (build date %s, rev %s), args: %s", buildDateStr, buildRev, args);
        LOG.debug("System properties: %s", System.getProperties());
      }
    }
  }

  private ImmutableMap<CellName, Path> getCellMapping(Path canonicalRootPath) throws IOException {
    return DefaultCellPathResolver.bootstrapPathMapping(
        canonicalRootPath, Configs.createDefaultConfig(canonicalRootPath));
  }

  private Config setupDefaultConfig(ImmutableMap<CellName, Path> cellMapping, BuckCommand command)
      throws IOException {
    Path rootPath = cellMapping.get(CellName.ROOT_CELL_NAME);
    Objects.requireNonNull(rootPath, "Root cell should be implicitly added");
    RawConfig rootCellConfigOverrides;

    try {
      ImmutableMap<Path, RawConfig> overridesByPath =
          command.getConfigOverrides(cellMapping).getOverridesByPath(cellMapping);
      rootCellConfigOverrides =
          Optional.ofNullable(overridesByPath.get(rootPath)).orElse(RawConfig.of());
    } catch (InvalidCellOverrideException exception) {
      rootCellConfigOverrides =
          command.getConfigOverrides(cellMapping).getForCell(CellName.ROOT_CELL_NAME);
    }
    return Configs.createDefaultConfig(Objects.requireNonNull(rootPath), rootCellConfigOverrides);
  }

  private ImmutableSet<Path> getProjectWatchList(
      Path canonicalRootPath, BuckConfig buckConfig, DefaultCellPathResolver cellPathResolver) {
    return ImmutableSet.<Path>builder()
        .add(canonicalRootPath)
        .addAll(
            buckConfig.getView(ParserConfig.class).getWatchCells()
                ? cellPathResolver.getPathMapping().values()
                : ImmutableList.of())
        .build();
  }

  /**
   * @param buildId an identifier for this command execution.
   * @param initTimestamp Value of System.nanoTime() when process got main()/nailMain() invoked.
   * @param unexpandedCommandLineArgs command line arguments
   * @return an ExitCode representing the result of the command
   */
  @SuppressWarnings("PMD.PrematureDeclaration")
  public ExitCode runMainWithExitCode(
      BuildId buildId,
      Path projectRoot,
      ImmutableMap<String, String> clientEnvironment,
      CommandMode commandMode,
      WatchmanWatcher.FreshInstanceAction watchmanFreshInstanceAction,
      long initTimestamp,
      ImmutableList<String> unexpandedCommandLineArgs)
      throws Exception {

    // Set initial exitCode value to FATAL. This will eventually get reassigned unless an exception
    // happens
    ExitCode exitCode = ExitCode.FATAL_GENERIC;

    // Setup filesystem and buck config.
    Path canonicalRootPath = projectRoot.toRealPath().normalize();
    ImmutableMap<CellName, Path> rootCellMapping = getCellMapping(canonicalRootPath);
    ImmutableList<String> args =
        BuckArgsMethods.expandAtFiles(unexpandedCommandLineArgs, rootCellMapping);

    if (moduleManager == null) {
      pluginManager = BuckPluginManagerFactory.createPluginManager();
      moduleManager = new DefaultBuckModuleManager(pluginManager, new BuckModuleJarHashProvider());
    }

    // Parse command line arguments
    BuckCommand command = new BuckCommand();
    command.setPluginManager(pluginManager);
    // Parse the command line args.
    AdditionalOptionsCmdLineParser cmdLineParser =
        new AdditionalOptionsCmdLineParser(pluginManager, command);
    try {
      cmdLineParser.parseArgument(args);
    } catch (CmdLineException e) {
      throw new CommandLineException(e, e.getLocalizedMessage() + "\nFor help see 'buck --help'.");
    }

    // Return help strings fast if the command is a help request.
    Optional<ExitCode> result = command.runHelp(stdOut);
    if (result.isPresent()) {
      return result.get();
    }

    // If this command is not read only, acquire the command semaphore to become the only executing
    // read/write command. Early out will also help to not rotate log on each BUSY status which
    // happens in setupLogging().
    ImmutableList.Builder<String> previousCommandArgsBuilder = new ImmutableList.Builder<>();
    try (CloseableWrapper<Semaphore> semaphore =
        getSemaphoreWrapper(command, unexpandedCommandLineArgs, previousCommandArgsBuilder)) {
      if (!command.isReadOnly() && semaphore == null) {
        // buck_tool will set BUCK_BUSY_DISPLAYED if it already displayed the busy error
        if (!clientEnvironment.containsKey("BUCK_BUSY_DISPLAYED")) {
          String activeCommandLine = "buck " + String.join(" ", previousCommandArgsBuilder.build());
          if (activeCommandLine.length() > 80) {
            activeCommandLine = activeCommandLine.substring(0, 76) + "...";
          }

          System.err.println(
              String.format("Buck Daemon is busy executing '%s'.", activeCommandLine));
          LOG.info(
              "Buck server was busy executing '%s'. Maybe retrying later will help.",
              activeCommandLine);
        }
        return ExitCode.BUSY;
      }

      // statically configure Buck logging environment based on Buck config, usually buck-x.log
      // files
      setupLogging(commandMode, command, args);

      ProjectFilesystemFactory projectFilesystemFactory = new DefaultProjectFilesystemFactory();
      UnconfiguredBuildTargetFactory buildTargetFactory =
          new ParsingUnconfiguredBuildTargetFactory();

      Config config;
      ProjectFilesystem filesystem;
      DefaultCellPathResolver cellPathResolver;
      BuckConfig buckConfig;

      boolean reusePreviousConfig =
          isReuseCurrentConfigPropertySet(command) && daemonLifecycleManager.hasDaemon();
      if (reusePreviousConfig) {
        printWarnMessage(
            String.format(
                "`%s` parameter provided. Reusing previously defined config.",
                GlobalCliOptions.REUSE_CURRENT_CONFIG_ARG));
        buckConfig =
            daemonLifecycleManager
                .getBuckConfig()
                .orElseThrow(
                    () -> new IllegalStateException("Deamon is present but config is missing."));
        config = buckConfig.getConfig();
        filesystem = buckConfig.getFilesystem();
        cellPathResolver = DefaultCellPathResolver.of(filesystem.getRootPath(), config);
      } else {
        config = setupDefaultConfig(rootCellMapping, command);
        filesystem = projectFilesystemFactory.createProjectFilesystem(canonicalRootPath, config);
        cellPathResolver = DefaultCellPathResolver.of(filesystem.getRootPath(), config);
        buckConfig =
            new BuckConfig(
                config,
                filesystem,
                architecture,
                platform,
                clientEnvironment,
                buildTargetName -> buildTargetFactory.create(cellPathResolver, buildTargetName));
      }

      // Set so that we can use some settings when we print out messages to users
      parsedRootConfig = Optional.of(buckConfig);
      CliConfig cliConfig = buckConfig.getView(CliConfig.class);
      // if we are reusing previous configuration then no need to warn about config override
      if (!reusePreviousConfig) {
        warnAboutConfigFileOverrides(filesystem.getRootPath(), cliConfig);
      }

      ImmutableSet<Path> projectWatchList =
          getProjectWatchList(canonicalRootPath, buckConfig, cellPathResolver);

      Verbosity verbosity = VerbosityParser.parse(args);

      // Setup the console.
      console = makeCustomConsole(context, verbosity, buckConfig);

      DistBuildConfig distBuildConfig = new DistBuildConfig(buckConfig);
      boolean isUsingDistributedBuild = false;

      ExecutionEnvironment executionEnvironment =
          new DefaultExecutionEnvironment(clientEnvironment, System.getProperties());

      // Automatically use distributed build for supported repositories and users.
      if (command.subcommand instanceof BuildCommand) {
        BuildCommand subcommand = (BuildCommand) command.subcommand;
        isUsingDistributedBuild = subcommand.isUsingDistributedBuild();
        if (!isUsingDistributedBuild
            && (distBuildConfig.shouldUseDistributedBuild(
                buildId, executionEnvironment.getUsername(), subcommand.getArguments()))) {
          isUsingDistributedBuild = subcommand.tryConvertingToStampede(distBuildConfig);
        }
      }

      // Switch to async file logging, if configured. A few log samples will have already gone
      // via the regular file logger, but that's OK.
      boolean isDistBuildCommand = command.subcommand instanceof DistBuildCommand;
      if (isDistBuildCommand) {
        LogConfig.setUseAsyncFileLogging(distBuildConfig.isAsyncLoggingEnabled());
      }

      RuleKeyConfiguration ruleKeyConfiguration =
          ConfigRuleKeyConfigurationFactory.create(buckConfig, moduleManager);

      String previousBuckCoreKey;
      if (!command.isReadOnly()) {
        Optional<String> currentBuckCoreKey =
            filesystem.readFileIfItExists(filesystem.getBuckPaths().getCurrentVersionFile());
        BuckPaths unconfiguredPaths =
            filesystem.getBuckPaths().withConfiguredBuckOut(filesystem.getBuckPaths().getBuckOut());

        previousBuckCoreKey = currentBuckCoreKey.orElse("<NOT_FOUND>");

        if (!currentBuckCoreKey.isPresent()
            || !currentBuckCoreKey.get().equals(ruleKeyConfiguration.getCoreKey())
            || (filesystem.exists(unconfiguredPaths.getGenDir(), LinkOption.NOFOLLOW_LINKS)
                && (filesystem.isSymLink(unconfiguredPaths.getGenDir())
                    ^ buckConfig.getView(BuildBuckConfig.class).getBuckOutCompatLink()))) {
          // Migrate any version-dependent directories (which might be huge) to a trash directory
          // so we can delete it asynchronously after the command is done.
          moveToTrash(
              filesystem,
              console,
              buildId,
              filesystem.getBuckPaths().getAnnotationDir(),
              filesystem.getBuckPaths().getGenDir(),
              filesystem.getBuckPaths().getScratchDir(),
              filesystem.getBuckPaths().getResDir());
          filesystem.mkdirs(filesystem.getBuckPaths().getCurrentVersionFile().getParent());
          filesystem.writeContentsToPath(
              ruleKeyConfiguration.getCoreKey(), filesystem.getBuckPaths().getCurrentVersionFile());
        }
      } else {
        previousBuckCoreKey = "";
      }

      LOG.verbose("Buck core key from the previous Buck instance: %s", previousBuckCoreKey);

      ProcessExecutor processExecutor = new DefaultProcessExecutor(console);

      SandboxExecutionStrategyFactory sandboxExecutionStrategyFactory =
          new PlatformSandboxExecutionStrategyFactory();

      Clock clock;
      boolean enableThreadCpuTime =
          buckConfig.getBooleanValue("build", "enable_thread_cpu_time", true);
      if (BUCKD_LAUNCH_TIME_NANOS.isPresent()) {
        long nanosEpoch = Long.parseLong(BUCKD_LAUNCH_TIME_NANOS.get(), 10);
        LOG.verbose("Using nanos epoch: %d", nanosEpoch);
        clock = new NanosAdjustedClock(nanosEpoch, enableThreadCpuTime);
      } else {
        clock = new DefaultClock(enableThreadCpuTime);
      }

      ParserConfig parserConfig = buckConfig.getView(ParserConfig.class);
      Watchman watchman =
          buildWatchman(context, parserConfig, projectWatchList, clientEnvironment, console, clock);

      ImmutableList<ConfigurationRuleDescription<?>> knownConfigurationDescriptions =
          PluginBasedKnownConfigurationDescriptionsFactory.createFromPlugins(pluginManager);

      KnownRuleTypesProvider knownRuleTypesProvider =
          new KnownRuleTypesProvider(
              knownRuleTypesFactoryFactory.create(
                  processExecutor,
                  pluginManager,
                  sandboxExecutionStrategyFactory,
                  knownConfigurationDescriptions));

      ExecutableFinder executableFinder = new ExecutableFinder();

      ToolchainProviderFactory toolchainProviderFactory =
          new DefaultToolchainProviderFactory(
              pluginManager, clientEnvironment, processExecutor, executableFinder);

      DefaultCellPathResolver rootCellCellPathResolver =
          DefaultCellPathResolver.of(filesystem.getRootPath(), buckConfig.getConfig());

      Cell rootCell =
          LocalCellProviderFactory.create(
                  filesystem,
                  buckConfig,
                  command.getConfigOverrides(rootCellMapping),
                  rootCellCellPathResolver.getPathMapping(),
                  rootCellCellPathResolver,
                  moduleManager,
                  toolchainProviderFactory,
                  projectFilesystemFactory,
                  buildTargetFactory)
              .getCellByPath(filesystem.getRootPath());

      TargetConfigurationSerializer targetConfigurationSerializer =
          new JsonTargetConfigurationSerializer();

      List<DevspeedTelemetryPlugin> telemetryPlugins;
      if (context.isPresent() && (watchman != WatchmanFactory.NULL_WATCHMAN)) {
        telemetryPlugins = pluginManager.getExtensions(DevspeedTelemetryPlugin.class);
      } else {
        telemetryPlugins = Lists.newArrayList();
      }

      Pair<BuckGlobalState, DaemonStatus> daemonRequest =
          daemonLifecycleManager.getDaemon(
              rootCell,
              knownRuleTypesProvider,
              watchman,
              console,
              clock,
              buildTargetFactory,
              targetConfigurationSerializer,
              telemetryPlugins.isEmpty()
                  ? Optional::empty
                  : () ->
                      telemetryPlugins
                          .get(0)
                          .newBuildListenerFactoryForDaemon(
                              rootCell.getFilesystem(), System.getProperties()),
              context);

      BuckGlobalState buckGlobalState = daemonRequest.getFirst();
      DaemonStatus daemonStatus = daemonRequest.getSecond();

      if (!context.isPresent()) {
        // Clean up the trash on a background thread if this was a
        // non-buckd read-write command. (We don't bother waiting
        // for it to complete; the thread is a daemon thread which
        // will just be terminated at shutdown time.)
        TRASH_CLEANER.startCleaningDirectory(filesystem.getBuckPaths().getTrashDir());
      }

      ImmutableList<BuckEventListener> eventListeners = ImmutableList.of();

      ImmutableList.Builder<ProjectFileHashCache> allCaches = ImmutableList.builder();

      // Build up the hash cache, which is a collection of the stateful cell cache and some
      // per-run caches.
      //
      // TODO(coneko, ruibm, agallagher): Determine whether we can use the existing filesystem
      // object that is in scope instead of creating a new rootCellProjectFilesystem. The primary
      // difference appears to be that filesystem is created with a Config that is used to produce
      // ImmutableSet<PathMatcher> and BuckPaths for the ProjectFilesystem, whereas this one
      // uses the defaults.
      ProjectFilesystem rootCellProjectFilesystem =
          projectFilesystemFactory.createOrThrow(rootCell.getFilesystem().getRootPath());
      BuildBuckConfig buildBuckConfig = rootCell.getBuckConfig().getView(BuildBuckConfig.class);
      allCaches.addAll(buckGlobalState.getFileHashCaches());

      rootCell
          .getAllCells()
          .forEach(
              cell -> {
                if (!cell.equals(rootCell)) {
                  allCaches.add(
                      DefaultFileHashCache.createBuckOutFileHashCache(
                          cell.getFilesystem(), buildBuckConfig.getFileHashCacheMode()));
                }
              });

      // A cache which caches hashes of cell-relative paths which may have been ignore by
      // the main cell cache, and only serves to prevent rehashing the same file multiple
      // times in a single run.
      allCaches.add(
          DefaultFileHashCache.createDefaultFileHashCache(
              rootCellProjectFilesystem, buildBuckConfig.getFileHashCacheMode()));
      allCaches.addAll(
          DefaultFileHashCache.createOsRootDirectoriesCaches(
              projectFilesystemFactory, buildBuckConfig.getFileHashCacheMode()));

      StackedFileHashCache fileHashCache = new StackedFileHashCache(allCaches.build());

      Optional<WebServer> webServer = buckGlobalState.getWebServer();
      ConcurrentMap<String, WorkerProcessPool> persistentWorkerPools =
          buckGlobalState.getPersistentWorkerPools();
      TestBuckConfig testConfig = buckConfig.getView(TestBuckConfig.class);
      ArtifactCacheBuckConfig cacheBuckConfig = new ArtifactCacheBuckConfig(buckConfig);

      SuperConsoleConfig superConsoleConfig = new SuperConsoleConfig(buckConfig);

      // Eventually, we'll want to get allow websocket and/or nailgun clients to specify locale
      // when connecting. For now, we'll use the default from the server environment.
      Locale locale = Locale.getDefault();

      InvocationInfo invocationInfo =
          InvocationInfo.of(
              buildId,
              superConsoleConfig.isEnabled(console.getAnsi(), console.getVerbosity()),
              context.isPresent(),
              command.getSubCommandNameForLogging(),
              args,
              unexpandedCommandLineArgs,
              filesystem.getBuckPaths().getLogDir(),
              isRemoteExecutionBuild(command, buckConfig));

      RemoteExecutionConfig remoteExecutionConfig = buckConfig.getView(RemoteExecutionConfig.class);
      if (isRemoteExecutionBuild(command, buckConfig)) {
        remoteExecutionConfig.validateCertificatesOrThrow();
      }

      Optional<RemoteExecutionEventListener> remoteExecutionListener =
          remoteExecutionConfig.isConsoleEnabled()
              ? Optional.of(new RemoteExecutionEventListener())
              : Optional.empty();
      MetadataProvider metadataProvider =
          MetadataProviderFactory.minimalMetadataProviderForBuild(
              buildId, executionEnvironment.getUsername());

      LogBuckConfig logBuckConfig = buckConfig.getView(LogBuckConfig.class);

      try (TaskManagerScope managerScope = buckGlobalState.getBgTaskManager().getNewScope(buildId);
          GlobalStateManager.LoggerIsMappedToThreadScope loggerThreadMappingScope =
              GlobalStateManager.singleton()
                  .setupLoggers(invocationInfo, console.getStdErr(), stdErr, verbosity);
          DefaultBuckEventBus buildEventBus = new DefaultBuckEventBus(clock, buildId);
          ThrowingCloseableMemoizedSupplier<ManifestService, IOException> manifestServiceSupplier =
              ThrowingCloseableMemoizedSupplier.of(
                  () -> {
                    ManifestServiceConfig manifestServiceConfig =
                        new ManifestServiceConfig(buckConfig);
                    return manifestServiceConfig.createManifestService(
                        clock, buildEventBus, newDirectExecutorService());
                  },
                  ManifestService::close);
          ) {

        CommonThreadFactoryState commonThreadFactoryState =
            GlobalStateManager.singleton().getThreadToCommandRegister();

        try (ThrowingCloseableWrapper<ExecutorService, InterruptedException> diskIoExecutorService =
                getExecutorWrapper(
                    MostExecutors.newSingleThreadExecutor("Disk I/O"),
                    "Disk IO",
                    DISK_IO_STATS_TIMEOUT_SECONDS);
            ThrowingCloseableWrapper<ListeningExecutorService, InterruptedException>
                httpWriteExecutorService =
                    getExecutorWrapper(
                        getHttpWriteExecutorService(cacheBuckConfig, isUsingDistributedBuild),
                        "HTTP Write",
                        cacheBuckConfig.getHttpWriterShutdownTimeout());
            ThrowingCloseableWrapper<ListeningExecutorService, InterruptedException>
                stampedeSyncBuildHttpFetchExecutorService =
                    getExecutorWrapper(
                        getHttpFetchExecutorService(
                            "heavy", cacheBuckConfig.getDownloadHeavyBuildHttpFetchConcurrency()),
                        "Download Heavy Build HTTP Read",
                        cacheBuckConfig.getHttpWriterShutdownTimeout());
            ThrowingCloseableWrapper<ListeningExecutorService, InterruptedException>
                httpFetchExecutorService =
                    getExecutorWrapper(
                        getHttpFetchExecutorService(
                            "standard", cacheBuckConfig.getHttpFetchConcurrency()),
                        "HTTP Read",
                        cacheBuckConfig.getHttpWriterShutdownTimeout());
            ThrowingCloseableWrapper<ScheduledExecutorService, InterruptedException>
                counterAggregatorExecutor =
                    getExecutorWrapper(
                        Executors.newSingleThreadScheduledExecutor(
                            new CommandThreadFactory(
                                "CounterAggregatorThread", commonThreadFactoryState)),
                        "CounterAggregatorExecutor",
                        COUNTER_AGGREGATOR_SERVICE_TIMEOUT_SECONDS);
            ThrowingCloseableWrapper<ScheduledExecutorService, InterruptedException>
                scheduledExecutorPool =
                    getExecutorWrapper(
                        Executors.newScheduledThreadPool(
                            buckConfig
                                .getView(BuildBuckConfig.class)
                                .getNumThreadsForSchedulerPool(),
                            new CommandThreadFactory(
                                getClass().getName() + "SchedulerThreadPool",
                                commonThreadFactoryState)),
                        "ScheduledExecutorService",
                        EXECUTOR_SERVICES_TIMEOUT_SECONDS);
            // Create a cached thread pool for cpu intensive tasks
            ThrowingCloseableWrapper<ListeningExecutorService, InterruptedException>
                cpuExecutorService =
                    getExecutorWrapper(
                        listeningDecorator(Executors.newCachedThreadPool()),
                        ExecutorPool.CPU.toString(),
                        EXECUTOR_SERVICES_TIMEOUT_SECONDS);
            // Create a thread pool for network I/O tasks
            ThrowingCloseableWrapper<ListeningExecutorService, InterruptedException>
                networkExecutorService =
                    getExecutorWrapper(
                        newDirectExecutorService(),
                        ExecutorPool.NETWORK.toString(),
                        EXECUTOR_SERVICES_TIMEOUT_SECONDS);
            ThrowingCloseableWrapper<ListeningExecutorService, InterruptedException>
                projectExecutorService =
                    getExecutorWrapper(
                        listeningDecorator(
                            MostExecutors.newMultiThreadExecutor(
                                "Project",
                                buckConfig.getView(BuildBuckConfig.class).getNumThreads())),
                        ExecutorPool.PROJECT.toString(),
                        EXECUTOR_SERVICES_TIMEOUT_SECONDS);
            BuildInfoStoreManager storeManager = new BuildInfoStoreManager();
            AbstractConsoleEventBusListener consoleListener =
                createConsoleEventListener(
                    clock,
                    superConsoleConfig,
                    console,
                    testConfig.getResultSummaryVerbosity(),
                    executionEnvironment,
                    locale,
                    filesystem.getBuckPaths().getLogDir().resolve("test.log"),
                    buildId,
                    logBuckConfig.isLogBuildIdToConsoleEnabled(),
                    logBuckConfig.getBuildDetailsTemplate(),
                    createAdditionalConsoleLinesProviders(
                        remoteExecutionListener, remoteExecutionConfig, metadataProvider),
                    isRemoteExecutionBuild(command, buckConfig)
                        ? Optional.of(
                            remoteExecutionConfig.getDebugURLString(
                                metadataProvider.get().getReSessionId()))
                        : Optional.empty());
            // This makes calls to LOG.error(...) post to the EventBus, instead of writing to
            // stderr.
            Closeable logErrorToEventBus =
                loggerThreadMappingScope.setWriter(createWriterForConsole(consoleListener));
            Scope ddmLibLogRedirector = DdmLibLogRedirector.redirectDdmLogger(buildEventBus);

            // NOTE: This will only run during the lifetime of the process and will flush on close.
            CounterRegistry counterRegistry =
                new CounterRegistryImpl(
                    counterAggregatorExecutor.get(),
                    buildEventBus,
                    buckConfig
                        .getView(CounterBuckConfig.class)
                        .getCountersFirstFlushIntervalMillis(),
                    buckConfig.getView(CounterBuckConfig.class).getCountersFlushIntervalMillis());
            PerfStatsTracking perfStatsTracking =
                new PerfStatsTracking(buildEventBus, invocationInfo);
            ProcessTracker processTracker =
                logBuckConfig.isProcessTrackerEnabled() && platform != Platform.WINDOWS
                    ? new ProcessTracker(
                        buildEventBus,
                        invocationInfo,
                        context.isPresent(),
                        logBuckConfig.isProcessTrackerDeepEnabled())
                    : null;
            ArtifactCaches artifactCacheFactory =
                new ArtifactCaches(
                    cacheBuckConfig,
                    buildEventBus,
                    target -> buildTargetFactory.create(cellPathResolver, target),
                    targetConfigurationSerializer,
                    filesystem,
                    executionEnvironment.getWifiSsid(),
                    httpWriteExecutorService.get(),
                    httpFetchExecutorService.get(),
                    stampedeSyncBuildHttpFetchExecutorService.get(),
                    getDirCacheStoreExecutor(cacheBuckConfig, diskIoExecutorService),
                    managerScope,
                    getArtifactProducerId(executionEnvironment),
                    executionEnvironment.getHostname(),
                    ClientCertificateHandler.fromConfiguration(cacheBuckConfig));

            // Once command completes it should be safe to not wait for executors and other stateful
            // objects to terminate and release semaphore right away. It will help to retry
            // command faster if user terminated with Ctrl+C.
            // Ideally, we should come up with a better lifecycle management strategy for the
            // semaphore object
            CloseableWrapper<Optional<CloseableWrapper<Semaphore>>> semaphoreCloser =
                CloseableWrapper.of(
                    Optional.ofNullable(semaphore),
                    s -> {
                      if (s.isPresent()) {
                        s.get().close();
                      }
                    });

            // This will get executed first once it gets out of try block and just wait for
            // event bus to dispatch all pending events before we proceed to termination
            // procedures
            CloseableWrapper<BuckEventBus> waitEvents = getWaitEventsWrapper(buildEventBus)) {

          LOG.debug(invocationInfo.toLogLine());

          buildEventBus.register(HANG_MONITOR.getHangMonitor());

          ImmutableMap<ExecutorPool, ListeningExecutorService> executors =
              ImmutableMap.of(
                  ExecutorPool.CPU,
                  cpuExecutorService.get(),
                  ExecutorPool.NETWORK,
                  networkExecutorService.get(),
                  ExecutorPool.PROJECT,
                  projectExecutorService.get());

          // No need to kick off ProgressEstimator for commands that
          // don't build anything -- it has overhead and doesn't seem
          // to work for (e.g.) query anyway. ProgressEstimator has
          // special support for project so we have to include it
          // there too.
          if (consoleListener.displaysEstimatedProgress()
              && (command.performsBuild() || command.subcommand instanceof ProjectCommand)) {
            ProgressEstimator progressEstimator =
                new ProgressEstimator(
                    filesystem
                        .resolve(filesystem.getBuckPaths().getBuckOut())
                        .resolve(ProgressEstimator.PROGRESS_ESTIMATIONS_JSON),
                    buildEventBus);
            consoleListener.setProgressEstimator(progressEstimator);
          }

          BuildEnvironmentDescription buildEnvironmentDescription =
              getBuildEnvironmentDescription(
                  executionEnvironment,
                  buckConfig);

          Iterable<BuckEventListener> commandEventListeners =
              command.getSubcommand().isPresent()
                  ? command
                      .getSubcommand()
                      .get()
                      .getEventListeners(executors, scheduledExecutorPool.get())
                  : ImmutableList.of();

          final boolean isRemoteExecutionBuild = isRemoteExecutionBuild(command, buckConfig);
          if (isRemoteExecutionBuild) {
            List<BuckEventListener> remoteExecutionsListeners = Lists.newArrayList();
            if (remoteExecutionListener.isPresent()) {
              remoteExecutionsListeners.add(remoteExecutionListener.get());
            }


            commandEventListeners =
                new ImmutableList.Builder<BuckEventListener>()
                    .addAll(commandEventListeners)
                    .addAll(remoteExecutionsListeners)
                    .build();
          }

          eventListeners =
              addEventListeners(
                  buckGlobalState.getDevspeedDaemonListener(),
                  buildEventBus,
                  buckGlobalState.getFileEventBus(),
                  rootCell.getFilesystem(),
                  invocationInfo,
                  rootCell.getBuckConfig(),
                  webServer,
                  clock,
                  counterRegistry,
                  commandEventListeners,
                  managerScope);
          consoleListener.register(buildEventBus);

          if (logBuckConfig.isBuckConfigLocalWarningEnabled()
              && !console.getVerbosity().isSilent()) {
            ImmutableList<Path> localConfigFiles =
                rootCell
                    .getAllCells()
                    .stream()
                    .map(
                        cell ->
                            cell.getRoot().resolve(Configs.DEFAULT_BUCK_CONFIG_OVERRIDE_FILE_NAME))
                    .filter(path -> Files.isRegularFile(path))
                    .collect(ImmutableList.toImmutableList());
            if (localConfigFiles.size() > 0) {
              String message =
                  localConfigFiles.size() == 1
                      ? "Using local configuration:"
                      : "Using local configurations:";
              buildEventBus.post(ConsoleEvent.warning(message));
              for (Path localConfigFile : localConfigFiles) {
                buildEventBus.post(ConsoleEvent.warning(String.format("- %s", localConfigFile)));
              }
            }
          }

          if (commandMode == CommandMode.RELEASE && logBuckConfig.isPublicAnnouncementsEnabled()) {
            PublicAnnouncementManager announcementManager =
                new PublicAnnouncementManager(
                    clock,
                    buildEventBus,
                    consoleListener,
                    new RemoteLogBuckConfig(buckConfig),
                    Objects.requireNonNull(executors.get(ExecutorPool.CPU)));
            announcementManager.getAndPostAnnouncements();
          }

          // This needs to be after the registration of the event listener so they can pick it up.
          Optional<String> newDaemonEvent = daemonStatus.newDaemonEvent();
          newDaemonEvent.ifPresent(
              event -> {
                buildEventBus.post(DaemonEvent.newDaemonInstance(event));
              });


          VersionControlBuckConfig vcBuckConfig = new VersionControlBuckConfig(buckConfig);
          VersionControlStatsGenerator vcStatsGenerator =
              new VersionControlStatsGenerator(
                  new DelegatingVersionControlCmdLineInterface(
                      rootCell.getFilesystem().getRootPath(),
                      new PrintStreamProcessExecutorFactory(),
                      vcBuckConfig.getHgCmd(),
                      buckConfig.getEnvironment()),
                  vcBuckConfig.getPregeneratedVersionControlStats());
          if (vcBuckConfig.shouldGenerateStatistics()
              && command.subcommand instanceof AbstractCommand
              && !(command.subcommand instanceof DistBuildCommand)) {
            AbstractCommand subcommand = (AbstractCommand) command.subcommand;
            if (!commandMode.equals(CommandMode.TEST)) {
              vcStatsGenerator.generateStatsAsync(
                  subcommand.isSourceControlStatsGatheringEnabled(),
                  diskIoExecutorService.get(),
                  buildEventBus);
            }
          }
          NetworkInfo.generateActiveNetworkAsync(diskIoExecutorService.get(), buildEventBus);

          ImmutableList<String> remainingArgs =
              args.isEmpty() ? ImmutableList.of() : args.subList(1, args.size());

          CommandEvent.Started startedEvent =
              CommandEvent.started(
                  command.getDeclaredSubCommandName(),
                  remainingArgs,
                  context.isPresent()
                      ? OptionalLong.of(buckGlobalState.getUptime())
                      : OptionalLong.empty(),
                  getBuckPID());
          buildEventBus.post(startedEvent);

          ResourcesConfig resourceConfig = buckConfig.getView(ResourcesConfig.class);

          CloseableMemoizedSupplier<ForkJoinPool> forkJoinPoolSupplier =
              getForkJoinPoolSupplier(resourceConfig);

          TargetSpecResolver targetSpecResolver =
              new TargetSpecResolver(
                  buildEventBus,
                  resourceConfig.getMaximumResourceAmounts().getCpu(),
                  rootCell.getCellProvider(),
                  buckGlobalState.getDirectoryListCaches(),
                  buckGlobalState.getFileTreeCaches());

          ParserAndCaches parserAndCaches =
              getParserAndCaches(
                  context,
                  watchmanFreshInstanceAction,
                  filesystem,
                  buckConfig,
                  watchman,
                  knownRuleTypesProvider,
                  rootCell,
                  command::getTargetPlatforms,
                  buckGlobalState,
                  buildEventBus,
                  forkJoinPoolSupplier,
                  ruleKeyConfiguration,
                  executableFinder,
                  manifestServiceSupplier,
                  fileHashCache,
                  buildTargetFactory,
                  targetSpecResolver);

          // Because the Parser is potentially constructed before the CounterRegistry,
          // we need to manually register its counters after it's created.
          //
          // The counters will be unregistered once the counter registry is closed.
          counterRegistry.registerCounters(
              parserAndCaches.getParser().getPermState().getCounters());

          Optional<ProcessManager> processManager;
          if (platform == Platform.WINDOWS) {
            processManager = Optional.empty();
          } else {
            processManager = Optional.of(new PkillProcessManager(processExecutor));
          }

          // At this point, we have parsed options but haven't started
          // running the command yet.  This is a good opportunity to
          // augment the event bus with our serialize-to-file
          // event-listener.
          if (command.subcommand instanceof AbstractCommand) {
            AbstractCommand subcommand = (AbstractCommand) command.subcommand;
            Optional<Path> eventsOutputPath = subcommand.getEventsOutputPath();
            if (eventsOutputPath.isPresent()) {
              BuckEventListener listener =
                  new FileSerializationEventBusListener(eventsOutputPath.get());
              buildEventBus.register(listener);
            }
          }

          buildEventBus.post(
              new BuckInitializationDurationEvent(
                  TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - initTimestamp)));

          try {
            exitCode =
                command.run(
                    CommandRunnerParams.of(
                        console,
                        stdIn,
                        rootCell,
                        watchman,
                        parserAndCaches.getVersionedTargetGraphCache(),
                        artifactCacheFactory,
                        parserAndCaches.getTypeCoercerFactory(),
                        buildTargetFactory,
                        () -> EmptyTargetConfiguration.INSTANCE,
                        targetConfigurationSerializer,
                        parserAndCaches.getParser(),
                        buildEventBus,
                        platform,
                        clientEnvironment,
                        rootCell
                            .getBuckConfig()
                            .getView(JavaBuckConfig.class)
                            .createDefaultJavaPackageFinder(),
                        clock,
                        vcStatsGenerator,
                        processManager,
                        webServer,
                        persistentWorkerPools,
                        buckConfig,
                        fileHashCache,
                        executors,
                        scheduledExecutorPool.get(),
                        buildEnvironmentDescription,
                        parserAndCaches.getActionGraphProvider(),
                        knownRuleTypesProvider,
                        storeManager,
                        Optional.of(invocationInfo),
                        parserAndCaches.getDefaultRuleKeyFactoryCacheRecycler(),
                        projectFilesystemFactory,
                        ruleKeyConfiguration,
                        processExecutor,
                        executableFinder,
                        pluginManager,
                        moduleManager,
                        forkJoinPoolSupplier,
                        metadataProvider,
                        manifestServiceSupplier));
          } catch (InterruptedException | ClosedByInterruptException e) {
            buildEventBus.post(CommandEvent.interrupted(startedEvent, ExitCode.SIGNAL_INTERRUPT));
            throw e;
          } finally {
            buildEventBus.post(CommandEvent.finished(startedEvent, exitCode));
            buildEventBus.post(
                new CacheStatsEvent(
                    "versioned_target_graph_cache",
                    parserAndCaches.getVersionedTargetGraphCache().getCacheStats()));
          }
        } finally {
          // signal nailgun that we are not interested in client disconnect events anymore
          context.ifPresent(c -> c.removeAllClientListeners());

          if (context.isPresent()) {
            // Clean up the trash in the background if this was a buckd
            // read-write command. (We don't bother waiting for it to
            // complete; the cleaner will ensure subsequent cleans are
            // serialized with this one.)
            TRASH_CLEANER.startCleaningDirectory(filesystem.getBuckPaths().getTrashDir());
          }

          // Exit Nailgun earlier if command succeeded to now block the client while performing
          // telemetry upload in background
          // For failures, always do it synchronously because exitCode in fact may be overridden up
          // the stack
          // TODO(buck_team): refactor this as in case of exception exitCode is reported incorrectly
          // to the CommandEvent listener
          if (exitCode == ExitCode.SUCCESS
              && context.isPresent()
              && !cliConfig.getFlushEventsBeforeExit()) {
            context.get().in.close(); // Avoid client exit triggering client disconnection handling.
            context.get().exit(exitCode.getCode());
          }

          // TODO(buck_team): refactor eventListeners for RAII
          flushAndCloseEventListeners(console, eventListeners);
        }
      }
    }
    return exitCode;
  }

  private boolean isReuseCurrentConfigPropertySet(BuckCommand command) {
    if (command.subcommand instanceof AbstractCommand) {
      AbstractCommand subcommand = (AbstractCommand) command.subcommand;
      return subcommand.isReuseCurrentConfig();
    }
    return false;
  }

  private void warnAboutConfigFileOverrides(Path root, CliConfig cliConfig) throws IOException {
    if (!cliConfig.getWarnOnConfigFileOverrides()) {
      return;
    }

    if (!console.getVerbosity().shouldPrintStandardInformation()) {
      return;
    }

    // Useful for filtering out things like system wide buckconfigs in /etc that might be managed
    // by the system. We don't want to warn users about files that they have not necessarily
    // created.
    ImmutableSet<Path> overridesToIgnore = cliConfig.getWarnOnConfigFileOverridesIgnoredFiles();
    Path mainConfigPath = Configs.getMainConfigurationFile(root);

    ImmutableSortedSet<Path> userSpecifiedOverrides =
        Configs.getDefaultConfigurationFiles(root)
            .stream()
            .filter(
                path ->
                    !overridesToIgnore.contains(path.getFileName()) && !mainConfigPath.equals(path))
            .map(path -> path.startsWith(root) ? root.relativize(path) : path)
            .collect(ImmutableSortedSet.toImmutableSortedSet(Comparator.naturalOrder()));

    if (userSpecifiedOverrides.isEmpty()) {
      return;
    }

    // Use the raw stream because otherwise this will stop superconsole from ever printing again
    printWarnMessage(
        String.format(
            "Using additional configuration options from %s",
            Joiner.on(", ").join(userSpecifiedOverrides)));
  }

  private void printWarnMessage(String message) {
    console.getStdErr().getRawStream().println(console.getAnsi().asWarningText(message));
  }

  private ListeningExecutorService getDirCacheStoreExecutor(
      ArtifactCacheBuckConfig cacheBuckConfig,
      ThrowingCloseableWrapper<ExecutorService, InterruptedException> diskIoExecutorService) {
    Executor dirCacheStoreExecutor = cacheBuckConfig.getDirCacheStoreExecutor();
    switch (dirCacheStoreExecutor) {
      case DISK_IO:
        return listeningDecorator(diskIoExecutorService.get());
      case DIRECT:
        return newDirectExecutorService();
      default:
        throw new IllegalStateException(
            "Executor service for " + dirCacheStoreExecutor + " is not configured.");
    }
  }

  private boolean isRemoteExecutionBuild(BuckCommand command, BuckConfig config) {
    if (!command.getSubcommand().isPresent()
        || !(command.getSubcommand().get() instanceof BuildCommand)) {
      return false;
    }

    ModernBuildRuleStrategyConfig strategyConfig =
        config.getView(ModernBuildRuleConfig.class).getDefaultStrategyConfig();
    while (strategyConfig.getBuildStrategy() == ModernBuildRuleBuildStrategy.HYBRID_LOCAL) {
      strategyConfig = strategyConfig.getHybridLocalConfig().getDelegateConfig();
    }
    return strategyConfig.getBuildStrategy() == ModernBuildRuleBuildStrategy.REMOTE;
  }

  private ImmutableList<AdditionalConsoleLineProvider> createAdditionalConsoleLinesProviders(
      Optional<RemoteExecutionEventListener> remoteExecutionListener,
      RemoteExecutionConfig remoteExecutionConfig,
      MetadataProvider metadataProvider) {
    if (!remoteExecutionListener.isPresent() || !remoteExecutionConfig.isConsoleEnabled()) {
      return ImmutableList.of();
    }

    return ImmutableList.of(
        new RemoteExecutionConsoleLineProvider(
            remoteExecutionListener.get(),
            remoteExecutionConfig.getDebugURLString(metadataProvider.get().getReSessionId())));
  }

  /** Struct for the multiple values returned by {@link #getParserAndCaches}. */
  @Value.Immutable(copy = false, builder = false)
  @BuckStyleTuple
  abstract static class AbstractParserAndCaches {

    public abstract Parser getParser();

    public abstract TypeCoercerFactory getTypeCoercerFactory();

    public abstract InstrumentedVersionedTargetGraphCache getVersionedTargetGraphCache();

    public abstract ActionGraphProvider getActionGraphProvider();

    public abstract Optional<RuleKeyCacheRecycler<RuleKey>> getDefaultRuleKeyFactoryCacheRecycler();
  }

  private static ParserAndCaches getParserAndCaches(
      Optional<NGContext> context,
      FreshInstanceAction watchmanFreshInstanceAction,
      ProjectFilesystem filesystem,
      BuckConfig buckConfig,
      Watchman watchman,
      KnownRuleTypesProvider knownRuleTypesProvider,
      Cell rootCell,
      Supplier<ImmutableList<String>> targetPlatforms,
      BuckGlobalState buckGlobalState,
      BuckEventBus buildEventBus,
      CloseableMemoizedSupplier<ForkJoinPool> forkJoinPoolSupplier,
      RuleKeyConfiguration ruleKeyConfiguration,
      ExecutableFinder executableFinder,
      ThrowingCloseableMemoizedSupplier<ManifestService, IOException> manifestServiceSupplier,
      FileHashCache fileHashCache,
      UnconfiguredBuildTargetFactory unconfiguredBuildTargetFactory,
      TargetSpecResolver targetSpecResolver)
      throws IOException, InterruptedException {
    Optional<WatchmanWatcher> watchmanWatcher = Optional.empty();
    if (watchman.getTransportPath().isPresent()) {
      try {
        watchmanWatcher =
            Optional.of(
                new WatchmanWatcher(
                    watchman,
                    buckGlobalState.getFileEventBus(),
                    ImmutableSet.<PathMatcher>builder()
                        .addAll(filesystem.getIgnorePaths())
                        .addAll(DEFAULT_IGNORE_GLOBS)
                        .build(),
                    buckGlobalState.getWatchmanCursor(),
                    buckConfig.getView(BuildBuckConfig.class).getNumThreads()));
      } catch (WatchmanWatcherException e) {
        buildEventBus.post(
            ConsoleEvent.warning(
                "Watchman threw an exception while parsing file changes.\n%s", e.getMessage()));
      }
    }

    ParserConfig parserConfig = rootCell.getBuckConfig().getView(ParserConfig.class);
    // Create or get Parser and invalidate cached command parameters.
    ParserAndCaches parserAndCaches;
    if (context.isPresent()) {
      // Note that watchmanWatcher is non-null only when daemon.isPresent().
      registerClientDisconnectedListener(context.get(), buckGlobalState);
      if (watchmanWatcher.isPresent()) {
        buckGlobalState.watchFileSystem(
            buildEventBus, watchmanWatcher.get(), watchmanFreshInstanceAction);
      }
      Optional<RuleKeyCacheRecycler<RuleKey>> defaultRuleKeyFactoryCacheRecycler;
      if (buckConfig.getView(BuildBuckConfig.class).getRuleKeyCaching()) {
        LOG.debug("Using rule key calculation caching");
        defaultRuleKeyFactoryCacheRecycler =
            Optional.of(buckGlobalState.getDefaultRuleKeyFactoryCacheRecycler());
      } else {
        defaultRuleKeyFactoryCacheRecycler = Optional.empty();
      }
      TypeCoercerFactory typeCoercerFactory = buckGlobalState.getTypeCoercerFactory();
      Parser parser =
          ParserFactory.create(
              typeCoercerFactory,
              new DefaultConstructorArgMarshaller(typeCoercerFactory),
              knownRuleTypesProvider,
              new ParserPythonInterpreterProvider(parserConfig, executableFinder),
              rootCell.getBuckConfig(),
              buckGlobalState.getDaemonicParserState(),
              targetSpecResolver,
              watchman,
              buildEventBus,
              targetPlatforms,
              manifestServiceSupplier,
              fileHashCache,
              unconfiguredBuildTargetFactory);
      buckGlobalState.getFileEventBus().register(buckGlobalState.getDaemonicParserState());

      parserAndCaches =
          ParserAndCaches.of(
              parser,
              buckGlobalState.getTypeCoercerFactory(),
              new InstrumentedVersionedTargetGraphCache(
                  buckGlobalState.getVersionedTargetGraphCache(),
                  new InstrumentingCacheStatsTracker()),
              new ActionGraphProvider(
                  buildEventBus,
                  ActionGraphFactory.create(
                      buildEventBus, rootCell.getCellProvider(), forkJoinPoolSupplier, buckConfig),
                  buckGlobalState.getActionGraphCache(),
                  ruleKeyConfiguration,
                  buckConfig),
              defaultRuleKeyFactoryCacheRecycler);
    } else {
      TypeCoercerFactory typeCoercerFactory = new DefaultTypeCoercerFactory();
      parserAndCaches =
          ParserAndCaches.of(
              ParserFactory.create(
                  typeCoercerFactory,
                  new DefaultConstructorArgMarshaller(typeCoercerFactory),
                  knownRuleTypesProvider,
                  new ParserPythonInterpreterProvider(parserConfig, executableFinder),
                  rootCell.getBuckConfig(),
                  new DaemonicParserState(parserConfig.getNumParsingThreads()),
                  targetSpecResolver,
                  watchman,
                  buildEventBus,
                  targetPlatforms,
                  manifestServiceSupplier,
                  fileHashCache,
                  unconfiguredBuildTargetFactory),
              typeCoercerFactory,
              new InstrumentedVersionedTargetGraphCache(
                  new VersionedTargetGraphCache(), new InstrumentingCacheStatsTracker()),
              new ActionGraphProvider(
                  buildEventBus,
                  ActionGraphFactory.create(
                      buildEventBus, rootCell.getCellProvider(), forkJoinPoolSupplier, buckConfig),
                  new ActionGraphCache(
                      buckConfig.getView(BuildBuckConfig.class).getMaxActionGraphCacheEntries()),
                  ruleKeyConfiguration,
                  buckConfig),
              /* defaultRuleKeyFactoryCacheRecycler */ Optional.empty());
    }
    return parserAndCaches;
  }

  private static void registerClientDisconnectedListener(
      NGContext context, BuckGlobalState buckGlobalState) {
    Thread mainThread = Thread.currentThread();
    context.addClientListener(
        reason -> {
          LOG.info("Nailgun client disconnected with " + reason);
          if (Main.commandSemaphoreNgClient.orElse(null) == context) {
            // Process no longer wants work done on its behalf.
            LOG.debug("Killing background processes on client disconnect");
            BgProcessKiller.interruptBgProcesses();
          }

          if (reason != NGClientDisconnectReason.SESSION_SHUTDOWN) {
            LOG.debug("Killing all Buck jobs on client disconnect by interrupting the main thread");
            // signal daemon to complete required tasks and interrupt main thread
            // this will hopefully trigger InterruptedException and program shutdown
            buckGlobalState.interruptOnClientExit(mainThread);
          }
        });
  }

  private Console makeCustomConsole(
      Optional<NGContext> context, Verbosity verbosity, BuckConfig buckConfig) {
    Optional<String> color;
    if (context.isPresent() && (context.get().getEnv() != null)) {
      String colorString = context.get().getEnv().getProperty(BUCKD_COLOR_DEFAULT_ENV_VAR);
      color = Optional.ofNullable(colorString);
    } else {
      color = Optional.empty();
    }
    return new Console(
        verbosity, stdOut, stdErr, buckConfig.getView(CliConfig.class).createAnsi(color));
  }

  private void flushAndCloseEventListeners(
      Console console, ImmutableList<BuckEventListener> eventListeners) throws IOException {
    for (BuckEventListener eventListener : eventListeners) {
      try {
        eventListener.close();
      } catch (RuntimeException e) {
        PrintStream stdErr = console.getStdErr();
        stdErr.println("Ignoring non-fatal error!  The stack trace is below:");
        e.printStackTrace(stdErr);
      }
    }
  }

  private static void moveToTrash(
      ProjectFilesystem filesystem, Console console, BuildId buildId, Path... pathsToMove)
      throws IOException {
    Path trashPath = filesystem.getBuckPaths().getTrashDir().resolve(buildId.toString());
    filesystem.mkdirs(trashPath);
    for (Path pathToMove : pathsToMove) {
      try {
        // Technically this might throw AtomicMoveNotSupportedException,
        // but we're moving a path within buck-out, so we don't expect this
        // to throw.
        //
        // If it does throw, we'll complain loudly and synchronously delete
        // the file instead.
        filesystem.move(
            pathToMove,
            trashPath.resolve(pathToMove.getFileName()),
            StandardCopyOption.ATOMIC_MOVE);
      } catch (NoSuchFileException e) {
        LOG.verbose(e, "Ignoring missing path %s", pathToMove);
      } catch (AtomicMoveNotSupportedException e) {
        console
            .getStdErr()
            .format("Atomic moves not supported, falling back to synchronous delete: %s", e);
        MostFiles.deleteRecursivelyIfExists(pathToMove);
      }
    }
  }

  private static final Watchman buildWatchman(
      Optional<NGContext> context,
      ParserConfig parserConfig,
      ImmutableSet<Path> projectWatchList,
      ImmutableMap<String, String> clientEnvironment,
      Console console,
      Clock clock)
      throws InterruptedException {
    Watchman watchman;
    if (context.isPresent() || parserConfig.getGlobHandler() == ParserConfig.GlobHandler.WATCHMAN) {
      WatchmanFactory watchmanFactory = new WatchmanFactory();
      watchman =
          watchmanFactory.build(
              projectWatchList,
              clientEnvironment,
              console,
              clock,
              parserConfig.getWatchmanQueryTimeoutMs());

      LOG.debug(
          "Watchman capabilities: %s Project watches: %s Glob handler config: %s "
              + "Query timeout ms config: %s",
          watchman.getCapabilities(),
          watchman.getProjectWatches(),
          parserConfig.getGlobHandler(),
          parserConfig.getWatchmanQueryTimeoutMs());

    } else {
      watchman = WatchmanFactory.NULL_WATCHMAN;
      LOG.debug(
          "Not using Watchman, context present: %s, glob handler: %s",
          context.isPresent(), parserConfig.getGlobHandler());
    }
    return watchman;
  }

  /**
   * RAII wrapper which does not really close any object but waits for all events in given event bus
   * to complete. We want to have it this way to safely start deinitializing event listeners
   */
  private static CloseableWrapper<BuckEventBus> getWaitEventsWrapper(BuckEventBus buildEventBus) {
    return CloseableWrapper.of(
        buildEventBus,
        eventBus -> {
          // wait for event bus to process all pending events
          if (!eventBus.waitEvents(EVENT_BUS_TIMEOUT_SECONDS * 1000)) {
            LOG.warn(
                "Event bus did not complete all events within timeout; event listener's data"
                    + "may be incorrect");
          }
        });
  }

  private static <T extends ExecutorService>
      ThrowingCloseableWrapper<T, InterruptedException> getExecutorWrapper(
          T executor, String executorName, long closeTimeoutSeconds) {
    return ThrowingCloseableWrapper.of(
        executor,
        service -> {
          executor.shutdown();
          LOG.info(
              "Awaiting termination of %s executor service. Waiting for all jobs to complete, "
                  + "or up to maximum of %s seconds...",
              executorName, closeTimeoutSeconds);
          executor.awaitTermination(closeTimeoutSeconds, TimeUnit.SECONDS);
          if (!executor.isTerminated()) {
            LOG.warn(
                "%s executor service is still running after shutdown request and "
                    + "%s second timeout. Shutting down forcefully..",
                executorName, closeTimeoutSeconds);
            executor.shutdownNow();
          } else {
            LOG.info("Successfully terminated %s executor service.", executorName);
          }
        });
  }

  private static ListeningExecutorService getHttpWriteExecutorService(
      ArtifactCacheBuckConfig buckConfig, boolean isUsingDistributedBuild) {
    if (isUsingDistributedBuild || buckConfig.hasAtLeastOneWriteableRemoteCache()) {
      // Distributed builds need to upload from the local cache to the remote cache.
      ExecutorService executorService =
          MostExecutors.newMultiThreadExecutor(
              "HTTP Write", buckConfig.getHttpMaxConcurrentWrites());
      return listeningDecorator(executorService);
    } else {
      return newDirectExecutorService();
    }
  }

  private static ListeningExecutorService getHttpFetchExecutorService(
      String prefix, int fetchConcurrency) {
    return listeningDecorator(
        MostExecutors.newMultiThreadExecutor(
            new ThreadFactoryBuilder().setNameFormat(prefix + "-cache-fetch-%d").build(),
            fetchConcurrency));
  }

  private static ConsoleHandlerState.Writer createWriterForConsole(
      AbstractConsoleEventBusListener console) {
    return new ConsoleHandlerState.Writer() {
      @Override
      public void write(String line) {
        console.printSevereWarningDirectly(line);
      }

      @Override
      public void flush() {
        // Intentional no-op.
      }

      @Override
      public void close() {
        // Intentional no-op.
      }
    };
  }

  /**
   * @return the client environment, which is either the process environment or the environment sent
   *     to the daemon by the Nailgun client. This method should always be used in preference to
   *     System.getenv() and should be the only call to System.getenv() within the Buck codebase to
   *     ensure that the use of the Buck daemon is transparent. This also scrubs NG environment
   *     variables if no context is actually present.
   */
  @SuppressWarnings({"unchecked", "rawtypes"}) // Safe as Property is a Map<String, String>.
  private static ImmutableMap<String, String> getClientEnvironment(Optional<NGContext> context) {
    if (context.isPresent()) {
      return ImmutableMap.<String, String>copyOf((Map) context.get().getEnv());
    } else {
      ImmutableMap<String, String> systemEnv = EnvVariablesProvider.getSystemEnv();
      ImmutableMap.Builder<String, String> builder =
          ImmutableMap.builderWithExpectedSize(systemEnv.size());

      systemEnv
          .entrySet()
          .stream()
          .filter(
              e ->
                  !NAILGUN_STDOUT_ISTTY_ENV.equals(e.getKey())
                      && !NAILGUN_STDERR_ISTTY_ENV.equals(e.getKey()))
          .forEach(builder::put);
      return builder.build();
    }
  }

  /**
   * Try to acquire global semaphore if needed to do so. Attach closer to acquired semaphore in a
   * form of a wrapper object so it can be used with try-with-resources.
   *
   * @return (semaphore, previous args) If we successfully acquire the semaphore, return (semaphore,
   *     null). If there is already a command running but the command to run is readonly, return
   *     (null, null) and allow the execution. Otherwise, return (null, previously running command
   *     args) and block this command.
   */
  private @Nullable CloseableWrapper<Semaphore> getSemaphoreWrapper(
      BuckCommand command,
      ImmutableList<String> currentArgs,
      ImmutableList.Builder<String> previousArgs) {
    // we can execute read-only commands (query, targets, etc) in parallel
    if (command.isReadOnly()) {
      // using nullable instead of Optional<> to use the object with try-with-resources
      return null;
    }

    while (!commandSemaphore.tryAcquire()) {
      ImmutableList<String> activeCommandArgsCopy = activeCommandArgs.get();
      if (activeCommandArgsCopy != null) {
        // Keep retrying until we either 1) successfully acquire the semaphore or 2) failed to
        // acquire the semaphore and obtain a valid list of args for on going command.
        // In theory, this can stuck in a loop if it never observes such state if other commands
        // winning the race, but I consider this to be a rare corner case.
        previousArgs.addAll(activeCommandArgsCopy);
        return null;
      }

      // Avoid hogging CPU
      Thread.yield();
    }

    commandSemaphoreNgClient = context;

    // Keep track of command that is in progress
    activeCommandArgs.set(currentArgs);

    CloseableWrapper<Semaphore> semaphore =
        CloseableWrapper.of(
            commandSemaphore,
            commandSemaphore -> {
              activeCommandArgs.set(null);
              commandSemaphoreNgClient = Optional.empty();
              // TODO(buck_team): have background process killer have its own lifetime management
              BgProcessKiller.disarm();
              commandSemaphore.release();
            });
    return semaphore;
  }


  @SuppressWarnings("PMD.PrematureDeclaration")
  private ImmutableList<BuckEventListener> addEventListeners(
      Optional<BuckEventListener> devspeedDaemonEventListener,
      BuckEventBus buckEventBus,
      EventBus fileEventBus,
      ProjectFilesystem projectFilesystem,
      InvocationInfo invocationInfo,
      BuckConfig buckConfig,
      Optional<WebServer> webServer,
      Clock clock,
      CounterRegistry counterRegistry,
      Iterable<BuckEventListener> commandSpecificEventListeners,
      TaskManagerScope managerScope) {
    ImmutableList.Builder<BuckEventListener> eventListenersBuilder =
        ImmutableList.<BuckEventListener>builder().add(new LoggingBuildListener());

    LogBuckConfig logBuckConfig = buckConfig.getView(LogBuckConfig.class);
    if (logBuckConfig.isJavaUtilsLoggingEnabled()) {
      eventListenersBuilder.add(new JavaUtilsLoggingBuildListener(projectFilesystem));
    }

    ChromeTraceBuckConfig chromeTraceConfig = buckConfig.getView(ChromeTraceBuckConfig.class);
    if (chromeTraceConfig.isChromeTraceCreationEnabled()) {
      try {
        ChromeTraceBuildListener chromeTraceBuildListener =
            new ChromeTraceBuildListener(
                projectFilesystem, invocationInfo, clock, chromeTraceConfig, managerScope);
        eventListenersBuilder.add(chromeTraceBuildListener);
        fileEventBus.register(chromeTraceBuildListener);
      } catch (IOException e) {
        LOG.error("Unable to create ChromeTrace listener!");
      }
    } else {
      LOG.info("::: ChromeTrace listener disabled");
    }
    if (webServer.isPresent()) {
      eventListenersBuilder.add(webServer.get().createListener());
    }

    ArtifactCacheBuckConfig artifactCacheConfig = new ArtifactCacheBuckConfig(buckConfig);

    devspeedDaemonEventListener.ifPresent(eventListenersBuilder::add);


    CommonThreadFactoryState commonThreadFactoryState =
        GlobalStateManager.singleton().getThreadToCommandRegister();

    eventListenersBuilder.add(
        new LogUploaderListener(
            chromeTraceConfig,
            invocationInfo.getLogFilePath(),
            invocationInfo.getLogDirectoryPath(),
            invocationInfo.getBuildId(),
            managerScope));
    if (logBuckConfig.isRuleKeyLoggerEnabled()) {
      eventListenersBuilder.add(
          new RuleKeyLoggerListener(
              projectFilesystem,
              invocationInfo,
              MostExecutors.newSingleThreadExecutor(
                  new CommandThreadFactory(getClass().getName(), commonThreadFactoryState)),
              managerScope));
    }

    eventListenersBuilder.add(
        new RuleKeyDiagnosticsListener(
            projectFilesystem,
            invocationInfo,
            MostExecutors.newSingleThreadExecutor(
                new CommandThreadFactory(getClass().getName(), commonThreadFactoryState)),
            managerScope));

    if (logBuckConfig.isMachineReadableLoggerEnabled()) {
      try {
        eventListenersBuilder.add(
            new MachineReadableLoggerListener(
                invocationInfo,
                projectFilesystem,
                MostExecutors.newSingleThreadExecutor(
                    new CommandThreadFactory(getClass().getName(), commonThreadFactoryState)),
                artifactCacheConfig.getArtifactCacheModes(),
                managerScope));
      } catch (FileNotFoundException e) {
        LOG.warn("Unable to open stream for machine readable log file.");
      }
    }

    eventListenersBuilder.add(new ParserProfilerLoggerListener(invocationInfo, projectFilesystem));

    eventListenersBuilder.add(new LoadBalancerEventsListener(counterRegistry));
    eventListenersBuilder.add(new CacheRateStatsListener(buckEventBus));
    eventListenersBuilder.add(new WatchmanDiagnosticEventListener(buckEventBus));
    eventListenersBuilder.addAll(commandSpecificEventListeners);

    ImmutableList<BuckEventListener> eventListeners = eventListenersBuilder.build();
    eventListeners.forEach(buckEventBus::register);

    return eventListeners;
  }

  private BuildEnvironmentDescription getBuildEnvironmentDescription(
      ExecutionEnvironment executionEnvironment,
      BuckConfig buckConfig) {
    ImmutableMap.Builder<String, String> environmentExtraData = ImmutableMap.builder();

    return BuildEnvironmentDescription.of(
        executionEnvironment,
        new ArtifactCacheBuckConfig(buckConfig).getArtifactCacheModesRaw(),
        environmentExtraData.build());
  }

  private AbstractConsoleEventBusListener createConsoleEventListener(
      Clock clock,
      SuperConsoleConfig config,
      Console console,
      TestResultSummaryVerbosity testResultSummaryVerbosity,
      ExecutionEnvironment executionEnvironment,
      Locale locale,
      Path testLogPath,
      BuildId buildId,
      boolean printBuildId,
      Optional<String> buildDetailsTemplate,
      ImmutableList<AdditionalConsoleLineProvider> additionalConsoleLineProviders,
      Optional<String> reSessionIDInfo) {
    RenderingConsole renderingConsole = new RenderingConsole(clock, console);
    if (config.isEnabled(console.getAnsi(), console.getVerbosity())) {
      SuperConsoleEventBusListener superConsole =
          new SuperConsoleEventBusListener(
              config,
              renderingConsole,
              clock,
              testResultSummaryVerbosity,
              executionEnvironment,
              locale,
              testLogPath,
              TimeZone.getDefault(),
              buildId,
              printBuildId,
              buildDetailsTemplate,
              additionalConsoleLineProviders);
      return superConsole;
    }
    if (renderingConsole.getVerbosity().isSilent()) {
      return new SilentConsoleEventBusListener(
          renderingConsole, clock, locale, executionEnvironment);
    }
    return new SimpleConsoleEventBusListener(
        renderingConsole,
        clock,
        testResultSummaryVerbosity,
        config.getHideSucceededRulesInLogMode(),
        config.getNumberOfSlowRulesToShow(),
        config.shouldShowSlowRulesInConsole(),
        locale,
        testLogPath,
        executionEnvironment,
        buildId,
        printBuildId,
        buildDetailsTemplate,
        reSessionIDInfo,
        additionalConsoleLineProviders);
  }

  /**
   * A helper method to retrieve the process ID of Buck. The return value from the JVM has to match
   * the following pattern: {PID}@{Hostname}. It it does not match the return value is 0.
   *
   * @return the PID or 0L.
   */
  private static long getBuckPID() {
    String pid = ManagementFactory.getRuntimeMXBean().getName();
    return (pid != null && pid.matches("^\\d+@.*$")) ? Long.parseLong(pid.split("@")[0]) : 0L;
  }

  private static BuildId getBuildId(Optional<NGContext> context) {
    String specifiedBuildId;
    if (context.isPresent()) {
      specifiedBuildId = context.get().getEnv().getProperty(BUCK_BUILD_ID_ENV_VAR);
    } else {
      specifiedBuildId = EnvVariablesProvider.getSystemEnv().get(BUCK_BUILD_ID_ENV_VAR);
    }
    if (specifiedBuildId == null) {
      specifiedBuildId = UUID.randomUUID().toString();
    }
    return new BuildId(specifiedBuildId);
  }

  private static String getArtifactProducerId(ExecutionEnvironment executionEnvironment) {
    String artifactProducerId = "user://" + executionEnvironment.getUsername();
    return artifactProducerId;
  }

  /**
   * @param config the configuration for resources
   * @return a memoized supplier for a ForkJoinPool that will be closed properly if initialized
   */
  @VisibleForTesting
  static CloseableMemoizedSupplier<ForkJoinPool> getForkJoinPoolSupplier(BuckConfig config) {
    return getForkJoinPoolSupplier(config.getView(ResourcesConfig.class));
  }

  private static CloseableMemoizedSupplier<ForkJoinPool> getForkJoinPoolSupplier(
      ResourcesConfig config) {
    return CloseableMemoizedSupplier.of(
        () ->
            MostExecutors.forkJoinPoolWithThreadLimit(
                config.getMaximumResourceAmounts().getCpu(), 16),
        ForkJoinPool::shutdownNow);
  }

  private static void installUncaughtExceptionHandler(Optional<NGContext> context) {
    // Override the default uncaught exception handler for background threads to log
    // to java.util.logging then exit the JVM with an error code.
    //
    // (We do this because the default is to just print to stderr and not exit the JVM,
    // which is not safe in a multithreaded environment if the thread held a lock or
    // resource which other threads need.)
    Thread.setDefaultUncaughtExceptionHandler(
        (t, e) -> {
          ExitCode exitCode = ExitCode.FATAL_GENERIC;
          if (e instanceof OutOfMemoryError) {
            exitCode = ExitCode.FATAL_OOM;
          } else if (e instanceof IOException) {
            exitCode =
                e.getMessage().startsWith("No space left on device")
                    ? ExitCode.FATAL_DISK_FULL
                    : ExitCode.FATAL_IO;
          }

          // Do not log anything in case we do not have space on the disk
          if (exitCode != ExitCode.FATAL_DISK_FULL) {
            LOG.error(e, "Uncaught exception from thread %s", t);
          }

          if (context.isPresent()) {
            // Shut down the Nailgun server and make sure it stops trapping System.exit().
            context.get().getNGServer().shutdown();
          }

          NON_REENTRANT_SYSTEM_EXIT.shutdownSoon(exitCode.getCode());
        });
  }

  public static void main(String[] args) {
    new Main(System.out, System.err, System.in, Optional.empty())
        .runMainThenExit(args, System.nanoTime());
  }

  private static void markFdCloseOnExec(int fd) {
    int fdFlags;
    fdFlags = Libc.INSTANCE.fcntl(fd, Libc.Constants.rFGETFD);
    if (fdFlags == -1) {
      throw new LastErrorException(Native.getLastError());
    }
    fdFlags |= Libc.Constants.rFDCLOEXEC;
    if (Libc.INSTANCE.fcntl(fd, Libc.Constants.rFSETFD, fdFlags) == -1) {
      throw new LastErrorException(Native.getLastError());
    }
  }

  private static void daemonizeIfPossible() {
    String osName = System.getProperty("os.name");
    Libc.OpenPtyLibrary openPtyLibrary;
    Platform platform = Platform.detect();
    if (platform == Platform.LINUX) {
      Libc.Constants.rTIOCSCTTY = Libc.Constants.LINUX_TIOCSCTTY;
      Libc.Constants.rFDCLOEXEC = Libc.Constants.LINUX_FD_CLOEXEC;
      Libc.Constants.rFGETFD = Libc.Constants.LINUX_F_GETFD;
      Libc.Constants.rFSETFD = Libc.Constants.LINUX_F_SETFD;
      openPtyLibrary = Native.loadLibrary("libutil", Libc.OpenPtyLibrary.class);
    } else if (platform == Platform.MACOS) {
      Libc.Constants.rTIOCSCTTY = Libc.Constants.DARWIN_TIOCSCTTY;
      Libc.Constants.rFDCLOEXEC = Libc.Constants.DARWIN_FD_CLOEXEC;
      Libc.Constants.rFGETFD = Libc.Constants.DARWIN_F_GETFD;
      Libc.Constants.rFSETFD = Libc.Constants.DARWIN_F_SETFD;
      openPtyLibrary =
          Native.loadLibrary(com.sun.jna.Platform.C_LIBRARY_NAME, Libc.OpenPtyLibrary.class);
    } else {
      LOG.info("not enabling process killing on nailgun exit: unknown OS %s", osName);
      return;
    }

    // Making ourselves a session leader with setsid disconnects us from our controlling terminal
    int ret = Libc.INSTANCE.setsid();
    if (ret < 0) {
      LOG.warn("cannot enable background process killing: %s", Native.getLastError());
      return;
    }

    LOG.info("enabling background process killing for buckd");

    IntByReference master = new IntByReference();
    IntByReference slave = new IntByReference();

    if (openPtyLibrary.openpty(master, slave, Pointer.NULL, Pointer.NULL, Pointer.NULL) != 0) {
      throw new RuntimeException("Failed to open pty");
    }

    // Deliberately leak the file descriptors for the lifetime of this process; NuProcess can
    // sometimes leak file descriptors to children, so make sure these FDs are marked close-on-exec.
    markFdCloseOnExec(master.getValue());
    markFdCloseOnExec(slave.getValue());

    // Make the pty our controlling terminal; works because we disconnected above with setsid.
    if (Libc.INSTANCE.ioctl(slave.getValue(), Pointer.createConstant(Libc.Constants.rTIOCSCTTY), 0)
        == -1) {
      throw new RuntimeException("Failed to set pty");
    }

    LOG.info("enabled background process killing for buckd");
    isSessionLeader = true;
  }

  public static final class DaemonBootstrap {

    private static final int AFTER_COMMAND_AUTO_GC_DELAY_MS = 5000;
    private static final int SUBSEQUENT_GC_DELAY_MS = 10000;
    private static @Nullable DaemonKillers daemonKillers;
    private static AtomicInteger activeTasks = new AtomicInteger(0);

    /** Single thread for running short-lived tasks outside the command context. */
    private static final ScheduledExecutorService housekeepingExecutorService =
        Executors.newSingleThreadScheduledExecutor();

    private static final boolean isCMS =
        ManagementFactory.getGarbageCollectorMXBeans()
            .stream()
            .filter(GarbageCollectorMXBean::isValid)
            .map(GarbageCollectorMXBean::getName)
            .anyMatch(Predicate.isEqual("ConcurrentMarkSweep"));

    public static void main(String[] args) {
      try {
        daemonizeIfPossible();
        if (isSessionLeader) {
          BgProcessKiller.init();
          LOG.info("initialized bg session killer");
        }
      } catch (Throwable ex) {
        System.err.println(String.format("buckd: fatal error %s", ex));
        System.exit(1);
      }

      if (args.length != 2) {
        System.err.println("Usage: buckd socketpath heartbeatTimeout");
        return;
      }

      String socketPath = args[0];
      int heartbeatTimeout = Integer.parseInt(args[1]);
      // Strip out optional local: prefix.  This server only use domain sockets.
      if (socketPath.startsWith("local:")) {
        socketPath = socketPath.substring("local:".length());
      }
      SecurityManager securityManager = System.getSecurityManager();
      NGServer server =
          new NGServer(
              new NGListeningAddress(socketPath),
              1, // store only 1 NGSession in a pool to avoid excessive memory usage
              heartbeatTimeout);
      daemonKillers = new DaemonKillers(housekeepingExecutorService, server, Paths.get(socketPath));
      try {
        server.run();
      } catch (RuntimeException e) {
        // server.run() might throw (for example, if this process loses the race with another
        // process to become the daemon for a given Buck root). Letting the exception go would
        // kill this thread, but other non-daemon threads have already been started and we haven't
        // yet installed the unhandled exception handler that would call System.exit, so this
        // process would live forever as a zombie. We catch the exception, re-instate the original
        // security manager (because NailGun has replaced it with one that blocks System.exit, and
        // doesn't restore the original if an exception occurs), and exit.
        System.setSecurityManager(securityManager);
        LOG.error(e, "Exception thrown in NailGun server.");
      }
      System.exit(0);
    }

    static DaemonKillers getDaemonKillers() {
      return Objects.requireNonNull(daemonKillers, "Daemon killers should be initialized.");
    }

    static void commandStarted() {
      activeTasks.incrementAndGet();
    }

    static void commandFinished() {
      // Concurrent Mark and Sweep (CMS) garbage collector releases memory to operating system
      // in multiple steps, even given that full collection is performed at each step. So if CMS
      // collector is used we call System.gc() up to 4 times with some interval, and call it
      // just once for any other major collector.
      // With Java 9 we could just use -XX:-ShrinkHeapInSteps flag.
      int nTimes = isCMS ? 4 : 1;

      housekeepingExecutorService.schedule(
          () -> collectGarbage(nTimes), AFTER_COMMAND_AUTO_GC_DELAY_MS, TimeUnit.MILLISECONDS);
    }

    private static void collectGarbage(int nTimes) {
      int tasks = activeTasks.decrementAndGet();
      if (tasks > 0) {
        return;
      }
      // Potentially there is a race condition - new command comes exactly at this point and got
      // under GC right away. Unlucky. We ignore that.
      System.gc();

      // schedule next collection to release more memory to operating system if garbage collector
      // releases it in steps
      if (nTimes > 1) {
        activeTasks.incrementAndGet();
        housekeepingExecutorService.schedule(
            () -> collectGarbage(nTimes - 1), SUBSEQUENT_GC_DELAY_MS, TimeUnit.MILLISECONDS);
      }
    }
  }

  static class DaemonKillers {

    private final NGServer server;
    private final IdleKiller idleKiller;
    private final @Nullable SocketLossKiller unixDomainSocketLossKiller;

    DaemonKillers(ScheduledExecutorService executorService, NGServer server, Path socketPath) {
      this.server = server;
      this.idleKiller = new IdleKiller(executorService, DAEMON_SLAYER_TIMEOUT, this::killServer);
      this.unixDomainSocketLossKiller =
          Platform.detect() == Platform.WINDOWS
              ? null
              : new SocketLossKiller(
                  executorService, socketPath.toAbsolutePath(), this::killServer);
    }

    IdleKiller.CommandExecutionScope newCommandExecutionScope() {
      if (unixDomainSocketLossKiller != null) {
        unixDomainSocketLossKiller.arm(); // Arm the socket loss killer also.
      }
      return idleKiller.newCommandExecutionScope();
    }

    private void killServer() {
      server.shutdown();
    }
  }

  /** Used to clean up the daemon after running integration tests that exercise it. */
  @VisibleForTesting
  static void resetDaemon() {
    daemonLifecycleManager.resetDaemon();
  }
}
