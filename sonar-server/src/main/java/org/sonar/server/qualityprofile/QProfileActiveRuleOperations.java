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

package org.sonar.server.qualityprofile;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Splitter;
import com.google.common.base.Strings;
import org.apache.ibatis.session.SqlSession;
import org.elasticsearch.common.base.Predicate;
import org.elasticsearch.common.collect.Iterables;
import org.sonar.api.ServerComponent;
import org.sonar.api.rule.RuleKey;
import org.sonar.api.rule.Severity;
import org.sonar.api.rules.RulePriority;
import org.sonar.api.server.rule.RuleParamType;
import org.sonar.api.utils.System2;
import org.sonar.core.permission.GlobalPermissions;
import org.sonar.core.persistence.DbSession;
import org.sonar.core.persistence.MyBatis;
import org.sonar.core.qualityprofile.db.ActiveRuleDto;
import org.sonar.core.qualityprofile.db.ActiveRuleKey;
import org.sonar.core.qualityprofile.db.ActiveRuleParamDto;
import org.sonar.core.qualityprofile.db.QualityProfileDao;
import org.sonar.core.qualityprofile.db.QualityProfileDto;
import org.sonar.core.qualityprofile.db.QualityProfileKey;
import org.sonar.core.rule.RuleDto;
import org.sonar.core.rule.RuleParamDto;
import org.sonar.server.exceptions.BadRequestException;
import org.sonar.server.exceptions.NotFoundException;
import org.sonar.server.qualityprofile.persistence.ActiveRuleDao;
import org.sonar.server.rule2.persistence.RuleDao;
import org.sonar.server.user.UserSession;
import org.sonar.server.util.TypeValidations;

import javax.annotation.CheckForNull;
import javax.annotation.Nullable;
import java.util.Date;
import java.util.List;

import static com.google.common.collect.Lists.newArrayList;

/**
 * @deprecated to be dropped in 4.4
 */
@Deprecated
public class QProfileActiveRuleOperations implements ServerComponent {

  private final MyBatis myBatis;
  private final ActiveRuleDao activeRuleDao;
  private final RuleDao ruleDao;
  private final QualityProfileDao profileDao;
  private final ESActiveRule esActiveRule;
  private final ProfilesManager profilesManager;
  private final QProfileRuleLookup rules;

  private final System2 system;
  private final TypeValidations typeValidations;

  public QProfileActiveRuleOperations(MyBatis myBatis, ActiveRuleDao activeRuleDao, RuleDao ruleDao, QualityProfileDao profileDao, ESActiveRule esActiveRule,
                                      ProfilesManager profilesManager, TypeValidations typeValidations, QProfileRuleLookup rules) {
    this(myBatis, activeRuleDao, ruleDao, profileDao, esActiveRule, profilesManager, rules, typeValidations, System2.INSTANCE);
  }

  @VisibleForTesting
  QProfileActiveRuleOperations(MyBatis myBatis, ActiveRuleDao activeRuleDao, RuleDao ruleDao, QualityProfileDao profileDao, ESActiveRule esActiveRule,
                               ProfilesManager profilesManager, QProfileRuleLookup rules, TypeValidations typeValidations, System2 system) {
    this.myBatis = myBatis;
    this.activeRuleDao = activeRuleDao;
    this.ruleDao = ruleDao;
    this.profileDao = profileDao;
    this.esActiveRule = esActiveRule;
    this.profilesManager = profilesManager;
    this.rules = rules;
    this.typeValidations = typeValidations;
    this.system = system;
  }

  public void activateRule(QualityProfileKey profileKey, RuleKey ruleKey, String severity, UserSession userSession) {
    validatePermission(userSession);
    validateSeverity(severity);

    DbSession session = myBatis.openSession(false);
    try {
      ActiveRuleDto activeRule = findActiveRule(profileKey, ruleKey, session);
      if (activeRule == null) {
        activateRule(profileKey, ruleKey, severity, userSession, session);
      } else {
        updateSeverity(activeRule, severity, userSession, session);
      }
    } finally {
      MyBatis.closeQuietly(session);
    }
  }

