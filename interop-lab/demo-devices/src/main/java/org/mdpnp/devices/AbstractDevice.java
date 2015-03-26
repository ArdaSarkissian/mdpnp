/*******************************************************************************
 * Copyright (c) 2014, MD PnP Program
 * All rights reserved.
 * 
 * Redistribution and use in source and binary forms, with or without modification, are permitted provided that the following conditions are met:
 * 
 * 1. Redistributions of source code must retain the above copyright notice, this list of conditions and the following disclaimer.
 * 
 * 2. Redistributions in binary form must reproduce the above copyright notice, this list of conditions and the following disclaimer in the documentation and/or other materials provided with the distribution.
 * 
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 ******************************************************************************/
package org.mdpnp.devices;

import ice.Alert;
import ice.DeviceIdentity;
import ice.DeviceIdentityDataWriter;
import ice.DeviceIdentityTypeSupport;
import ice.LocalAlarmSettingsObjectiveDataWriter;
import ice.Numeric;
import ice.NumericDataWriter;
import ice.NumericTypeSupport;
import ice.SampleArray;
import ice.SampleArrayDataWriter;
import ice.SampleArrayTypeSupport;

import java.io.IOException;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import javax.management.JMException;
import javax.management.ObjectInstance;
import javax.management.ObjectName;

import org.mdpnp.rtiapi.data.EventLoop;
import org.mdpnp.rtiapi.data.EventLoop.ConditionHandler;
import org.mdpnp.rtiapi.data.QosProfiles;
import org.mdpnp.rtiapi.data.TopicUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.rti.dds.domain.DomainParticipant;
import com.rti.dds.domain.DomainParticipantFactory;
import com.rti.dds.domain.DomainParticipantQos;
import com.rti.dds.infrastructure.Condition;
import com.rti.dds.infrastructure.InstanceHandle_t;
import com.rti.dds.infrastructure.RETCODE_NO_DATA;
import com.rti.dds.infrastructure.ResourceLimitsQosPolicy;
import com.rti.dds.infrastructure.StatusKind;
import com.rti.dds.infrastructure.Time_t;
import com.rti.dds.publication.Publisher;
import com.rti.dds.subscription.InstanceStateKind;
import com.rti.dds.subscription.ReadCondition;
import com.rti.dds.subscription.SampleInfo;
import com.rti.dds.subscription.SampleInfoSeq;
import com.rti.dds.subscription.SampleStateKind;
import com.rti.dds.subscription.Subscriber;
import com.rti.dds.subscription.ViewStateKind;
import com.rti.dds.topic.Topic;
import com.rti.dds.topic.TopicDescription;

public abstract class AbstractDevice implements ThreadFactory, AbstractDeviceMBean {
    protected final ThreadGroup threadGroup;
    protected final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(this);
    protected final EventLoop eventLoop;

    private static final Logger log = LoggerFactory.getLogger(AbstractDevice.class);

    protected final DomainParticipant domainParticipant;
    protected final Publisher publisher;
    protected final Subscriber subscriber;
    protected TimeManager timeManager;
    protected final Topic deviceIdentityTopic;
    private final DeviceIdentityDataWriter deviceIdentityWriter;
    protected final DeviceIdentity deviceIdentity;
    private InstanceHandle_t deviceIdentityHandle;

    private PartitionAssignmentController partitionAssignmentController;

    protected final Topic numericTopic;
    protected final NumericDataWriter numericDataWriter;

    protected final Topic sampleArrayTopic;
    protected final SampleArrayDataWriter sampleArrayDataWriter;

    private final DeviceClock timestampFactory;

    protected final Topic alarmSettingsTopic;
    protected final ice.AlarmSettingsDataWriter alarmSettingsDataWriter;

    protected final Topic alarmSettingsObjectiveTopic;
    protected final ice.LocalAlarmSettingsObjectiveDataWriter alarmSettingsObjectiveWriter;
    
    protected Topic deviceAlertConditionTopic;
    protected ice.DeviceAlertConditionDataWriter deviceAlertConditionWriter;
    
    protected Topic patientAlertTopic;
    protected ice.AlertDataWriter patientAlertWriter;
    
    protected Topic technicalAlertTopic;
    protected ice.AlertDataWriter technicalAlertWriter;

    protected ice.GlobalAlarmSettingsObjectiveDataReader alarmSettingsObjectiveReader;
    protected ReadCondition alarmSettingsObjectiveCondition;
    
    protected InstanceHolder<ice.DeviceAlertCondition> deviceAlertConditionInstance;


    public Subscriber getSubscriber() {
        return subscriber;
    }

    public static class InstanceHolder<T> {
        public T data;
        public InstanceHandle_t handle;

        public InstanceHolder() {

        }

        public InstanceHolder(T t, InstanceHandle_t handle) {
            this.data = t;
            this.handle = handle;
        }

        @Override
        public String toString() {
            return "[data=" + data + ",handle=" + handle + "]";
        }
    }


    public DeviceIdentity getDeviceIdentity() {
        return deviceIdentity;
    }

    public DomainParticipant getParticipant() {
        return domainParticipant;
    }

    protected InstanceHolder<Numeric> createNumericInstance(String metric_id, String vendor_metric_id) {
        return createNumericInstance(metric_id, vendor_metric_id, 0);
    }
    
    protected InstanceHolder<Numeric> createNumericInstance(String metric_id, String vendor_metric_id, int instance_id) {
        return createNumericInstance(metric_id, vendor_metric_id, instance_id, rosetta.MDC_DIM_DIMLESS.VALUE);
    }

