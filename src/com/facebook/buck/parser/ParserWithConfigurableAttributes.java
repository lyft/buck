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
package com.facebook.buck.parser;

import com.facebook.buck.core.cell.Cell;
import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.RuleType;
import com.facebook.buck.core.model.platform.ConstraintResolver;
import com.facebook.buck.core.model.platform.Platform;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.parser.buildtargetparser.UnconfiguredBuildTargetFactory;
import com.facebook.buck.core.select.SelectableConfigurationContext;
import com.facebook.buck.core.select.SelectorList;
import com.facebook.buck.core.select.SelectorListResolver;
import com.facebook.buck.core.select.impl.SelectorFactory;
import com.facebook.buck.core.select.impl.SelectorListFactory;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.parser.TargetSpecResolver.TargetNodeFilterForSpecResolver;
import com.facebook.buck.parser.TargetSpecResolver.TargetNodeProviderForSpecResolver;
import com.facebook.buck.parser.api.BuildFileManifest;
import com.facebook.buck.parser.exceptions.BuildFileParseException;
import com.facebook.buck.parser.syntax.ListWithSelects;
import com.facebook.buck.rules.coercer.BuildTargetTypeCoercer;
import com.facebook.buck.rules.coercer.CoerceFailedException;
import com.facebook.buck.rules.coercer.JsonTypeConcatenatingCoercerFactory;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import java.io.IOException;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.function.Supplier;
import javax.annotation.Nullable;

/**
 * An implementation of {@link Parser} that supports attributes with configurable values (i.e.
 * defined using {@code select} keyword).
 *
 * <p>This implementation also supports notion of configuration rules which are used to resolve
 * conditions in {@code select} statements.
 */
class ParserWithConfigurableAttributes extends AbstractParser {

  private final UnconfiguredBuildTargetFactory unconfiguredBuildTargetFactory;
  private final TargetSpecResolver targetSpecResolver;

  ParserWithConfigurableAttributes(
      DaemonicParserState daemonicParserState,
      PerBuildStateFactory perBuildStateFactory,
      TargetSpecResolver targetSpecResolver,
      BuckEventBus eventBus,
      UnconfiguredBuildTargetFactory unconfiguredBuildTargetFactory,
      Supplier<ImmutableList<String>> targetPlatforms) {
    super(daemonicParserState, perBuildStateFactory, eventBus, targetPlatforms);
    this.targetSpecResolver = targetSpecResolver;
    this.unconfiguredBuildTargetFactory = unconfiguredBuildTargetFactory;
  }

  /**
   * This implementation collects raw attributes of a target node and resolves configurable
   * attributes.
   */
  @Override
  @Nullable
  public SortedMap<String, Object> getTargetNodeRawAttributes(
      PerBuildState state, Cell cell, TargetNode<?> targetNode) throws BuildFileParseException {
    BuildTarget buildTarget = targetNode.getBuildTarget();
    Cell owningCell = cell.getCell(buildTarget);
    BuildFileManifest buildFileManifest =
        getTargetNodeRawAttributes(state, owningCell, cell.getAbsolutePathToBuildFile(buildTarget));
    return getTargetFromManifest(state, cell, targetNode, buildFileManifest);
  }

  @Override
  public ListenableFuture<SortedMap<String, Object>> getTargetNodeRawAttributesJob(
      PerBuildState state, Cell cell, TargetNode<?> targetNode) throws BuildFileParseException {
    Cell owningCell = cell.getCell(targetNode.getBuildTarget());
    ListenableFuture<BuildFileManifest> buildFileManifestFuture =
        state.getBuildFileManifestJob(
            owningCell, cell.getAbsolutePathToBuildFile(targetNode.getBuildTarget()));
    return Futures.transform(
        buildFileManifestFuture,
        buildFileManifest -> getTargetFromManifest(state, cell, targetNode, buildFileManifest),
        MoreExecutors.directExecutor());
  }

  @Nullable
  private SortedMap<String, Object> getTargetFromManifest(
      PerBuildState state,
      Cell cell,
      TargetNode<?> targetNode,
      BuildFileManifest buildFileManifest) {
    BuildTarget buildTarget = targetNode.getBuildTarget();
    String shortName = buildTarget.getShortName();

    if (!buildFileManifest.getTargets().containsKey(shortName)) {
      return null;
    }

    Map<String, Object> attributes = buildFileManifest.getTargets().get(shortName);

    SortedMap<String, Object> convertedAttributes =
        copyWithResolvingConfigurableAttributes(state, cell, buildTarget, attributes);

    convertedAttributes.put(
        InternalTargetAttributeNames.DIRECT_DEPENDENCIES,
        targetNode
            .getParseDeps()
            .stream()
            .map(Object::toString)
            .collect(ImmutableList.toImmutableList()));
    return convertedAttributes;
  }

  private SortedMap<String, Object> copyWithResolvingConfigurableAttributes(
      PerBuildState state, Cell cell, BuildTarget buildTarget, Map<String, Object> attributes) {
    PerBuildStateWithConfigurableAttributes stateWithConfigurableAttributes =
        (PerBuildStateWithConfigurableAttributes) state;

    SelectableConfigurationContext configurationContext =
        DefaultSelectableConfigurationContext.of(
            cell.getBuckConfig(),
            stateWithConfigurableAttributes.getConstraintResolver(),
            stateWithConfigurableAttributes.getTargetPlatform().get());

    SelectorListFactory selectorListFactory =
        new SelectorListFactory(
            new SelectorFactory(
                new BuildTargetTypeCoercer(unconfiguredBuildTargetFactory)::coerce));

    SortedMap<String, Object> convertedAttributes = new TreeMap<>();

    for (Map.Entry<String, Object> attribute : attributes.entrySet()) {
      String attributeName = attribute.getKey();
      try {
        convertedAttributes.put(
            attributeName,
            resolveConfigurableAttributes(
                stateWithConfigurableAttributes.getSelectorListResolver(),
                configurationContext,
                cell.getCellPathResolver(),
                cell.getFilesystem(),
                buildTarget,
                selectorListFactory,
                attributeName,
                attribute.getValue()));
      } catch (CoerceFailedException e) {
        throw new HumanReadableException(e, "%s: %s", buildTarget, e.getMessage());
      }
    }

    return convertedAttributes;
  }