  private ActiveRuleDto activateRule(QualityProfileKey profileKey, RuleKey ruleKey, String severity, UserSession userSession, DbSession session) {
    ActiveRuleDto activeRule = createActiveRule(profileKey, ruleKey, severity, session);
    session.commit();
    //FIXME this is because profileManger uses Hibernate still
    QualityProfileDto profile = profileDao.selectByNameAndLanguage(profileKey.name(), profileKey.lang());
    ProfilesManager.RuleInheritanceActions actions = profilesManager.activated(profile.getId(), activeRule.getId(), getLoggedName(userSession));
    reindexInheritanceResult(actions, session);
    return activeRule;
    
  }

  ActiveRuleDto createActiveRule(QualityProfileKey profileKey, RuleKey ruleKey, String severity, DbSession session) {
    RuleDto ruleDto = ruleDao.getByKey(ruleKey, session);
    //TODO use BaseDao for profileDao
    QualityProfileDto profileDto = profileDao.selectByNameAndLanguage(profileKey.name(), profileKey.lang(), session);
    ActiveRuleDto activeRule = ActiveRuleDto.createFor(profileDto, ruleDto)
      .setSeverity(severity);
    activeRuleDao.insert(activeRule, session);

    List<RuleParamDto> ruleParams = ruleDao.findRuleParamsByRuleKey(ruleKey, session);
    List<ActiveRuleParamDto> activeRuleParams = newArrayList();
    for (RuleParamDto ruleParam : ruleParams) {
      ActiveRuleParamDto activeRuleParam = ActiveRuleParamDto.createFor(ruleParam)
        .setKey(ruleParam.getName())
        .setValue(ruleParam.getDefaultValue());
      activeRuleParams.add(activeRuleParam);
      activeRuleDao.addParam(activeRule, activeRuleParam, session);
    }
    return activeRule;
  }

  private void updateSeverity(ActiveRuleDto activeRule, String newSeverity, UserSession userSession, DbSession session) {
    String oldSeverity = activeRule.getSeverityString();
    activeRule.setSeverity(newSeverity);
    activeRuleDao.update(activeRule, session);
    session.commit();

    notifySeverityChanged(activeRule, newSeverity, oldSeverity, session, userSession);
  }

  public int activateRules(QualityProfileKey profileKey, ProfileRuleQuery query, UserSession userSession) {
    validatePermission(userSession);

    DbSession session = myBatis.openSession(false);
    try {
      List<Integer> ruleIdsToActivate = rules.searchInactiveProfileRuleIds(query);
      for (Integer ruleId : ruleIdsToActivate) {
        RuleDto rule = findRuleNotNull(ruleId, session);
        activateRule(profileKey, rule.getKey(), rule.getSeverityString(), userSession, session);
      }
      return ruleIdsToActivate.size();
    } finally {
      MyBatis.closeQuietly(session);
    }
  }

  public boolean deactivateRule(QualityProfileKey profileKey, RuleKey ruleKey, UserSession userSession) {
    validatePermission(userSession);
    DbSession session = myBatis.openSession(false);
    try {
      ActiveRuleDto activeRule = findActiveRuleNotNull(profileKey, ruleKey, session);
      return deactivateRule(activeRule, userSession, session);
    } finally {
      MyBatis.closeQuietly(session);
    }
  }

  private boolean deactivateRule(ActiveRuleDto activeRule, UserSession userSession, DbSession session) {
    if (activeRule.getInheritance() == null) {
      ProfilesManager.RuleInheritanceActions actions = profilesManager.deactivated(activeRule.getProfileId(), activeRule.getId(), getLoggedName(userSession));

      activeRuleDao.removeAllParam(activeRule, session);
      activeRuleDao.delete(activeRule, session);
      actions.addToDelete(activeRule.getId());
      session.commit();

      reindexInheritanceResult(actions, session);
      return true;
    }
    return false;
  }