    protected InstanceHolder<Numeric> createNumericInstance(String metric_id, String vendor_metric_id, int instance_id, String unit_id) {
        if (deviceIdentity == null || deviceIdentity.ice_id == null || "".equals(deviceIdentity.ice_id)) {
            throw new IllegalStateException("Please populate deviceIdentity.ice_id before calling createNumericInstance");
        }

        InstanceHolder<Numeric> holder = new InstanceHolder<Numeric>();
        holder.data = new Numeric();
        holder.data.ice_id = deviceIdentity.ice_id;
        holder.data.metric_id = metric_id;
        holder.data.vendor_metric_id = vendor_metric_id;
        holder.data.instance_id = instance_id;
        holder.data.unit_id = unit_id;
        holder.handle = numericDataWriter.register_instance(holder.data);

        registeredNumericInstances.add(holder);
        return holder;
    }

    protected InstanceHolder<ice.AlarmSettings> createAlarmSettingsInstance(String metric_id) {
        if (deviceIdentity == null || deviceIdentity.ice_id == null || "".equals(deviceIdentity.ice_id)) {
            throw new IllegalStateException("Please populate deviceIdentity.ice_id before calling createAlarmInstance");
        }

        InstanceHolder<ice.AlarmSettings> holder = new InstanceHolder<ice.AlarmSettings>();
        holder.data = new ice.AlarmSettings();
        holder.data.ice_id = deviceIdentity.ice_id;
        holder.data.metric_id = metric_id;
        holder.handle = alarmSettingsDataWriter.register_instance(holder.data);
        registeredAlarmSettingsInstances.add(holder);
        return holder;
    }

    protected InstanceHolder<ice.LocalAlarmSettingsObjective> createAlarmSettingsObjectiveInstance(String metric_id) {
        if (deviceIdentity == null || deviceIdentity.ice_id == null || "".equals(deviceIdentity.ice_id)) {
            throw new IllegalStateException("Please populate deviceIdentity.ice_id before calling createAlarmInstance");
        }

        InstanceHolder<ice.LocalAlarmSettingsObjective> holder = new InstanceHolder<ice.LocalAlarmSettingsObjective>();
        holder.data = new ice.LocalAlarmSettingsObjective();
        holder.data.ice_id = deviceIdentity.ice_id;
        holder.data.metric_id = metric_id;
        holder.handle = alarmSettingsObjectiveWriter.register_instance(holder.data);
        registeredAlarmSettingsObjectiveInstances.add(holder);
        return holder;
    }

    protected void unregisterAllInstances() {
        unregisterAllNumericInstances();
        unregisterAllSampleArrayInstances();
        unregisterAllAlarmSettingsInstances();
        unregisterAllAlarmSettingsObjectiveInstances();
        unregisterAllPatientAlertInstances();
        unregisterAllTechnicalAlertInstances();
    }
    
    private void unregisterAllAlertInstances(Set<String> old, Map<String, InstanceHolder<ice.Alert>> map, ice.AlertDataWriter writer) {
        for(String key : map.keySet().toArray(new String[0])) {
            writeAlert(old, map, writer, key, null);
        }
    }
    protected void unregisterAllPatientAlertInstances() {
        unregisterAllAlertInstances(oldPatientAlertInstances, patientAlertInstances, patientAlertWriter);
    }
    
    protected void unregisterAllTechnicalAlertInstances() {
        unregisterAllAlertInstances(oldTechnicalAlertInstances, technicalAlertInstances, technicalAlertWriter);
    }

    protected void unregisterAllAlarmSettingsObjectiveInstances() {
        while (!registeredAlarmSettingsObjectiveInstances.isEmpty()) {
            unregisterAlarmSettingsObjectiveInstance(registeredAlarmSettingsObjectiveInstances.get(0));
        }
    }

    protected void unregisterAllAlarmSettingsInstances() {
        while (!registeredAlarmSettingsInstances.isEmpty()) {
            unregisterAlarmSettingsInstance(registeredAlarmSettingsInstances.get(0));
        }
    }

    protected void unregisterAllNumericInstances() {
        while (!registeredNumericInstances.isEmpty()) {
            unregisterNumericInstance(registeredNumericInstances.get(0));
        }
    }

    protected void unregisterAllSampleArrayInstances() {
        while (!registeredSampleArrayInstances.isEmpty()) {
            unregisterSampleArrayInstance(registeredSampleArrayInstances.get(0));
        }
    }

    protected void unregisterNumericInstance(InstanceHolder<Numeric> holder) {
        if (null != holder) {
            registeredNumericInstances.remove(holder);
            numericDataWriter.unregister_instance(holder.data, holder.handle);
        }
    }

    protected void unregisterSampleArrayInstance(InstanceHolder<SampleArray> holder) {

        registeredSampleArrayInstances.remove(holder);

        sampleArrayDataWriter.unregister_instance(holder.data, holder.handle);
    }

    protected void unregisterAlarmSettingsInstance(InstanceHolder<ice.AlarmSettings> holder) {
        registeredAlarmSettingsInstances.remove(holder);
        alarmSettingsDataWriter.unregister_instance(holder.data, holder.handle);
    }

    protected void unregisterAlarmSettingsObjectiveInstance(InstanceHolder<ice.LocalAlarmSettingsObjective> holder) {
        registeredAlarmSettingsObjectiveInstances.remove(holder);
        alarmSettingsObjectiveWriter.unregister_instance(holder.data, holder.handle);
    }

