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
package com.facebook.buck.core.rules.analysis.impl;

import static org.junit.Assert.assertSame;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.rules.actions.ActionAnalysisData;
import com.facebook.buck.core.rules.actions.ActionAnalysisData.ID;
import com.facebook.buck.core.rules.actions.ActionAnalysisDataKey;
import com.facebook.buck.core.rules.analysis.RuleAnalysisKey;
import com.facebook.buck.core.rules.providers.ProviderInfoCollection;
import com.facebook.buck.core.rules.providers.impl.ProviderInfoCollectionImpl;
import com.google.common.base.VerifyException;
import com.google.common.collect.ImmutableMap;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class RuleAnalysisContextImplTest {

  @Rule public ExpectedException expectedException = ExpectedException.none();

  @Test
  public void getDepsReturnCorrectDeps() {
    ImmutableMap<RuleAnalysisKey, ProviderInfoCollection> deps = ImmutableMap.of();
    assertSame(deps, new RuleAnalysisContextImpl(deps).deps());

    deps =
        ImmutableMap.of(
            ImmutableRuleAnalysisKeyImpl.of(BuildTargetFactory.newInstance("//my:foo")),
            ProviderInfoCollectionImpl.builder().build());
    assertSame(deps, new RuleAnalysisContextImpl(deps).deps());
  }

  @Test
  public void registerActionRegistersToGivenActionRegistry() {
    RuleAnalysisContextImpl context = new RuleAnalysisContextImpl(ImmutableMap.of());

    ActionAnalysisData actionAnalysisData1 =
        new ActionAnalysisData() {
          private final ActionAnalysisDataKey key =
              getNewKey(BuildTargetFactory.newInstance("//my:foo"), new ID() {});

          @Override
          public ActionAnalysisDataKey getKey() {
            return key;
          }
        };

    context.registerAction(actionAnalysisData1);

    assertSame(
        actionAnalysisData1,
        context.getRegisteredActionData().get(actionAnalysisData1.getKey().getID()));

    ActionAnalysisData actionAnalysisData2 =
        new ActionAnalysisData() {
          private final ActionAnalysisDataKey key =
              getNewKey(BuildTargetFactory.newInstance("//my:foo"), new ID() {});

          @Override
          public ActionAnalysisDataKey getKey() {
            return key;
          }
        };

    context.registerAction(actionAnalysisData2);

    assertSame(
        actionAnalysisData2,
        context.getRegisteredActionData().get(actionAnalysisData2.getKey().getID()));
    assertSame(
        actionAnalysisData1,
        context.getRegisteredActionData().get(actionAnalysisData1.getKey().getID()));
  }

  @Test
  public void registerConflictingActionsThrows() {
    expectedException.expect(VerifyException.class);

    RuleAnalysisContextImpl context = new RuleAnalysisContextImpl(ImmutableMap.of());

    ActionAnalysisDataKey key =
        new ActionAnalysisDataKey() {
          private final ID id = new ID() {};

          @Override
          public BuildTarget getBuildTarget() {
            return BuildTargetFactory.newInstance("//my:target");
          }

          @Override
          public ID getID() {
            return id;
          }
        };

    ActionAnalysisData actionAnalysisData1 = () -> key;

    context.registerAction(actionAnalysisData1);

    ActionAnalysisData actionAnalysisData2 = () -> key;

    context.registerAction(actionAnalysisData2);
  }

  private static ActionAnalysisDataKey getNewKey(BuildTarget target, ActionAnalysisData.ID id) {
    return new ActionAnalysisDataKey() {
      @Override
      public BuildTarget getBuildTarget() {
        return target;
      }

      @Override
      public ID getID() {
        return id;
      }
    };
  }
}
