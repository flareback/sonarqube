/*
 * SonarQube, open source software quality management tool.
 * Copyright (C) 2008-2014 SonarSource
 * mailto:contact AT sonarsource DOT com
 *
 * SonarQube is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * SonarQube is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */

package org.sonar.server.qualityprofile.ws;

import org.junit.Before;
import org.junit.Test;
import org.sonar.api.server.ws.WebService;
import org.sonar.server.qualityprofile.ActiveRuleService;
import org.sonar.server.qualityprofile.QProfileBackup;
import org.sonar.server.ws.WsTester;

import static org.fest.assertions.Assertions.assertThat;
import static org.mockito.Mockito.mock;

public class QProfilesWsTest {

  WebService.Controller controller;

  @Before
  public void setUp() {
    ActiveRuleService activeRuleService = mock(ActiveRuleService.class);
    controller = new WsTester(new QProfilesWs(new QProfileRecreateBuiltInAction(
      mock(QProfileBackup.class)),
      new RuleActivationActions(activeRuleService),
      new BulkRuleActivationActions(activeRuleService)
    )).controller("api/qualityprofiles");
  }

  @Test
  public void define_controller() throws Exception {
    assertThat(controller).isNotNull();
    assertThat(controller.path()).isEqualTo("api/qualityprofiles");
    assertThat(controller.description()).isNotEmpty();
    assertThat(controller.actions()).hasSize(5);
  }

  @Test
  public void define_recreate_built_action() throws Exception {
    WebService.Action restoreProfiles = controller.action("recreate_built_in");
    assertThat(restoreProfiles).isNotNull();
    assertThat(restoreProfiles.isPost()).isTrue();
    assertThat(restoreProfiles.params()).hasSize(1);
  }

  @Test
  public void define_activate_rule_action() throws Exception {
    WebService.Action restoreProfiles = controller.action("activate_rule");
    assertThat(restoreProfiles).isNotNull();
    assertThat(restoreProfiles.isPost()).isTrue();
    assertThat(restoreProfiles.params()).hasSize(6);
  }

  @Test
  public void define_deactivate_rule_action() throws Exception {
    WebService.Action restoreProfiles = controller.action("deactivate_rule");
    assertThat(restoreProfiles).isNotNull();
    assertThat(restoreProfiles.isPost()).isTrue();
    assertThat(restoreProfiles.params()).hasSize(4);
  }

}