    private final List<InstanceHolder<SampleArray>> registeredSampleArrayInstances = new ArrayList<InstanceHolder<SampleArray>>();
    private final List<InstanceHolder<Numeric>> registeredNumericInstances = new ArrayList<InstanceHolder<Numeric>>();
    private final List<InstanceHolder<ice.AlarmSettings>> registeredAlarmSettingsInstances = new ArrayList<InstanceHolder<ice.AlarmSettings>>();
    private final List<InstanceHolder<ice.LocalAlarmSettingsObjective>> registeredAlarmSettingsObjectiveInstances = new ArrayList<InstanceHolder<ice.LocalAlarmSettingsObjective>>();
    private final Map<String, InstanceHolder<ice.Alert>> patientAlertInstances = new HashMap<String, InstanceHolder<ice.Alert>>();
    private final Map<String, InstanceHolder<ice.Alert>> technicalAlertInstances = new HashMap<String, InstanceHolder<ice.Alert>>();
    private final Set<String> oldPatientAlertInstances = new HashSet<String>();
    private final Set<String> oldTechnicalAlertInstances = new HashSet<String>();


    protected InstanceHolder<SampleArray> createSampleArrayInstance(String metric_id, String vendor_metric_id, int instance_id, String unit_id, int frequency) {
        if (deviceIdentity == null || deviceIdentity.ice_id == null || "".equals(deviceIdentity.ice_id)) {
            throw new IllegalStateException("Please populate deviceIdentity.ice_id before calling createSampleArrayInstance");
        }

        InstanceHolder<SampleArray> holder = new InstanceHolder<SampleArray>();
        holder.data = new SampleArray();
        holder.data.ice_id = deviceIdentity.ice_id;
        holder.data.metric_id = metric_id;
        holder.data.vendor_metric_id = vendor_metric_id;
        holder.data.instance_id = instance_id;
        holder.data.unit_id = unit_id;
        holder.data.frequency = frequency;

        holder.handle = sampleArrayDataWriter.register_instance(holder.data);

        if(holder.handle.is_nil()) {
            log.warn("Unable to register instance " + holder.data);
            holder.handle = null;
        } else {
            registeredSampleArrayInstances.add(holder);
        }
        return holder;
    }

    protected void numericSample(InstanceHolder<Numeric> holder, float newValue, DeviceClock.Reading time) {
        holder.data.value = newValue;
        if(time.hasDeviceTime()) {
            Time_t t = DomainClock.toDDSTime(time.getDeviceTime());
            holder.data.device_time.sec = t.sec;
            holder.data.device_time.nanosec = t.nanosec;
        } else {
            holder.data.device_time.sec = 0;
            holder.data.device_time.nanosec = 0;
        }
        
        Time_t t = DomainClock.toDDSTime(time.getTime());
        holder.data.presentation_time.sec = t.sec;
        holder.data.presentation_time.nanosec = t.nanosec;
        
        numericDataWriter.write(holder.data, holder.handle);
    }

    protected void alarmSettingsSample(InstanceHolder<ice.AlarmSettings> holder, Float newLower, Float newUpper) {
        newLower = null == newLower ? Float.NEGATIVE_INFINITY : newLower;
        newUpper = null == newUpper ? Float.POSITIVE_INFINITY : newUpper;
        if(0 != Float.compare(newLower, holder.data.lower) || 0 != Float.compare(newUpper, holder.data.upper)) {
            holder.data.lower = newLower;
            holder.data.upper = newUpper;
            alarmSettingsDataWriter.write(holder.data, holder.handle);
        }
    }

    protected void alarmSettingsObjectiveSample(InstanceHolder<ice.LocalAlarmSettingsObjective> holder, float newLower, float newUpper) {
        if(0 != Float.compare(newLower, holder.data.lower) || 0 != Float.compare(newUpper, holder.data.upper)) {
            holder.data.lower = newLower;
            holder.data.upper = newUpper;
            alarmSettingsObjectiveWriter.write(holder.data, holder.handle);
        }
    }

    protected InstanceHolder<ice.AlarmSettings> alarmSettingsSample(InstanceHolder<ice.AlarmSettings> holder, Float newLower, Float newUpper,
            String metric_id) {
        if (holder != null && !holder.data.metric_id.equals(metric_id)) {
            unregisterAlarmSettingsInstance(holder);
            holder = null;
        }
        if (null == holder) {
            holder = createAlarmSettingsInstance(metric_id);
        }
        alarmSettingsSample(holder, newLower, newUpper);

        return holder;
    }

    protected InstanceHolder<ice.LocalAlarmSettingsObjective> alarmSettingsObjectiveSample(InstanceHolder<ice.LocalAlarmSettingsObjective> holder,
            Float newLower, Float newUpper, String metric_id) {
        if (holder != null && !holder.data.metric_id.equals(metric_id)) {
            unregisterAlarmSettingsObjectiveInstance(holder);
            holder = null;
        }
        if (null != newLower && null != newUpper) {
            if (null == holder) {
                holder = createAlarmSettingsObjectiveInstance(metric_id);
            }
            alarmSettingsObjectiveSample(holder, newLower, newUpper);
        } else {
            if (null != holder) {
                unregisterAlarmSettingsObjectiveInstance(holder);
                holder = null;
            }

        }

        return holder;
    }

    
    // For convenience
    protected InstanceHolder<Numeric> numericSample(InstanceHolder<Numeric> holder, Integer newValue, String metric_id, String vendor_metric_id, String unit_id, DeviceClock.Reading time) {
        return numericSample(holder, null == newValue ? null : newValue.floatValue(), metric_id, vendor_metric_id, unit_id, time);
    }

    // For convenience
    protected InstanceHolder<Numeric> numericSample(InstanceHolder<Numeric> holder, Integer newValue, String metric_id, String vendor_metric_id, int instance_id, DeviceClock.Reading time) {
        return numericSample(holder, null == newValue ? null : newValue.floatValue(), metric_id, vendor_metric_id, instance_id, rosetta.MDC_DIM_DIMLESS.VALUE, time);
    }
    
    // For convenience
    protected InstanceHolder<Numeric> numericSample(InstanceHolder<Numeric> holder, Integer newValue, String metric_id, String vendor_metric_id, int instance_id, String unit_id, DeviceClock.Reading time) {
        return numericSample(holder, null == newValue ? null : newValue.floatValue(), metric_id, vendor_metric_id, instance_id, unit_id, time);
    }