  @Nullable
  private Object resolveConfigurableAttributes(
      SelectorListResolver selectorListResolver,
      SelectableConfigurationContext configurationContext,
      CellPathResolver cellPathResolver,
      ProjectFilesystem projectFilesystem,
      BuildTarget buildTarget,
      SelectorListFactory selectorListFactory,
      String attributeName,
      Object jsonObject)
      throws CoerceFailedException {
    if (!(jsonObject instanceof ListWithSelects)) {
      return jsonObject;
    }

    ListWithSelects list = (ListWithSelects) jsonObject;

    SelectorList<Object> selectorList =
        selectorListFactory.create(
            cellPathResolver,
            projectFilesystem,
            buildTarget.getBasePath(),
            list.getElements(),
            JsonTypeConcatenatingCoercerFactory.createForType(list.getType()));

    return selectorListResolver.resolveList(
        configurationContext, buildTarget, attributeName, selectorList);
  }

  @Override
  public ImmutableList<ImmutableSet<BuildTarget>> resolveTargetSpecs(
      ParsingContext parsingContext, Iterable<? extends TargetNodeSpec> specs)
      throws BuildFileParseException, InterruptedException, IOException {

    try (PerBuildStateWithConfigurableAttributes state =
        (PerBuildStateWithConfigurableAttributes)
            perBuildStateFactory.create(parsingContext, permState, targetPlatforms.get())) {
      TargetNodeFilterForSpecResolver<TargetNode<?>> targetNodeFilter =
          (spec, nodes) -> spec.filter(nodes);
      if (parsingContext.excludeUnsupportedTargets()) {
        Platform targetPlatform = state.getTargetPlatform().get();
        ConstraintResolver constraintResolver = state.getConstraintResolver();
        targetNodeFilter =
            new TargetNodeFilterForSpecResolverWithNodeFiltering<>(
                targetNodeFilter,
                node ->
                    TargetCompatibilityChecker.targetNodeArgMatchesPlatform(
                        constraintResolver, node.getConstructorArg(), targetPlatform));
      }

      TargetNodeProviderForSpecResolver<TargetNode<?>> targetNodeProvider =
          DefaultParser.createTargetNodeProviderForSpecResolver(state);
      return targetSpecResolver.resolveTargetSpecs(
          parsingContext.getCell(),
          specs,
          (buildTarget, targetNode, targetType) ->
              DefaultParser.applyDefaultFlavors(
                  buildTarget, targetNode, targetType, parsingContext.getApplyDefaultFlavorsMode()),
          targetNodeProvider,
          targetNodeFilter);
    }
  }

  @Override
  protected ImmutableSet<BuildTarget> collectBuildTargetsFromTargetNodeSpecs(
      ParsingContext parsingContext,
      PerBuildState state,
      Iterable<? extends TargetNodeSpec> targetNodeSpecs,
      boolean excludeConfigurationTargets)
      throws IOException, InterruptedException {
    PerBuildStateWithConfigurableAttributes stateWithConfigurableAttributes =
        (PerBuildStateWithConfigurableAttributes) state;

    TargetNodeProviderForSpecResolver<TargetNode<?>> targetNodeProvider =
        DefaultParser.createTargetNodeProviderForSpecResolver(state);

    TargetNodeFilterForSpecResolver<TargetNode<?>> targetNodeFilter =
        (spec, nodes) -> spec.filter(nodes);

    if (excludeConfigurationTargets) {
      targetNodeFilter =
          new TargetNodeFilterForSpecResolverWithNodeFiltering<>(
              targetNodeFilter, ParserWithConfigurableAttributes::filterOutNonBuildTargets);
    }

    if (parsingContext.excludeUnsupportedTargets()) {
      Platform targetPlatform = stateWithConfigurableAttributes.getTargetPlatform().get();
      ConstraintResolver constraintResolver =
          stateWithConfigurableAttributes.getConstraintResolver();
      targetNodeFilter =
          new TargetNodeFilterForSpecResolverWithNodeFiltering<>(
              targetNodeFilter,
              node ->
                  TargetCompatibilityChecker.targetNodeArgMatchesPlatform(
                      constraintResolver, node.getConstructorArg(), targetPlatform));
    }

    return ImmutableSet.copyOf(
        Iterables.concat(
            targetSpecResolver.resolveTargetSpecs(
                parsingContext.getCell(),
                targetNodeSpecs,
                (buildTarget, targetNode, targetType) ->
                    DefaultParser.applyDefaultFlavors(
                        buildTarget,
                        targetNode,
                        targetType,
                        parsingContext.getApplyDefaultFlavorsMode()),
                targetNodeProvider,
                targetNodeFilter)));
  }

  private static boolean filterOutNonBuildTargets(TargetNode<?> node) {
    return node.getRuleType().getKind() == RuleType.Kind.BUILD;
  }
}