  public int deactivateRules(ProfileRuleQuery query, UserSession userSession) {
    validatePermission(userSession);

    DbSession session = myBatis.openSession(false);
    int numberOfDeactivatedRules = 0;
    try {
      List<Integer> activeRuleIdsToDeactivate = rules.searchProfileRuleIds(query);
      for (int activeRuleId : activeRuleIdsToDeactivate) {
        ActiveRuleDto activeRule = findActiveRuleNotNull(activeRuleId, session);
        if (deactivateRule(activeRule, userSession, session)) {
          numberOfDeactivatedRules++;
        }
      }
      return numberOfDeactivatedRules;
    } finally {
      MyBatis.closeQuietly(session);
    }
  }

  public void updateActiveRuleParam(int activeRuleId, String key, @Nullable String value, UserSession userSession) {
    validatePermission(userSession);

    DbSession session = myBatis.openSession(false);
    try {
      String sanitizedValue = Strings.emptyToNull(value);
      ActiveRuleParamDto activeRuleParam = findActiveRuleParam(activeRuleId, key, session);
      ActiveRuleDto activeRule = findActiveRuleNotNull(activeRuleId, session);
      if (activeRuleParam == null && sanitizedValue != null) {
        createActiveRuleParam(activeRule, key, sanitizedValue, userSession, session);
      } else if (activeRuleParam != null && sanitizedValue == null) {
        deleteActiveRuleParam(activeRule, activeRuleParam, userSession, session);
      } else if (activeRuleParam != null) {
        updateActiveRuleParam(activeRule, activeRuleParam, sanitizedValue, userSession, session);
      }
      // If no active rule param and no value -> do nothing

    } finally {
      MyBatis.closeQuietly(session);
    }
  }

  private void createActiveRuleParam(ActiveRuleDto activeRule, String key, String value, UserSession userSession, DbSession session) {
    RuleParamDto ruleParam = findRuleParamNotNull(activeRule.getRulId(), key, session);
    validateParam(ruleParam, value);
    ActiveRuleParamDto activeRuleParam = ActiveRuleParamDto.createFor(ruleParam)
      .setKey(key)
      .setValue(value);
    activeRuleDao.addParam(activeRule, activeRuleParam, session);
    session.commit();

    ProfilesManager.RuleInheritanceActions actions = profilesManager.ruleParamChanged(
      activeRule.getProfileId(), activeRule.getId(), key, null, value, getLoggedName(userSession));
    reindexInheritanceResult(actions, session);
  }

  private void deleteActiveRuleParam(ActiveRuleDto activeRule, ActiveRuleParamDto activeRuleParam, UserSession userSession, DbSession session) {
    activeRuleDao.removeParam(activeRule, activeRuleParam, session);
    session.commit();
    notifyParamsDeleted(activeRule, newArrayList(activeRuleParam), session, userSession);
  }

  void updateActiveRuleParam(ActiveRuleDto activeRule, String key, String sanitizedValue, DbSession session) {
    RuleParamDto ruleParam = findRuleParamNotNull(activeRule.getRulId(), key, session);
    ActiveRuleParamDto activeRuleParam = findActiveRuleParamNotNull(activeRule.getId(), key, session);
    validateParam(ruleParam, sanitizedValue);

    activeRuleParam.setValue(sanitizedValue);
    activeRuleDao.updateParam(activeRule, activeRuleParam, session);
  }

  private void updateActiveRuleParam(ActiveRuleDto activeRule, ActiveRuleParamDto activeRuleParam, String sanitizedValue, UserSession userSession, DbSession session) {
    RuleParamDto ruleParam = findRuleParamNotNull(activeRule.getRulId(), activeRuleParam.getKey(), session);
    validateParam(ruleParam, sanitizedValue);

    String oldValue = activeRuleParam.getValue();
    activeRuleParam.setValue(sanitizedValue);
    activeRuleDao.updateParam(activeRule, activeRuleParam, session);
    session.commit();

    ProfilesManager.RuleInheritanceActions actions = profilesManager.ruleParamChanged(activeRule.getProfileId(), activeRule.getId(), activeRuleParam.getKey(), oldValue,
      sanitizedValue, getLoggedName(userSession));
    reindexInheritanceResult(actions, session);
  }