    protected InstanceHolder<Numeric> numericSample(InstanceHolder<Numeric> holder, Float newValue, String metric_id, String vendor_metric_id, String unit_id, DeviceClock.Reading time) {
        return numericSample(holder, newValue, metric_id, vendor_metric_id, 0, unit_id, time);
    }

    protected InstanceHolder<Numeric> numericSample(InstanceHolder<Numeric> holder, Float newValue, String metric_id, String vendor_metric_id, int instance_id, DeviceClock.Reading time) {
        return numericSample(holder, newValue, metric_id, vendor_metric_id, instance_id, rosetta.MDC_DIM_DIMLESS.VALUE, time);
    }

    protected InstanceHolder<Numeric> numericSample(InstanceHolder<Numeric> holder, Float newValue, String metric_id, String vendor_metric_id, int instance_id, String unit_id, DeviceClock.Reading time) {

        if (holder != null && (!holder.data.metric_id.equals(metric_id) || !holder.data.vendor_metric_id.equals(vendor_metric_id) || holder.data.instance_id != instance_id || !holder.data.unit_id.equals(unit_id))) {
            unregisterNumericInstance(holder);
            holder = null;
        }
        if (null != newValue) {
            if (null == holder) {
                holder = createNumericInstance(metric_id, vendor_metric_id, instance_id, unit_id);
            }
            numericSample(holder, newValue, time);
        } else {
            if (null != holder) {
                unregisterNumericInstance(holder);
                holder = null;
            }
        }
        return holder;
    }

    protected void writeDeviceAlert(String alertState) {
        alertState = null == alertState ? "" : alertState;
        if(null != deviceAlertConditionInstance) {
            if(!alertState.equals(deviceAlertConditionInstance.data.alert_state)) {
                deviceAlertConditionInstance.data.alert_state = alertState;
                deviceAlertConditionWriter.write(deviceAlertConditionInstance.data, deviceAlertConditionInstance.handle);
            }
        } else {
            throw new IllegalStateException("No deviceAlertCondition; have you called writeDeviceIdentity?");
        }
    }
    
    private void writeAlert(Set<String> old, Map<String, InstanceHolder<ice.Alert> > map, ice.AlertDataWriter writer, String key, String value) {
        InstanceHolder<ice.Alert> alert = map.get(key);
        if(null == value) {
            if(null != alert) {
                writer.unregister_instance(alert.data, alert.handle);
                map.remove(key);
            }
        } else {
            if (null == alert) {
                alert = new InstanceHolder<ice.Alert>();
                alert.data = (Alert) ice.Alert.create();
                alert.data.ice_id = deviceIdentity.ice_id;
                alert.data.identifier = key;
                alert.handle = writer.register_instance(alert.data);
                map.put(key, alert);
            }
            old.remove(key);
            if(!value.equals(alert.data.text)) { 
                alert.data.text = value;
                writer.write(alert.data, alert.handle);
            }
        }
    }
    
    protected void markOldPatientAlertInstances() {
        oldPatientAlertInstances.clear();
        oldPatientAlertInstances.addAll(patientAlertInstances.keySet());
    }
    
    protected void markOldTechnicalAlertInstances() {
        oldTechnicalAlertInstances.clear();
        oldTechnicalAlertInstances.addAll(technicalAlertInstances.keySet());
    }
    
    protected void clearOldPatientAlertInstances() {
        for(String key : oldPatientAlertInstances) {
            writePatientAlert(key, null);
        }
    }
    
    protected void clearOldTechnicalAlertInstances() {
        for(String key : oldTechnicalAlertInstances) {
            writeTechnicalAlert(key, null);
        }
    }
    
    protected void writePatientAlert(String key, String value) {
        writeAlert(oldPatientAlertInstances, patientAlertInstances, patientAlertWriter, key, value);
    }
    protected void writeTechnicalAlert(String key, String value) {
        writeAlert(oldTechnicalAlertInstances, technicalAlertInstances, technicalAlertWriter, key, value);
    }


    protected InstanceHolder<SampleArray> sampleArraySample(InstanceHolder<SampleArray> holder,
                                                            Number[] newValues,
                                                            String metric_id, String vendor_metric_id, String unit_id, int frequency,
                                                            DeviceClock.Reading timestamp) {
        return sampleArraySample(holder, newValues, metric_id, vendor_metric_id, 0, unit_id, frequency, timestamp);
    }


    protected InstanceHolder<ice.SampleArray> sampleArraySample(InstanceHolder<ice.SampleArray> holder,
                                                                Number[] newValues, int len,
                                                                String metric_id, String vendor_metric_id, int instance_id, String unit_id, int frequency,
                                                                DeviceClock.Reading timestamp) {
        return sampleArraySample(holder, new ArrayContainer<>(newValues, len), metric_id, vendor_metric_id, instance_id, unit_id, frequency, timestamp);
    }

    protected InstanceHolder<ice.SampleArray> sampleArraySample(InstanceHolder<ice.SampleArray> holder,
                                                                Number[] newValues,
                                                                String metric_id, String vendor_metric_id, int instance_id, String unit_id, int frequency,
                                                                DeviceClock.Reading timestamp) {
        return sampleArraySample(holder, new ArrayContainer<>(newValues), metric_id, vendor_metric_id, instance_id, unit_id, frequency, timestamp);
    }

    protected InstanceHolder<ice.SampleArray> sampleArraySample(InstanceHolder<ice.SampleArray> holder,
                                                                Collection<Number> newValues,
                                                                String metric_id, String vendor_metric_id, int instance_id, String unit_id, int frequency,
                                                                DeviceClock.Reading timestamp) {
        return sampleArraySample(holder, new CollectionContainer<>(newValues), metric_id, vendor_metric_id, instance_id, unit_id, frequency, timestamp);
    }

