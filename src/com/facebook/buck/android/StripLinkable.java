/*
 * Copyright 2015-present Facebook, Inc.
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

import com.facebook.buck.core.build.buildable.context.BuildableContext;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.common.BuildableSupport;
import com.facebook.buck.core.rules.common.BuildableSupport.DepsSupplier;
import com.facebook.buck.core.rules.impl.AbstractBuildRule;
import com.facebook.buck.core.sourcepath.ExplicitBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.io.BuildCellRelativePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MkdirStep;
import com.google.common.collect.ImmutableList;
import java.nio.file.Path;
import java.util.SortedSet;

public class StripLinkable extends AbstractBuildRule {

  @AddToRuleKey private final Tool stripTool;

  @AddToRuleKey private final SourcePath sourcePathToStrip;

  @AddToRuleKey private final String strippedObjectName;

  private final Path resultDir;
  private final DepsSupplier depsSupplier;

  public StripLinkable(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      SourcePathRuleFinder ruleFinder,
      Tool stripTool,
      SourcePath sourcePathToStrip,
      String strippedObjectName) {
    super(buildTarget, projectFilesystem);
    this.stripTool = stripTool;
    this.strippedObjectName = strippedObjectName;
    this.sourcePathToStrip = sourcePathToStrip;
    this.resultDir = BuildTargetPaths.getGenPath(getProjectFilesystem(), buildTarget, "%s");
    this.depsSupplier = BuildableSupport.buildDepsSupplier(this, ruleFinder);
  }

  @Override
  public void updateBuildRuleResolver(
      BuildRuleResolver ruleResolver, SourcePathRuleFinder ruleFinder) {
    depsSupplier.updateRuleFinder(ruleFinder);
  }

  @Override
  public SortedSet<BuildRule> getBuildDeps() {
    return depsSupplier.get();
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {

    ImmutableList.Builder<Step> steps = ImmutableList.builder();

    steps.add(
        MkdirStep.of(
            BuildCellRelativePath.fromCellRelativePath(
                context.getBuildCellRootPath(), getProjectFilesystem(), resultDir)));
    Path output = context.getSourcePathResolver().getRelativePath(getSourcePathToOutput());
    steps.add(
        new StripStep(
            getProjectFilesystem().getRootPath(),
            stripTool.getEnvironment(context.getSourcePathResolver()),
            stripTool.getCommandPrefix(context.getSourcePathResolver()),
            ImmutableList.of("--strip-unneeded"),
            context.getSourcePathResolver().getAbsolutePath(sourcePathToStrip),
            output));

    buildableContext.recordArtifact(output);

    return steps.build();
  }

  @Override
  public SourcePath getSourcePathToOutput() {
    return ExplicitBuildTargetSourcePath.of(
        getBuildTarget(), resultDir.resolve(strippedObjectName));
  }
}
