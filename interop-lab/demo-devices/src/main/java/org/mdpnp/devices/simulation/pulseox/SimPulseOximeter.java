/*******************************************************************************
 * Copyright (c) 2012 MD PnP Program.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the GNU Public License v3.0
 * which accompanies this distribution, and is available at
 * http://www.gnu.org/licenses/gpl.html
 ******************************************************************************/
package org.mdpnp.devices.simulation.pulseox;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.mdpnp.devices.EventLoop;
import org.mdpnp.devices.simulation.AbstractSimulatedConnectedDevice;
import org.omg.dds.core.Time;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SimPulseOximeter extends AbstractSimulatedConnectedDevice {

    protected final InstanceHolder<ice.Numeric> pulse;
    protected final InstanceHolder<ice.Numeric> SpO2;
    protected final InstanceHolder<ice.SampleArray> pleth;

    private final static Logger log = LoggerFactory.getLogger(SimPulseOximeter.class);

    private class MySimulatedPulseOximeter extends SimulatedPulseOximeter {
        @Override
        protected void receivePulseOx(long timestamp, int heartRate, int SpO2, Number[] plethValues, double msPerSample) {
            Time sampleTime = Time.newTime(timestamp, TimeUnit.MILLISECONDS, eventLoop.getServiceEnvironment());
            try {
                numericSample(pulse, heartRate, sampleTime);
                numericSample(SimPulseOximeter.this.SpO2, SpO2, sampleTime);
                sampleArraySample(pleth, plethValues, (int) msPerSample, sampleTime);
            } catch(TimeoutException te) {
                log.error("Timed out writing data", te);
            }
        }
    }

    private final MySimulatedPulseOximeter pulseox = new MySimulatedPulseOximeter();


    @Override
    public void connect(String str) {
        pulseox.connect(executor);
        super.connect(str);
    }

    @Override
    public void disconnect() {
        pulseox.disconnect();
        super.disconnect();
    }

    public SimPulseOximeter(int domainId, EventLoop eventLoop) throws TimeoutException {
        super(domainId, eventLoop);

        pulse = createNumericInstance(rosetta.MDC_PULS_OXIM_PULS_RATE.value);
        SpO2 = createNumericInstance(rosetta.MDC_PULS_OXIM_SAT_O2.value);
        pleth = createSampleArrayInstance(rosetta.MDC_PULS_OXIM_PLETH.value);

        deviceIdentity.model = "Pulse Ox (Simulated)";
        writeDeviceIdentity();
    }

    @Override
    protected String iconResourceName() {
        return "pulseox.png";
    }
}