    private InstanceHolder<SampleArray> sampleArraySample(InstanceHolder<SampleArray> holder,
                                                          NullSaveContainer<Number> newValues,
                                                          String metric_id, String vendor_metric_id, int instance_id, String unit_id, int frequency,
                                                          DeviceClock.Reading timestamp) {

        holder = ensureHolderConsistency(holder, metric_id, vendor_metric_id, instance_id, unit_id, frequency);

        if (!newValues.isNull()) {
            // Call this now so that resolution of instance registration timestamp
            // is reduced
            timestamp = timestamp.refineResolutionForFrequency(frequency, newValues.size());
            if (null == holder) {
                holder = createSampleArrayInstance(metric_id, vendor_metric_id, instance_id, unit_id, frequency);
            }
            sampleArraySample(holder, newValues, timestamp);
        } else {
            if (holder != null) {
                unregisterSampleArrayInstance(holder);
                holder = null;
            }
        }
        return holder;
    }

    protected void sampleArraySample(InstanceHolder<SampleArray> holder, Number[] newValues, DeviceClock.Reading timestamp) {
        sampleArraySample(holder, new ArrayContainer<>(newValues), timestamp);
    }

    protected void sampleArraySample(InstanceHolder<SampleArray> holder, Collection<Number>  newValues, DeviceClock.Reading timestamp) {
        sampleArraySample(holder, new CollectionContainer<>(newValues), timestamp);
    }

    private void sampleArraySample(InstanceHolder<ice.SampleArray> holder, NullSaveContainer<Number> newValues, DeviceClock.Reading deviceTimestamp) {
        fill(holder, newValues);
        publish(holder, deviceTimestamp);
    }

    private void fill(InstanceHolder<SampleArray> holder, NullSaveContainer<Number> newValues) {
        holder.data.values.userData.clear();
        if(!newValues.isNull()) {
            Iterator<Number> iter = newValues.iterator();
            while (iter.hasNext()) {
                Number n = iter.next();
                holder.data.values.userData.addFloat(n.floatValue());
            }
        }
    }

    private void publish(InstanceHolder<ice.SampleArray> holder, DeviceClock.Reading deviceTimestamp) {

        if (deviceTimestamp.hasDeviceTime()) {
            Time_t t = DomainClock.toDDSTime(deviceTimestamp.getDeviceTime());
            holder.data.device_time.sec = t.sec;
            holder.data.device_time.nanosec = t.nanosec;
        } else {

            holder.data.device_time.sec = 0;
            holder.data.device_time.nanosec = 0;
        }

        DeviceClock.Reading adjusted = deviceTimestamp.refineResolutionForFrequency(holder.data.frequency,
                                                                                    holder.data.values.userData.size());
        
        DomainClock.toDDSTime(adjusted.getTime(), holder.data.presentation_time);

        sampleArrayDataWriter.write(holder.data,
                                                holder.handle==null?InstanceHandle_t.HANDLE_NIL:holder.handle);
    }

    private InstanceHolder<SampleArray> ensureHolderConsistency(InstanceHolder<SampleArray> holder,
                                                                String metric_id, String vendor_metric_id, int instance_id, 
                                                                String unit_id, int frequency) {

        if (null != holder && (!holder.data.metric_id.equals(metric_id) ||
                               !holder.data.vendor_metric_id.equals(vendor_metric_id) ||
                                holder.data.instance_id != instance_id ||
                                holder.data.frequency != frequency ||
                               !holder.data.unit_id.equals(unit_id))) {

            unregisterSampleArrayInstance(holder);
            holder = null;
        }
        return holder;
    }

    protected String iconResourceName() {
        return null;
    }

    // MIKEFIX
    protected boolean iconFromResource(DeviceIdentity di, String iconResourceName) throws IOException {
        if (null == iconResourceName) {
            di.icon.content_type = "";
            di.icon.image.clear();
            return true;
        }

        InputStream is = getClass().getResourceAsStream(iconResourceName);
        if (null != is) {
            try {
                {
                    byte[] xfer = new byte[1024];
                    int len = is.read(xfer);

                    di.icon.image.userData.clear();

                    while (len >= 0) {
                        di.icon.image.userData.addAllByte(xfer, 0, len);
                        len = is.read(xfer);
                    }
                    is.close();
                }
                return true;
            } catch (Exception e) {
                log.error("error in iconUpdateFromResource", e);
            }
        }
        return false;
    }

    private int threadOrdinal = 0;

    @Override
    public Thread newThread(Runnable r) {
        Thread t = new Thread(threadGroup, r, "AbstractDevice-" + (++threadOrdinal));
        t.setDaemon(true);
        return t;
    }