  public void revertActiveRule(int activeRuleId, UserSession userSession) {
    validatePermission(userSession);

    DbSession session = myBatis.openSession(false);
    try {
      ActiveRuleDto activeRule = findActiveRuleNotNull(activeRuleId, session);
      if (activeRule.doesOverride()) {
        revertActiveRule(activeRule, userSession, session);
      }
    } finally {
      MyBatis.closeQuietly(session);
    }
  }

  private void revertActiveRule(ActiveRuleDto activeRule, UserSession userSession, DbSession session) {
    ProfilesManager.RuleInheritanceActions actions = new ProfilesManager.RuleInheritanceActions();
    ActiveRuleDto parent = getParent(activeRule, session);

    List<ActiveRuleParamDto> newParams = restoreActiveParametersFromActiveRuleParent(activeRule, parent, actions, userSession, session);
    restoreSeverityFromActiveRuleParent(activeRule, parent, actions, userSession, session);
    reindexInheritanceResult(actions, session);

    // Update inheritance
    activeRule.setInheritance(ActiveRuleDto.INHERITED);
    activeRuleDao.update(activeRule, session);
    session.commit();
    reindexActiveRule(activeRule, newParams);
  }

  private ActiveRuleDto getParent(ActiveRuleDto activeRule, DbSession session) {
    Integer parentId = activeRule.getParentId();
    if (parentId != null) {
      ActiveRuleDto parent = activeRuleDao.getById(parentId, session);
      if (parent != null) {
        return parent;
      }
    }
    throw new IllegalStateException("Can't find parent of active rule : " + activeRule.getId());
  }

  private List<ActiveRuleParamDto> restoreActiveParametersFromActiveRuleParent(ActiveRuleDto activeRule, ActiveRuleDto parent, ProfilesManager.RuleInheritanceActions actions,
                                                                               UserSession userSession, DbSession session) {
    // Restore all parameters from parent
    List<ActiveRuleParamDto> parentParams = activeRuleDao.findParamsByActiveRule(parent, session);
    List<ActiveRuleParamDto> activeRuleParams = activeRuleDao.findParamsByActiveRule(activeRule, session);
    List<ActiveRuleParamDto> newParams = newArrayList();
    List<String> paramKeys = newArrayList();
    for (ActiveRuleParamDto param : activeRuleParams) {
      final String key = param.getKey();
      ActiveRuleParamDto parentParam = Iterables.find(parentParams, new Predicate<ActiveRuleParamDto>() {
        @Override
        public boolean apply(ActiveRuleParamDto activeRuleParamDto) {
          return activeRuleParamDto.getKey().equals(key);
        }
      }, null);
      if (parentParam != null && !Strings.isNullOrEmpty(parentParam.getValue())) {
        String oldValue = param.getValue();
        String newValue = parentParam.getValue();
        param.setValue(newValue);
        activeRuleDao.updateParam(activeRule, param, session);
        session.commit();
        newParams.add(param);
        actions.add(profilesManager.ruleParamChanged(activeRule.getProfileId(), activeRule.getId(), key, oldValue, newValue, getLoggedName(userSession)));
      } else {
        activeRuleDao.removeParam(activeRule, param, session);
        session.commit();
        actions.add(profilesManager.ruleParamChanged(activeRule.getProfileId(), activeRule.getId(), key, param.getValue(), null, getLoggedName(userSession)));
      }
      paramKeys.add(key);
    }
    for (ActiveRuleParamDto parentParam : parentParams) {
      if (!paramKeys.contains(parentParam.getKey())) {
        ActiveRuleParamDto activeRuleParam = ActiveRuleParamDto.createFrom(parentParam)
          .setKey(parentParam.getKey())
          .setValue(parentParam.getValue());
        activeRuleDao.addParam(activeRule, activeRuleParam, session);
        session.commit();
        newParams.add(activeRuleParam);
        actions.add(profilesManager.ruleParamChanged(activeRule.getProfileId(), activeRule.getId(), parentParam.getKey(), null, parentParam.getValue(),
          getLoggedName(userSession)));
      }
    }
    return newParams;
  }

