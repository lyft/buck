/*
 * Copyright 2014-present Facebook, Inc.
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

package com.facebook.buck.android;

import com.facebook.buck.android.packageable.AndroidPackageableCollector;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.attr.HasRuntimeDeps;
import com.facebook.buck.core.sourcepath.ExplicitBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.jvm.core.JavaAbis;
import com.facebook.buck.jvm.java.CompileToJarStepFactory;
import com.facebook.buck.jvm.java.ExtraClasspathProvider;
import com.facebook.buck.jvm.java.JarBuildStepsFactory;
import com.facebook.buck.jvm.java.JavaBuckConfig.UnusedDependenciesAction;
import com.facebook.buck.jvm.java.Javac;
import com.facebook.buck.jvm.java.JavacOptions;
import com.facebook.buck.jvm.java.JavacToJarStepFactory;
import com.facebook.buck.jvm.java.PrebuiltJar;
import com.facebook.buck.jvm.java.RemoveClassesPatternsMatcher;
import com.facebook.buck.jvm.java.ResourcesParameters;
import com.facebook.buck.jvm.java.abi.AbiGenerationMode;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.util.Collections;
import java.util.Optional;
import java.util.stream.Stream;

public class AndroidPrebuiltAar extends AndroidLibrary
    implements HasAndroidResourceDeps, HasRuntimeDeps {

  private final UnzipAar unzipAar;
  private final SourcePath nativeLibsDirectory;
  private final PrebuiltJar prebuiltJar;

  // TODO(cjhopman): It's silly that this is pretending to be a java library.
  public AndroidPrebuiltAar(
      BuildTarget androidLibraryBuildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams androidLibraryParams,
      SourcePathRuleFinder ruleFinder,
      SourcePath proguardConfig,
      SourcePath nativeLibsDirectory,
      PrebuiltJar prebuiltJar,
      UnzipAar unzipAar,
      CompileToJarStepFactory configuredCompiler,
      Iterable<PrebuiltJar> exportedDeps,
      boolean requiredForSourceAbi,
      Optional<String> mavenCoords,

      ActionGraphBuilder graphBuilder,
      Javac javac,
      JavacOptions javacOptions,
      boolean forceFinalResourceIds,
      Optional<String> resourceUnionPackage,
      Optional<String> finalRName,
      boolean useOldStyleableFormat,
      boolean skipNonUnionRDotJava,
      boolean mergeRClasses) {
    super(
        androidLibraryBuildTarget,
        projectFilesystem,
        new JarBuildStepsFactory(
            androidLibraryBuildTarget,
            configuredCompiler,
            /* srcs */ ImmutableSortedSet.of(),
            ImmutableSortedSet.of(),
            ResourcesParameters.of(),
            /* manifestFile */ Optional.empty(), // Manifest means something else for Android rules
            /* postprocessClassesCommands */ ImmutableList.of(),
            /* trackClassUsage */ false,
            /* trackJavacPhaseEvents */ false,
            RemoveClassesPatternsMatcher.EMPTY,
            AbiGenerationMode.CLASS,
            AbiGenerationMode.CLASS,
            ImmutableList.of(),
            requiredForSourceAbi),
        ruleFinder,
        Optional.of(proguardConfig),
        /* firstOrderPackageableDeps */ androidLibraryParams.getDeclaredDeps().get(),
        /* exportedDeps */ ImmutableSortedSet.<BuildRule>naturalOrder()
            .add(prebuiltJar)
            .addAll(exportedDeps)
            .build(),
        /* providedDeps */ ImmutableSortedSet.of(),
        ImmutableSortedSet.of(),
        JavaAbis.getClassAbiJar(androidLibraryBuildTarget),
        /* sourceOnlyAbiJar */ null,
        mavenCoords,
        Optional.of(
            ExplicitBuildTargetSourcePath.of(
                unzipAar.getBuildTarget(), unzipAar.getAndroidManifest())),
        /* tests */ ImmutableSortedSet.of(),
        /* requiredForSourceAbi */ requiredForSourceAbi,
        UnusedDependenciesAction.IGNORE,
        Optional.empty(),
        null,
        false,
        false);
    this.unzipAar = unzipAar;
    this.prebuiltJar = prebuiltJar;
    this.nativeLibsDirectory = nativeLibsDirectory;

    maybeAddDummyRDotJavaFlavor(
        androidLibraryBuildTarget,
        graphBuilder,
        javac,
        javacOptions,
        forceFinalResourceIds,
        resourceUnionPackage,
        finalRName,
        useOldStyleableFormat,
        projectFilesystem,
        skipNonUnionRDotJava,
        mergeRClasses
    );
  }

  @Override
  public String getRDotJavaPackage() {
    return unzipAar.getRDotJavaPackage();
  }

  @Override
  public SourcePath getPathToTextSymbolsFile() {
    return ExplicitBuildTargetSourcePath.of(
        unzipAar.getBuildTarget(), unzipAar.getTextSymbolsFile());
  }

  @Override
  public boolean hasDummyRDotJava() {
    // Assume true, otherwise generates empty R class.
    return true;
  }

  @Override
  public SourcePath getPathToRDotJavaPackageFile() {
    return ExplicitBuildTargetSourcePath.of(
        unzipAar.getBuildTarget(), unzipAar.getPathToRDotJavaPackageFile());
  }

  @Override
  public SourcePath getRes() {
    return unzipAar.getResDirectory();
  }

  @Override
  public SourcePath getAssets() {
    return unzipAar.getAssetsDirectory();
  }

  @Override
  public void addToCollector(AndroidPackageableCollector collector) {
    super.addToCollector(collector);
    collector.addNativeLibsDirectory(getBuildTarget(), nativeLibsDirectory);

    collector.addResourceDirectory(getBuildTarget(), getRes());
    collector.addAssetsDirectory(getBuildTarget(), getAssets());
  }

  public PrebuiltJar getPrebuiltJar() {
    return prebuiltJar;
  }

  public SourcePath getBinaryJar() {
    return prebuiltJar.getSourcePathToOutput();
  }

  // This class is basically a wrapper around its android resource rule, since dependents will
  // use this interface to access the underlying R.java package, so make sure it's available when
  // a dependent is building against us.
  @Override
  public Stream<BuildTarget> getRuntimeDeps(SourcePathRuleFinder ruleFinder) {
    return Stream.of(unzipAar.getBuildTarget());
  }

  private void maybeAddDummyRDotJavaFlavor(
      BuildTarget androidPrebuiltAarBuildTarget,
      ActionGraphBuilder graphBuilder,
      Javac javac,
      JavacOptions javacOptions,
      boolean forceFinalResourceIds,
      Optional<String> resourceUnionPackage,
      Optional<String> finalRName,
      boolean useOldStyleableFormat,
      ProjectFilesystem projectFilesystem,
      boolean skipNonUnionRDotJava,
      boolean mergeRClasses) {

    ImmutableSet<HasAndroidResourceDeps> androidResourceDeps = ImmutableSet.of(this);

    BuildTarget dummyRDotJavaBuildTarget = AndroidLibraryGraphEnhancer.getDummyRDotJavaTarget(androidPrebuiltAarBuildTarget);

    graphBuilder.computeIfAbsent(
            dummyRDotJavaBuildTarget,
            ignored -> {
              SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(graphBuilder);
              JavacOptions filteredOptions =
                  javacOptions.withExtraArguments(Collections.emptyList());

              JavacToJarStepFactory compileToJarStepFactory =
                  new JavacToJarStepFactory(javac, filteredOptions, ExtraClasspathProvider.EMPTY);

              return new DummyRDotJava(
                  dummyRDotJavaBuildTarget,
                  projectFilesystem,
                  ruleFinder,
                  androidResourceDeps,
                  compileToJarStepFactory,
                  forceFinalResourceIds,
                  resourceUnionPackage,
                  finalRName,
                  useOldStyleableFormat,
                  skipNonUnionRDotJava,
                  mergeRClasses);
            });
  }
}