    public void shutdown() {
        // TODO there is nothing preventing inheritors from interacting with
        // these objects as (and after) they are destroyed.
        // TODO there isn't a coherent way to dispose of the instances an
        // inheritor may have registered... perhaps they should be responsible
        // in their override of shutdown?

        if(timeManager!=null) {
            timeManager.stop();
        }
        
        if (null != alarmSettingsObjectiveCondition) {
            eventLoop.removeHandler(alarmSettingsObjectiveCondition);
            alarmSettingsObjectiveReader.delete_readcondition(alarmSettingsObjectiveCondition);
            alarmSettingsObjectiveCondition = null;
        }

        subscriber.delete_datareader(alarmSettingsObjectiveReader);

        publisher.delete_datawriter(alarmSettingsObjectiveWriter);
        domainParticipant.delete_topic(alarmSettingsObjectiveTopic);
        ice.LocalAlarmSettingsObjectiveTypeSupport.unregister_type(domainParticipant, ice.LocalAlarmSettingsObjectiveTypeSupport.get_type_name());

        publisher.delete_datawriter(alarmSettingsDataWriter);
        domainParticipant.delete_topic(alarmSettingsTopic);
        ice.AlarmSettingsTypeSupport.unregister_type(domainParticipant, ice.AlarmSettingsTypeSupport.get_type_name());

        publisher.delete_datawriter(sampleArrayDataWriter);
        domainParticipant.delete_topic(sampleArrayTopic);
        SampleArrayTypeSupport.unregister_type(domainParticipant, SampleArrayTypeSupport.get_type_name());

        publisher.delete_datawriter(numericDataWriter);
        domainParticipant.delete_topic(numericTopic);
        NumericTypeSupport.unregister_type(domainParticipant, NumericTypeSupport.get_type_name());

        publisher.delete_datawriter(deviceIdentityWriter);
        domainParticipant.delete_topic(deviceIdentityTopic);
        DeviceIdentityTypeSupport.unregister_type(domainParticipant, DeviceIdentityTypeSupport.get_type_name());
        
        publisher.delete_datawriter(deviceAlertConditionWriter);
        domainParticipant.delete_topic(deviceAlertConditionTopic);
        ice.DeviceAlertConditionTypeSupport.unregister_type(domainParticipant, ice.DeviceAlertConditionTypeSupport.get_type_name());

        publisher.delete_datawriter(patientAlertWriter);
        domainParticipant.delete_topic(patientAlertTopic);
        
        publisher.delete_datawriter(technicalAlertWriter);
        domainParticipant.delete_topic(technicalAlertTopic);
        ice.AlertTypeSupport.unregister_type(domainParticipant, ice.AlertTypeSupport.get_type_name());

        domainParticipant.delete_publisher(publisher);
        domainParticipant.delete_subscriber(subscriber);

        domainParticipant.delete_contained_entities();

        DomainParticipantFactory.get_instance().delete_participant(domainParticipant);

        executor.shutdown();
        log.info("AbstractDevice shutdown complete");
    }