  private void restoreSeverityFromActiveRuleParent(ActiveRuleDto activeRule, ActiveRuleDto parent, ProfilesManager.RuleInheritanceActions actions,
                                                   UserSession userSession, DbSession session) {
    String oldSeverity = activeRule.getSeverityString();
    String newSeverity = parent.getSeverityString();
    if (!oldSeverity.equals(newSeverity)) {
      activeRule.setSeverity(newSeverity);
      activeRuleDao.update(activeRule, session);
      session.commit();
      actions.add(profilesManager.ruleSeverityChanged(activeRule.getProfileId(), activeRule.getId(),
        RulePriority.valueOf(oldSeverity), RulePriority.valueOf(newSeverity), getLoggedName(userSession)));
    }
  }

  public void updateActiveRuleNote(int activeRuleId, String note, UserSession userSession) {
    validatePermission(userSession);
    DbSession session = myBatis.openSession(false);

    try {
      ActiveRuleDto activeRule = findActiveRuleNotNull(activeRuleId, session);
      String sanitizedNote = Strings.emptyToNull(note);
      if (sanitizedNote != null) {
        Date now = new Date(system.now());
        if (activeRule.getNoteData() == null) {
          activeRule.setNoteCreatedAt(now);
          activeRule.setNoteUserLogin(userSession.login());
        }
        activeRule.setNoteUpdatedAt(now);
        activeRule.setNoteData(note);
        activeRuleDao.update(activeRule, session);
        session.commit();

        reindexActiveRule(activeRule, session);
      }
    } finally {
      MyBatis.closeQuietly(session);
    }
  }

  public void deleteActiveRuleNote(int activeRuleId, UserSession userSession) {
    validatePermission(userSession);

    DbSession session = myBatis.openSession(false);
    try {
      ActiveRuleDto activeRule = findActiveRuleNotNull(activeRuleId, session);

      activeRule.setNoteData(null);
      activeRule.setNoteUserLogin(null);
      activeRule.setNoteCreatedAt(null);
      activeRule.setNoteUpdatedAt(null);
      activeRuleDao.update(activeRule, session);
      session.commit();

      reindexActiveRule(activeRule, session);
    } finally {
      MyBatis.closeQuietly(session);
    }
  }

  private void notifyParamsDeleted(ActiveRuleDto activeRule, List<ActiveRuleParamDto> params, SqlSession session, UserSession userSession) {
    ProfilesManager.RuleInheritanceActions actions = new ProfilesManager.RuleInheritanceActions();
    for (ActiveRuleParamDto activeRuleParam : params) {
      actions.add(profilesManager.ruleParamChanged(activeRule.getProfileId(), activeRule.getId(), activeRuleParam.getKey(), activeRuleParam.getValue(),
        null, getLoggedName(userSession)));
    }
    reindexInheritanceResult(actions, session);
  }

  private void notifySeverityChanged(ActiveRuleDto activeRule, String newSeverity, String oldSeverity, SqlSession session, UserSession userSession) {
    ProfilesManager.RuleInheritanceActions actions = profilesManager.ruleSeverityChanged(activeRule.getProfileId(), activeRule.getId(),
      RulePriority.valueOf(oldSeverity), RulePriority.valueOf(newSeverity),
      getLoggedName(userSession));
    reindexInheritanceResult(actions, session);
  }

  private void reindexInheritanceResult(ProfilesManager.RuleInheritanceActions actions, SqlSession session) {
    esActiveRule.deleteActiveRules(actions.idsToDelete());
    esActiveRule.bulkIndexActiveRuleIds(actions.idsToIndex(), session);
  }

