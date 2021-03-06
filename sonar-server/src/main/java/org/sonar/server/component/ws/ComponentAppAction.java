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

package org.sonar.server.component.ws;

import com.google.common.io.Resources;
import org.sonar.api.component.Component;
import org.sonar.api.i18n.I18n;
import org.sonar.api.measures.CoreMetrics;
import org.sonar.api.measures.Metric;
import org.sonar.api.server.ws.Request;
import org.sonar.api.server.ws.RequestHandler;
import org.sonar.api.server.ws.Response;
import org.sonar.api.server.ws.WebService;
import org.sonar.api.utils.Durations;
import org.sonar.api.utils.text.JsonWriter;
import org.sonar.api.web.UserRole;
import org.sonar.core.component.ComponentDto;
import org.sonar.core.measure.db.MeasureDao;
import org.sonar.core.measure.db.MeasureDto;
import org.sonar.core.properties.PropertiesDao;
import org.sonar.core.properties.PropertyDto;
import org.sonar.core.properties.PropertyQuery;
import org.sonar.core.resource.ResourceDao;
import org.sonar.server.exceptions.NotFoundException;
import org.sonar.server.user.UserSession;

import javax.annotation.CheckForNull;
import javax.annotation.Nullable;

import java.util.List;

public class ComponentAppAction implements RequestHandler {

  private static final String KEY = "key";

  private final ResourceDao resourceDao;
  private final MeasureDao measureDao;
  private final PropertiesDao propertiesDao;
  private final Durations durations;
  private final I18n i18n;

  public ComponentAppAction(ResourceDao resourceDao, MeasureDao measureDao, PropertiesDao propertiesDao, Durations durations, I18n i18n) {
    this.resourceDao = resourceDao;
    this.measureDao = measureDao;
    this.propertiesDao = propertiesDao;
    this.durations = durations;
    this.i18n = i18n;
  }

  void define(WebService.NewController controller) {
    WebService.NewAction action = controller.createAction("app")
      .setDescription("Coverage data required for rendering the component viewer")
      .setSince("4.4")
      .setInternal(true)
      .setHandler(this)
      .setResponseExample(Resources.getResource(this.getClass(), "components-app-example-show.json"));

    action
      .createParam(KEY)
      .setRequired(true)
      .setDescription("File key")
      .setExampleValue("my_project:/src/foo/Bar.php");
  }

  @Override
  public void handle(Request request, Response response) {
    String fileKey = request.mandatoryParam(KEY);
    UserSession userSession = UserSession.get();
    userSession.checkComponentPermission(UserRole.CODEVIEWER, fileKey);

    JsonWriter json = response.newJsonWriter();
    json.beginObject();

    ComponentDto component = resourceDao.selectComponentByKey(fileKey);
    if (component == null) {
      throw new NotFoundException(String.format("Component '%s' does not exists.", fileKey));
    }

    List<PropertyDto> propertyDtos = propertiesDao.selectByQuery(PropertyQuery.builder()
      .setKey("favourite")
      .setComponentId(component.getId())
      .setUserId(userSession.userId())
      .build());
    boolean isFavourite = propertyDtos.size() == 1;

    json.prop("key", component.key());
    json.prop("path", component.path());
    json.prop("name", component.name());
    json.prop("q", component.qualifier());

    Component subProject = componentById(component.subProjectId());
    json.prop("subProjectName", subProject != null ? subProject.longName() : null);

    Component project = componentById(component.projectId());
    json.prop("projectName", project != null ? project.longName() : null);

    json.prop("fav", isFavourite);

    json.name("measures").beginObject();
    json.prop("fNcloc", formattedMeasure(fileKey, CoreMetrics.NCLOC));
    json.prop("fCoverage", formattedMeasure(fileKey, CoreMetrics.COVERAGE));
    json.prop("fDuplicationDensity", formattedMeasure(fileKey, CoreMetrics.DUPLICATED_LINES_DENSITY));
    json.prop("fDebt", formattedMeasure(fileKey, CoreMetrics.TECHNICAL_DEBT));
    json.prop("fIssues", formattedMeasure(fileKey, CoreMetrics.VIOLATIONS));
    json.prop("fBlockerIssues", formattedMeasure(fileKey, CoreMetrics.BLOCKER_VIOLATIONS));
    json.prop("fCriticalIssues", formattedMeasure(fileKey, CoreMetrics.CRITICAL_VIOLATIONS));
    json.prop("fMajorIssues", formattedMeasure(fileKey, CoreMetrics.MAJOR_VIOLATIONS));
    json.prop("fMinorIssues", formattedMeasure(fileKey, CoreMetrics.MINOR_VIOLATIONS));
    json.prop("fInfoIssues", formattedMeasure(fileKey, CoreMetrics.INFO_VIOLATIONS));
    json.endObject();

    json.endObject();
    json.close();
  }

  @CheckForNull
  private Component componentById(@Nullable Long componentId) {
    if (componentId != null) {
      return resourceDao.findById(componentId);
    }
    return null;
  }

  @CheckForNull
  private String formattedMeasure(String fileKey, Metric metric) {
    MeasureDto measureDto = measureDao.findByComponentKeyAndMetricKey(fileKey, metric.getKey());
    if (measureDto != null) {
      Double value = measureDto.getValue();
      if (value != null) {
        if (metric.getType().equals(Metric.ValueType.FLOAT)) {
          return i18n.formatDouble(UserSession.get().locale(), value);
        } else if (metric.getType().equals(Metric.ValueType.INT)) {
          return i18n.formatInteger(UserSession.get().locale(), value.intValue());
        } else if (metric.getType().equals(Metric.ValueType.PERCENT)) {
          return i18n.formatDouble(UserSession.get().locale(), value) + "%";
        } else if (metric.getType().equals(Metric.ValueType.WORK_DUR)) {
          return durations.format(UserSession.get().locale(), durations.create(value.longValue()), Durations.DurationFormat.SHORT);
        }
      }
    }
    return null;
  }

}