    public AbstractDevice(int domainId, EventLoop eventLoop) {
        DomainParticipantQos pQos = new DomainParticipantQos();
        DomainParticipantFactory.get_instance().get_default_participant_qos(pQos);
        pQos.participant_name.name = "Device";
        
        domainParticipant = DomainParticipantFactory.get_instance().create_participant(domainId, pQos, null, StatusKind.STATUS_MASK_NONE);
        publisher = domainParticipant.create_publisher(DomainParticipant.PUBLISHER_QOS_DEFAULT, null, StatusKind.STATUS_MASK_NONE);
        subscriber = domainParticipant.create_subscriber(DomainParticipant.SUBSCRIBER_QOS_DEFAULT, null, StatusKind.STATUS_MASK_NONE);

        timestampFactory = new DomainClock(domainParticipant);

        partitionAssignmentController = new PartitionAssignmentController(publisher, subscriber);

        DeviceIdentityTypeSupport.register_type(domainParticipant, DeviceIdentityTypeSupport.get_type_name());
        deviceIdentityTopic = domainParticipant.create_topic(ice.DeviceIdentityTopic.VALUE, DeviceIdentityTypeSupport.get_type_name(),
                DomainParticipant.TOPIC_QOS_DEFAULT, null, StatusKind.STATUS_MASK_NONE);
        deviceIdentityWriter = (DeviceIdentityDataWriter) publisher.create_datawriter_with_profile(deviceIdentityTopic, QosProfiles.ice_library,
                QosProfiles.device_identity, null, StatusKind.STATUS_MASK_NONE);
        if (null == deviceIdentityWriter) {
            throw new RuntimeException("deviceIdentityWriter not created");
        }

        deviceIdentity  = (new DeviceIdentityBuilder()).osName().softwareRev().withIcon(this, iconResourceName()).build();

        NumericTypeSupport.register_type(domainParticipant, NumericTypeSupport.get_type_name());
        numericTopic = domainParticipant.create_topic(ice.NumericTopic.VALUE, NumericTypeSupport.get_type_name(),
                DomainParticipant.TOPIC_QOS_DEFAULT, null, StatusKind.STATUS_MASK_NONE);
        numericDataWriter = (NumericDataWriter) publisher.create_datawriter_with_profile(numericTopic, QosProfiles.ice_library,
                QosProfiles.numeric_data, null, StatusKind.STATUS_MASK_NONE);
        if (null == numericDataWriter) {
            throw new RuntimeException("numericDataWriter not created");
        }

        SampleArrayTypeSupport.register_type(domainParticipant, SampleArrayTypeSupport.get_type_name());
        sampleArrayTopic = domainParticipant.create_topic(ice.SampleArrayTopic.VALUE, SampleArrayTypeSupport.get_type_name(),
                DomainParticipant.TOPIC_QOS_DEFAULT, null, StatusKind.STATUS_MASK_NONE);
        sampleArrayDataWriter = (SampleArrayDataWriter) publisher.create_datawriter_with_profile(sampleArrayTopic, QosProfiles.ice_library,
                QosProfiles.waveform_data, null, StatusKind.STATUS_MASK_NONE);
        if (null == sampleArrayDataWriter) {
            throw new RuntimeException("sampleArrayDataWriter not created");
        }

        ice.AlarmSettingsTypeSupport.register_type(domainParticipant, ice.AlarmSettingsTypeSupport.get_type_name());
        alarmSettingsTopic = domainParticipant.create_topic(ice.AlarmSettingsTopic.VALUE, ice.AlarmSettingsTypeSupport.get_type_name(),
                DomainParticipant.TOPIC_QOS_DEFAULT, null, StatusKind.STATUS_MASK_NONE);
        alarmSettingsDataWriter = (ice.AlarmSettingsDataWriter) publisher.create_datawriter_with_profile(alarmSettingsTopic, QosProfiles.ice_library,
                QosProfiles.state, null, StatusKind.STATUS_MASK_NONE);

        ice.LocalAlarmSettingsObjectiveTypeSupport.register_type(domainParticipant, ice.LocalAlarmSettingsObjectiveTypeSupport.get_type_name());
        alarmSettingsObjectiveTopic = domainParticipant.create_topic(ice.LocalAlarmSettingsObjectiveTopic.VALUE,
                ice.LocalAlarmSettingsObjectiveTypeSupport.get_type_name(), DomainParticipant.TOPIC_QOS_DEFAULT, null, StatusKind.STATUS_MASK_NONE);
        alarmSettingsObjectiveWriter = (LocalAlarmSettingsObjectiveDataWriter) publisher.create_datawriter_with_profile(alarmSettingsObjectiveTopic,
                QosProfiles.ice_library, QosProfiles.state, null, StatusKind.STATUS_MASK_NONE);

        TopicDescription alarmSettingsObjectiveTopic = TopicUtil.lookupOrCreateTopic(domainParticipant, ice.GlobalAlarmSettingsObjectiveTopic.VALUE,
                ice.GlobalAlarmSettingsObjectiveTypeSupport.class);
        alarmSettingsObjectiveReader = (ice.GlobalAlarmSettingsObjectiveDataReader) subscriber.create_datareader_with_profile(
                alarmSettingsObjectiveTopic, QosProfiles.ice_library, QosProfiles.state, null, StatusKind.STATUS_MASK_NONE);

        ice.DeviceAlertConditionTypeSupport.register_type(domainParticipant, ice.DeviceAlertConditionTypeSupport.get_type_name());
        deviceAlertConditionTopic = domainParticipant.create_topic(ice.DeviceAlertConditionTopic.VALUE,
                ice.DeviceAlertConditionTypeSupport.get_type_name(), DomainParticipant.TOPIC_QOS_DEFAULT, null, StatusKind.STATUS_MASK_NONE);
        deviceAlertConditionWriter = (ice.DeviceAlertConditionDataWriter) publisher.create_datawriter_with_profile(deviceAlertConditionTopic,
                QosProfiles.ice_library, QosProfiles.state, null, StatusKind.STATUS_MASK_NONE);

        ice.AlertTypeSupport.register_type(domainParticipant, ice.AlertTypeSupport.get_type_name());
        patientAlertTopic = domainParticipant.create_topic(ice.PatientAlertTopic.VALUE, ice.AlertTypeSupport.get_type_name(),
                DomainParticipant.TOPIC_QOS_DEFAULT, null, StatusKind.STATUS_MASK_NONE);
        patientAlertWriter = (ice.AlertDataWriter) publisher.create_datawriter_with_profile(patientAlertTopic, QosProfiles.ice_library,
                QosProfiles.state, null, StatusKind.STATUS_MASK_NONE);
        
        technicalAlertTopic = domainParticipant.create_topic(ice.TechnicalAlertTopic.VALUE, ice.AlertTypeSupport.get_type_name(),
                DomainParticipant.TOPIC_QOS_DEFAULT, null, StatusKind.STATUS_MASK_NONE);
        technicalAlertWriter = (ice.AlertDataWriter) publisher.create_datawriter_with_profile(technicalAlertTopic, QosProfiles.ice_library,
                QosProfiles.state, null, StatusKind.STATUS_MASK_NONE);
        
        
        
        threadGroup = new ThreadGroup(Thread.currentThread().getThreadGroup(), "AbstractDevice") {
            @Override
            public void uncaughtException(Thread t, Throwable e) {
                log.error("Thrown by " + t.toString(), e);
                super.uncaughtException(t, e);
            }
        };

        threadGroup.setDaemon(true);
        this.eventLoop = eventLoop;
        
        executor.scheduleWithFixedDelay(new Runnable() {
            public void run() {
                partitionAssignmentController.checkForPartitionFile();
            }
        }, 1000L, 5000L, TimeUnit.MILLISECONDS);
    }


    /**
     * @return an instance of the device clock that should be used in stamping messages. Fall-back implementation
     * will supply dds time. If device maintains its own notion of the clock, it could use DeviceClock.ComboClock wrapper
     * to provide clock reading that would contain multiple values.
     **/

    protected final DeviceClock getClockProvider()
    {
        return timestampFactory;
    }

    public void setAlarmSettings(ice.GlobalAlarmSettingsObjective obj) {

    }

    public void unsetAlarmSettings(String metricId) {

    }

    private Map<InstanceHandle_t, String> instanceMetrics = new HashMap<InstanceHandle_t, String>();

