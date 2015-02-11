/*
 * Copyright 2015 Red Hat, Inc. and/or its affiliates
 * and other contributors as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.hawkular.alerts.engine.impl;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.CopyOnWriteArrayList;

import javax.annotation.PostConstruct;
import javax.ejb.EJB;
import javax.ejb.Singleton;

import org.hawkular.alerts.api.model.condition.Alert;
import org.hawkular.alerts.api.model.condition.Condition;
import org.hawkular.alerts.api.model.dampening.Dampening;
import org.hawkular.alerts.api.model.data.Data;
import org.hawkular.alerts.api.model.trigger.Trigger;
import org.hawkular.alerts.api.services.AlertsService;
import org.hawkular.alerts.api.services.DefinitionsService;
import org.hawkular.alerts.api.services.NotificationsService;
import org.hawkular.alerts.engine.log.MsgLogger;
import org.hawkular.alerts.engine.rules.RulesEngine;

import org.jboss.logging.Logger;

/**
 * Basic implementation for {@link org.hawkular.alerts.api.services.AlertsService}.
 * This implementation processes data asynchronously using a buffer queue.
 *
 * @author Jay Shaughnessy
 * @author Lucas Ponce
 */
@Singleton
public class BasicAlertsServiceImpl implements AlertsService {
    private final MsgLogger msgLog = MsgLogger.LOGGER;
    private final Logger log = Logger.getLogger(BasicAlertsServiceImpl.class);
    private static final int DELAY;
    private static final int PERIOD;

    private final List<Data> pendingData;
    private final List<Alert> alerts;

    private final Timer wakeUpTimer;
    private TimerTask rulesTask;

    @EJB
    RulesEngine rules;

    @EJB
    DefinitionsService definitions;

    @EJB
    NotificationsService notifications;

    /*
        Init properties
     */
    static {
        String sDelay = System.getProperty("org.hawkular.alerts.engine.DELAY");
        String sPeriod = System.getProperty("org.hawkular.alerts.engine.PERIOD");
        int dDelay = 1000;
        int dPeriod = 2000;
        try {
            dDelay = new Integer(sDelay).intValue();
            dPeriod = new Integer(sPeriod).intValue();
        } catch (Exception ignored) { }
        DELAY = dDelay;
        PERIOD = dPeriod;
    }

    public BasicAlertsServiceImpl() {
        log.debugf("Creating instance.");
        pendingData = new CopyOnWriteArrayList<Data>();
        alerts = new CopyOnWriteArrayList<Alert>();
        wakeUpTimer = new Timer("BasicAlertsServiceImpl-Timer");
    }

    @PostConstruct
    public void initServices() {
        reload();
    }

    @Override
    public Collection<Alert> checkAlerts() {
        return Collections.unmodifiableCollection(alerts);
    }

    @Override
    public void clear() {
        rulesTask.cancel();

        rules.clear();

        pendingData.clear();
        alerts.clear();

        rulesTask = new RulesInvoker();
        wakeUpTimer.schedule(rulesTask, DELAY, PERIOD);
    }

    @Override
    public void reload() {
        rules.reset();
        if (rulesTask != null) {
            rulesTask.cancel();
        }

        Collection<Trigger> triggers = null;
        try {
            definitions.getTriggers();
        } catch (Exception e) {
            log.debugf(e.getMessage(), e);
            msgLog.errorDefinitionsService("Triggers", e.getMessage());
        }
        if (triggers != null && !triggers.isEmpty()) {
            rules.addFacts(triggers);
        }

        Collection<Dampening> dampenings = null;
        try {
            definitions.getDampenings();
        } catch (Exception e) {
            log.debugf(e.getMessage(), e);
            msgLog.errorDefinitionsService("Dampenings", e.getMessage());
        }
        if (dampenings != null && !dampenings.isEmpty()) {
            rules.addFacts(dampenings);
        }

        Collection<Condition> conditions = null;
        try {
            definitions.getConditions();
        } catch (Exception e) {
            log.debugf(e.getMessage(), e);
            msgLog.errorDefinitionsService("Conditions", e.getMessage());
        }
        if (conditions != null && !conditions.isEmpty()) {
            rules.addFacts(conditions);
        }

        rules.addGlobal("log", log);
        rules.addGlobal("notifications", notifications);
        rules.addGlobal("alerts", alerts);

        rulesTask = new RulesInvoker();
        wakeUpTimer.schedule(rulesTask, DELAY, PERIOD);
    }

    @Override
    public void sendData(Collection<Data> data) {
        if (data == null) {
            throw new IllegalArgumentException("Data must be not null");
        }
        pendingData.addAll(data);
    }

    @Override
    public void sendData(Data data) {
        if (data == null) {
            throw new IllegalArgumentException("Data must be not null");
        }
        pendingData.add(data);
    }

    private class RulesInvoker extends TimerTask {
        @Override
        public void run() {
            if (!pendingData.isEmpty()) {

                log.debugf("Pending data [%1$d] found. Executing rules engine.", pendingData.size());

                rules.addData(pendingData);
                pendingData.clear();

                try {
                    rules.fire();
                } catch (Exception e) {
                    log.debugf("Error on rules processing: " + e.getMessage(), e);
                    msgLog.errorProcessingRules(e.getMessage());
                }
            }
        }
    }
}
