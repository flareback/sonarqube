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
package org.sonar.core.qualityprofile.db;

import com.google.common.base.Preconditions;
import org.sonar.api.rule.RuleKey;

import java.io.Serializable;

/**
 *
 * @since 4.4
 */
public class ActiveRuleKey implements Serializable {
  private final QualityProfileKey qualityProfileKey;
  private final RuleKey ruleKey;

  protected ActiveRuleKey(QualityProfileKey qualityProfileKey, RuleKey ruleKey) {
    this.qualityProfileKey = qualityProfileKey;
    this.ruleKey = ruleKey;
  }

  /**
   * Create a key. Parameters are NOT null.
   */
  public static ActiveRuleKey of(QualityProfileKey qualityProfileKey, RuleKey ruleKey) {
    Preconditions.checkArgument(qualityProfileKey != null, "QProfile is missing");
    Preconditions.checkArgument(ruleKey != null, "RuleKey is missing key");
    return new ActiveRuleKey(qualityProfileKey, ruleKey);
  }

  /**
   * Create a key from a string representation (see {@link #toString()}. An {@link IllegalArgumentException} is raised
   * if the format is not valid.
   */
  public static ActiveRuleKey parse(String s) {
    String[] split = s.split(":");
    Preconditions.checkArgument(split.length == 4, "Bad format of activeRule key: " + s);
    return ActiveRuleKey.of(QualityProfileKey.of(split[0], split[1]),
      RuleKey.of(split[2], split[3]));
  }

  /**
   * Never null
   */
  public RuleKey ruleKey() {
    return ruleKey;
  }

  /**
   * Never null
   */
  public QualityProfileKey qProfile() {
    return qualityProfileKey;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    ActiveRuleKey activeRuleKey = (ActiveRuleKey) o;
    if (!qualityProfileKey.equals(activeRuleKey.qualityProfileKey)) {
      return false;
    }
    if (!ruleKey.equals(activeRuleKey.ruleKey)) {
      return false;
    }
    return true;
  }

  @Override
  public int hashCode() {
    int result = qualityProfileKey.hashCode();
    result = 31 * result + ruleKey.hashCode();
    return result;
  }

  /**
   * Format is "qprofile:rule", for example "Java:squid:AvoidCycle:xpxp"
   */
  @Override
  public String toString() {
    return String.format("%s:%s", ruleKey.toString(), qualityProfileKey.toString());
  }
}

