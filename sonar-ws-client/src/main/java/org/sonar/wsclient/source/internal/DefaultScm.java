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

package org.sonar.wsclient.source.internal;

import org.sonar.wsclient.source.Scm;
import org.sonar.wsclient.unmarshallers.JsonUtils;

import javax.annotation.CheckForNull;

import java.util.Date;
import java.util.List;

public class DefaultScm implements Scm {

  private final List json;

  public DefaultScm(List json) {
    this.json = json;
  }

  @Override
  public long index() {
    return (Long) json.get(0);
  }

  @Override
  @CheckForNull
  public String author() {
    return (String) json.get(1);
  }

  @Override
  @CheckForNull
  public Date date() {
    return JsonUtils.parseDate((String) json.get(2));
  }

}