  @Deprecated
  private void reindexActiveRule(ActiveRuleDto activeRuleDto, DbSession session) {
    reindexActiveRule(activeRuleDto, activeRuleDao.findParamsByActiveRule(activeRuleDto, session));
  }

  @Deprecated
  private void reindexActiveRule(ActiveRuleDto activeRuleDto, List<ActiveRuleParamDto> params) {
    esActiveRule.save(activeRuleDto, params);
  }

  private void validatePermission(UserSession userSession) {
    userSession.checkLoggedIn();
    userSession.checkGlobalPermission(GlobalPermissions.QUALITY_PROFILE_ADMIN);
  }

  private void validateSeverity(String severity) {
    if (!Severity.ALL.contains(severity)) {
      throw new BadRequestException("The severity is not valid");
    }
  }

  private void validateParam(RuleParamDto ruleParam, String value) {
    RuleParamType ruleParamType = RuleParamType.parse(ruleParam.getType());
    if (ruleParamType.multiple()) {
      List<String> values = newArrayList(Splitter.on(",").split(value));
      typeValidations.validate(values, ruleParamType.type(), ruleParamType.values());
    } else {
      typeValidations.validate(value, ruleParamType.type(), ruleParamType.values());
    }
  }

  private String getLoggedName(UserSession userSession) {
    String name = userSession.name();
    if (Strings.isNullOrEmpty(name)) {
      throw new BadRequestException("User name can't be null");
    }
    return name;
  }

  private RuleParamDto findRuleParamNotNull(Integer ruleId, String key, DbSession session) {
    RuleDto rule = ruleDao.getById(ruleId, session);
    RuleParamDto ruleParam = ruleDao.getRuleParamByRuleAndParamKey(rule, key, session);
    if (ruleParam == null) {
      throw new IllegalArgumentException("No rule param found");
    }
    return ruleParam;
  }

  private QualityProfileDto findProfileNotNull(int profileId, DbSession session) {
    QualityProfileDto profile = profileDao.selectById(profileId, session);
    QProfileValidations.checkProfileIsNotNull(profile);
    return profile;
  }

  private RuleDto findRuleNotNull(int ruleId, DbSession session) {
    RuleDto rule = ruleDao.getById(ruleId, session);
    QProfileValidations.checkRuleIsNotNull(rule);
    return rule;
  }

  @CheckForNull
  private ActiveRuleDto findActiveRule(QualityProfileKey profileKey, RuleKey ruleKey, DbSession session) {
    return activeRuleDao.getByKey(
    ActiveRuleKey.of(profileKey,ruleKey), session);
  }

  private ActiveRuleDto findActiveRuleNotNull(QualityProfileKey profileKey, RuleKey ruleKey, DbSession session) {
    ActiveRuleDto activeRule = findActiveRule(profileKey, ruleKey, session);
    QProfileValidations.checkActiveRuleIsNotNull(activeRule);
    return activeRule;
  }

  private ActiveRuleDto findActiveRuleNotNull(int activeRuleId, DbSession session) {
    ActiveRuleDto activeRule = activeRuleDao.getById(activeRuleId, session);
    QProfileValidations.checkActiveRuleIsNotNull(activeRule);
    return activeRule;
  }

  @CheckForNull
  private ActiveRuleParamDto findActiveRuleParam(int activeRuleId, String key, DbSession session) {
    ActiveRuleDto activeRule = activeRuleDao.getById(activeRuleId, session);
    return activeRuleDao.getParamsByActiveRuleAndKey(activeRule, key, session);
  }

  private ActiveRuleParamDto findActiveRuleParamNotNull(int activeRuleId, String key, DbSession session) {
    ActiveRuleParamDto activeRuleParam = findActiveRuleParam(activeRuleId, key, session);
    if (activeRuleParam == null) {
      throw new NotFoundException(String.format("No active rule parameter '%s' has been found on active rule id '%s'", key, activeRuleId));
    }
    return activeRuleParam;
  }

}