    protected void writeDeviceIdentity() {
        if (null == deviceIdentity.ice_id || "".equals(deviceIdentity.ice_id)) {
            throw new IllegalStateException("cannot write deviceIdentity without an ice_id");
        }
        registerForManagement();
        
        if(null == timeManager) {
            timeManager = new TimeManager(publisher, subscriber, deviceIdentity.ice_id, "Device");
            timeManager.start();
        }

        if (null == deviceIdentityHandle) {
            deviceIdentityHandle = deviceIdentityWriter.register_instance(deviceIdentity);
        }
        deviceIdentityWriter.write(deviceIdentity, deviceIdentityHandle);
        
        ice.DeviceAlertCondition alertCondition = (ice.DeviceAlertCondition) ice.DeviceAlertCondition.create();
        alertCondition.ice_id = deviceIdentity.ice_id;
        alertCondition.alert_state = "";
        InstanceHandle_t deviceAlertHandle = deviceAlertConditionWriter.register_instance(alertCondition);
        deviceAlertConditionInstance = new InstanceHolder<ice.DeviceAlertCondition>(alertCondition, deviceAlertHandle);

        if (null == alarmSettingsObjectiveCondition) {
            final SampleInfoSeq info_seq = new SampleInfoSeq();
            final ice.GlobalAlarmSettingsObjectiveSeq data_seq = new ice.GlobalAlarmSettingsObjectiveSeq();
            eventLoop.addHandler(
                    alarmSettingsObjectiveCondition = alarmSettingsObjectiveReader.create_readcondition(SampleStateKind.NOT_READ_SAMPLE_STATE,
                            ViewStateKind.ANY_VIEW_STATE, InstanceStateKind.ANY_INSTANCE_STATE), new ConditionHandler() {
                        @Override
                        public void conditionChanged(Condition condition) {
                            try {
                                alarmSettingsObjectiveReader.read_w_condition(data_seq, info_seq, ResourceLimitsQosPolicy.LENGTH_UNLIMITED,
                                        (ReadCondition) condition);
                                for (int i = 0; i < data_seq.size(); i++) {
                                    SampleInfo si = (SampleInfo) info_seq.get(i);
                                    ice.GlobalAlarmSettingsObjective obj = (ice.GlobalAlarmSettingsObjective) data_seq.get(i);

                                    if (0 != (si.view_state & ViewStateKind.NEW_VIEW_STATE) && si.valid_data) {
                                        log.debug("Handle for metric_id=" + obj.metric_id + " is " + si.instance_handle);
                                        instanceMetrics.put(new InstanceHandle_t(si.instance_handle), obj.metric_id);
                                    }

                                    if (0 != (si.instance_state & InstanceStateKind.ALIVE_INSTANCE_STATE)) {
                                        if (si.valid_data) {
                                            log.debug("Setting " + obj.metric_id + " to [ " + obj.lower + " , " + obj.upper + "]");
                                            setAlarmSettings(obj);
                                        }
                                    } else {
                                        obj = new ice.GlobalAlarmSettingsObjective();
                                        log.warn("Unsetting handle " + si.instance_handle);
                                        // TODO 1-Oct-2013 JP This call to
                                        // get_key_value fails consistently on
                                        // ARM platforms
                                        // so I'm tracking instances externally
                                        // for the time being
                                        // alarmSettingsObjectiveReader.get_key_value(obj,
                                        // si.instance_handle);
                                        String metricId = instanceMetrics.get(si.instance_handle);
                                        log.debug("Unsetting " + metricId);
                                        if (null != metricId) {
                                            unsetAlarmSettings(metricId);
                                        }

                                    }
                                }
                            } catch (RETCODE_NO_DATA noData) {

                            } finally {
                                alarmSettingsObjectiveReader.return_loan(data_seq, info_seq);
                            }
                        }
                    });
        }
    }
    
    @Override
    public String getManufacturer() {
        return null == deviceIdentity ? null : deviceIdentity.manufacturer;
    }

    @Override
    public String getModel() {
        return null == deviceIdentity ? null : deviceIdentity.model;
    }

    @Override
    public String getUniqueDeviceIdentifier() {
        return null == deviceIdentity ? null : deviceIdentity.ice_id;
    }

    private ObjectInstance objInstance;

    private void registerForManagement() {
        if (null == objInstance) {
            try {
                objInstance = ManagementFactory.getPlatformMBeanServer().registerMBean(
                        this,
                        new ObjectName(AbstractDevice.class.getPackage().getName() + ":type=" + AbstractDevice.class.getSimpleName() + ",name="
                                + getUniqueDeviceIdentifier()));
            } catch (JMException e) {
                log.warn("Unable to register with JMX", e);
            }
        }
    }

    @Override
    public String[] getPartition() {
        return partitionAssignmentController.getPartition();
    }

    @Override
    public void addPartition(String partition) {
        partitionAssignmentController.addPartition(partition);
    }

    @Override
    public void removePartition(String partition) {
        partitionAssignmentController.removePartition(partition);
    }

    @Override
    public void setPartition(String[] partition) {
        partitionAssignmentController.setPartition(partition);
    }

    protected void iconOrBlank(String model, String icon) {
        (new DeviceIdentityBuilder(deviceIdentity)).withIcon(this, icon).model(model).build();
        writeDeviceIdentity();
    }

    static interface NullSaveContainer<T>
    {
        boolean isNull();
        Iterator<T> iterator();
        int size();
    }

    static class CollectionContainer<T> implements NullSaveContainer<T>
    {
        CollectionContainer(Collection<T> dt) {
            this.dt = dt;
        }

        private final Collection<T> dt;

        public boolean isNull() { return dt == null; }
        public Iterator<T> iterator() { return dt==null? Collections.<T>emptyIterator() : dt.iterator(); }
        public int size() { return dt == null ? 0 : dt.size(); }
    }

    static class ArrayContainer<T> implements NullSaveContainer<T>
    {
        ArrayContainer(T[] dt) {
            this(dt, dt == null ? 0 : dt.length);
        }

        ArrayContainer(T[] dt, int l) {
            this.dt = dt;
            this.l = l;
        }

        private final T[] dt;
        private final int l;

        public boolean isNull() { return dt == null; }
        public Iterator<T> iterator() { return new ArrayIterator<T>(dt, l); }
        public int size() { return l; }
    }

    static class ArrayIterator<T> implements Iterator<T>
    {
        final T[] data;
        final int l;

        int currentIdx=0;

        public ArrayIterator(T[] data, int l) {
            this.data = data;
            this.l = l;
        }

        @Override
        public boolean hasNext() {
            return currentIdx<l;
        }

        @Override
        public T next() {
            return data[currentIdx++];
        }

        @Override
        public void remove() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void forEachRemaining(Consumer<? super T> action) {
            throw new UnsupportedOperationException();
        }
    }
}
