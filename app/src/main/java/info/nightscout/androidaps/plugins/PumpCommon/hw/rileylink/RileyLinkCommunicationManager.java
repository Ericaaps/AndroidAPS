package info.nightscout.androidaps.plugins.PumpCommon.hw.rileylink;

import android.content.Context;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import info.nightscout.androidaps.plugins.PumpCommon.data.PumpStatus;
import info.nightscout.androidaps.plugins.PumpCommon.hw.rileylink.ble.RFSpy;
import info.nightscout.androidaps.plugins.PumpCommon.hw.rileylink.ble.data.FrequencyScanResults;
import info.nightscout.androidaps.plugins.PumpCommon.hw.rileylink.ble.data.FrequencyTrial;
import info.nightscout.androidaps.plugins.PumpCommon.hw.rileylink.ble.data.RFSpyResponse;
import info.nightscout.androidaps.plugins.PumpCommon.hw.rileylink.ble.data.RadioPacket;
import info.nightscout.androidaps.plugins.PumpCommon.hw.rileylink.ble.data.RadioResponse;
import info.nightscout.androidaps.plugins.PumpCommon.hw.rileylink.ble.defs.RLMessage;
import info.nightscout.androidaps.plugins.PumpCommon.hw.rileylink.ble.defs.RLMessageType;
import info.nightscout.androidaps.plugins.PumpCommon.hw.rileylink.ble.defs.RileyLinkTargetFrequency;
import info.nightscout.androidaps.plugins.PumpCommon.hw.rileylink.defs.RileyLinkError;
import info.nightscout.androidaps.plugins.PumpCommon.hw.rileylink.defs.RileyLinkServiceState;
import info.nightscout.androidaps.plugins.PumpCommon.hw.rileylink.service.RileyLinkServiceData;
import info.nightscout.androidaps.plugins.PumpCommon.utils.ByteUtil;
import info.nightscout.androidaps.plugins.PumpMedtronic.comm.message.PumpMessage;
import info.nightscout.androidaps.plugins.PumpMedtronic.defs.PumpDeviceState;
import info.nightscout.androidaps.plugins.PumpMedtronic.util.MedtronicConst;
import info.nightscout.androidaps.plugins.PumpMedtronic.util.MedtronicUtil;
import info.nightscout.utils.SP;


/**
 * This is abstract class for RileyLink Communication, this one needs to be extended by specific "Pump" class.
 * <p>
 * Created by andy on 5/10/18.
 */
public abstract class RileyLinkCommunicationManager {

    private static final Logger LOG = LoggerFactory.getLogger(RileyLinkCommunicationManager.class);

    protected final RFSpy rfspy;
    protected final Context context;

    private double[] scanFrequencies;

    protected int receiverDeviceAwakeForMinutes = 1; // override this in constructor of specific implementation
    protected String receiverDeviceID; // String representation of receiver device (ex. Pump (xxxxxx) or Pod (yyyyyy))
    protected long lastGoodReceiverCommunicationTime = 0;
    protected PumpStatus pumpStatus;
    protected RileyLinkServiceData rileyLinkServiceData;

    // internal flag
    private boolean showPumpMessages = true;
    protected RileyLinkTargetFrequency targetFrequency;


    public RileyLinkCommunicationManager(Context context, RFSpy rfspy, RileyLinkTargetFrequency targetFrequency) {
        this.context = context;
        this.rfspy = rfspy;
        this.targetFrequency = targetFrequency;
        this.scanFrequencies = targetFrequency.getScanFrequencies();
        this.rileyLinkServiceData = RileyLinkUtil.getRileyLinkServiceData();
        RileyLinkUtil.setRileyLinkCommunicationManager(this);

        configurePumpSpecificSettings();
    }


    protected abstract void configurePumpSpecificSettings();


    protected PumpMessage sendAndListen(RLMessage msg) {
        return sendAndListen(msg, 4000); // 2000
    }


    private int timeoutCount = 0;


    // All pump communications go through this function.
    protected PumpMessage sendAndListen(RLMessage msg, int timeout_ms) {

        if (showPumpMessages) {
            LOG.info("Sent:" + ByteUtil.shortHexString(msg.getTxData()));
        }

        RFSpyResponse rfSpyResponse = rfspy.transmitThenReceive(new RadioPacket(msg.getTxData()), timeout_ms);
        PumpMessage rval = new PumpMessage(rfSpyResponse.getRadioResponse().getPayload());
        if (rval.isValid()) {
            // Mark this as the last time we heard from the pump.
            rememberLastGoodPumpCommunicationTime();
        } else {
            LOG.warn("Response is invalid. !!! - ", rfSpyResponse.wasInterrupted(), rfSpyResponse.wasTimeout());

            if (rfSpyResponse.wasTimeout()) {
                timeoutCount++;

                if (timeoutCount >= 5) {
                    RileyLinkUtil.setServiceState(RileyLinkServiceState.PumpConnectorError, RileyLinkError.NoContactWithDevice);
                    timeoutCount = 0;
                    tuneForPump();
                    //RileyLinkUtil.sendBroadcastMessage(RileyLinkConst.IPC.MSG_PUMP_quickTune);
                }
            }

        }

        if (showPumpMessages) {
            LOG.info("Received:" + ByteUtil.shortHexString(rfSpyResponse.getRadioResponse().getPayload()));
        }
        return rval;
    }


    //    /**
    //     * For set commands we use this method (it just sends data and returns ACK or NAK)
    //     *
    //     * @param msg
    //     * @param timeoutMs
    //     * @return
    //     */
    //    protected PumpMessage send(RLMessage msg, int timeoutMs) {
    //
    //        // FIXME untested
    //        if (showPumpMessages) {
    //            LOG.info("Sent:" + ByteUtil.shortHexString(msg.getTxData()));
    //        }
    //
    //        RFSpyResponse resp = rfspy.transmit(new RadioPacket(msg.getTxData()));
    //        PumpMessage rval = new PumpMessage(resp.getRadioResponse().getPayload());
    //        if (rval.isValid()) {
    //            // Mark this as the last time we heard from the pump.
    //            rememberLastGoodPumpCommunicationTime();
    //        } else {
    //            LOG.warn("Response is invalid. !!!");
    //        }
    //
    //        if (showPumpMessages) {
    //            LOG.info("Received:" + ByteUtil.shortHexString(resp.getRadioResponse().getPayload()));
    //        }
    //        return rval;
    //    }


    public void wakeUp(boolean force) {
        wakeUp(receiverDeviceAwakeForMinutes, force);


    }


    long nextWakeUpRequired = 0L;


    public int getNotConnectedCount() {
        return rfspy != null ? rfspy.notConnectedCount : 0;
    }


    // FIXME change wakeup
    // TODO we might need to fix this. Maybe make pump awake for shorter time (battery factor for pump) - Andy
    public void wakeUp(int duration_minutes, boolean force) {
        // If it has been longer than n minutes, do wakeup.  Otherwise assume pump is still awake.
        // **** FIXME: this wakeup doesn't seem to work well... must revisit
        //receiverDeviceAwakeForMinutes = duration_minutes;

        MedtronicUtil.setPumpDeviceState(PumpDeviceState.WakingUp);

        if (force)
            nextWakeUpRequired = 0L;

        if (System.currentTimeMillis() > nextWakeUpRequired) {
            LOG.info("Waking pump...");

            byte[] pumpMsgContent = createPumpMessageContent(RLMessageType.ReadSimpleData); // simple
            RFSpyResponse resp = rfspy.transmitThenReceive(new RadioPacket(pumpMsgContent), (byte) 0, (byte) 200, (byte) 0, (byte) 0, 25000, (byte) 0);
            LOG.info("wakeup: raw response is " + ByteUtil.shortHexString(resp.getRaw()));

            // FIXME wakeUp successful !!!!!!!!!!!!!!!!!!

            nextWakeUpRequired = System.currentTimeMillis() + (receiverDeviceAwakeForMinutes * 60 * 1000);
        } else {
            LOG.trace("Last pump communication was recent, not waking pump.");
        }


        //        long lastGoodPlus = getLastGoodReceiverCommunicationTime() + (receiverDeviceAwakeForMinutes * 60 * 1000);
        //
        //        if (System.currentTimeMillis() > lastGoodPlus || force) {
        //            LOG.info("Waking pump...");
        //
        //            byte[] pumpMsgContent = createPumpMessageContent(RLMessageType.PowerOn);
        //            RFSpyResponse resp = rfspy.transmitThenReceive(new RadioPacket(pumpMsgContent), (byte) 0, (byte) 200, (byte) 0, (byte) 0, 15000, (byte) 0);
        //            LOG.info("wakeup: raw response is " + ByteUtil.shortHexString(resp.getRaw()));
        //        } else {
        //            LOG.trace("Last pump communication was recent, not waking pump.");
        //        }
    }


    public void setRadioFrequencyForPump(double freqMHz) {
        rfspy.setBaseFrequency(freqMHz);
    }


    public double tuneForPump() {
        return scanForPump(scanFrequencies);
    }


    /**
     * If user changes pump and one pump is running in US freq, and other in WW, then previously set frequency would be invalid,
     * so we would need to retune. This checks that saved frequency is corrent range.
     *
     * @param frequency
     * @return
     */
    public boolean isValidFrequency(double frequency) {
        return (this.scanFrequencies[0] <= frequency && this.scanFrequencies[scanFrequencies.length - 1] >= frequency);
    }


    /**
     * Do device connection, with wakeup
     *
     * @return
     */
    public abstract boolean tryToConnectToDevice();


    // FIXME sorting, and time display
    public double scanForPump(double[] frequencies) {
        LOG.info("Scanning for receiver ({})", receiverDeviceID);
        wakeUp(receiverDeviceAwakeForMinutes, false);
        FrequencyScanResults results = new FrequencyScanResults();

        for (int i = 0; i < frequencies.length; i++) {
            int tries = 3;
            FrequencyTrial trial = new FrequencyTrial();
            trial.frequencyMHz = frequencies[i];
            rfspy.setBaseFrequency(frequencies[i]);

            int sumRSSI = 0;
            for (int j = 0; j < tries; j++) {

                byte[] pumpMsgContent = createPumpMessageContent(RLMessageType.ReadSimpleData);
                RFSpyResponse resp = rfspy.transmitThenReceive(new RadioPacket(pumpMsgContent), (byte) 0, (byte) 0, (byte) 0, (byte) 0, 1500, (byte) 0);
                if (resp.wasTimeout()) {
                    LOG.error("scanForPump: Failed to find pump at frequency {}", frequencies[i]);
                } else if (resp.looksLikeRadioPacket()) {
                    RadioResponse radioResponse = new RadioResponse(resp.getRaw());
                    if (radioResponse.isValid()) {
                        sumRSSI += radioResponse.rssi;
                        trial.successes++;
                    } else {
                        LOG.warn("Failed to parse radio response: " + ByteUtil.shortHexString(resp.getRaw()));
                    }
                } else {
                    LOG.error("scanForPump: raw response is " + ByteUtil.shortHexString(resp.getRaw()));
                }
                trial.tries++;
            }
            sumRSSI += -99.0 * (trial.tries - trial.successes);
            trial.averageRSSI = (double) (sumRSSI) / (double) (trial.tries);
            results.trials.add(trial);
        }


        StringBuilder stringBuilder = new StringBuilder("Scan results:\n");

        for (int k = 0; k < results.trials.size(); k++) {
            FrequencyTrial one = results.trials.get(k);

            stringBuilder.append(String.format("Scan Result[%s]: Freq=%s, avg RSSI = %s\n", "" + k, "" + one.frequencyMHz, "" + one.averageRSSI));

            //LOG.debug("Scan Result[{}]: Freq={}, avg RSSI = {}", k, one.frequencyMHz, one.averageRSSI);
        }

        LOG.debug(stringBuilder.toString());

        results.sort(); // sorts in ascending order


        FrequencyTrial bestTrial = results.trials.get(results.trials.size() - 1);
        results.bestFrequencyMHz = bestTrial.frequencyMHz;
        if (bestTrial.successes > 0) {
            rfspy.setBaseFrequency(results.bestFrequencyMHz);
            return results.bestFrequencyMHz;
        } else {
            LOG.error("No pump response during scan.");
            return 0.0;
        }
    }


    public abstract byte[] createPumpMessageContent(RLMessageType type);


    private int tune_tryFrequency(double freqMHz) {
        rfspy.setBaseFrequency(freqMHz);
        //RLMessage msg = makeRLMessage(RLMessageType.ReadSimpleData);
        byte[] pumpMsgContent = createPumpMessageContent(RLMessageType.ReadSimpleData);
        RadioPacket pkt = new RadioPacket(pumpMsgContent);
        RFSpyResponse resp = rfspy.transmitThenReceive(pkt, (byte) 0, (byte) 0, (byte) 0, (byte) 0, rfspy.EXPECTED_MAX_BLUETOOTH_LATENCY_MS, (byte) 0);
        if (resp.wasTimeout()) {
            LOG.warn("tune_tryFrequency: no pump response at frequency {}", freqMHz);
        } else if (resp.looksLikeRadioPacket()) {
            RadioResponse radioResponse = new RadioResponse(resp.getRaw());
            if (radioResponse.isValid()) {
                LOG.warn("tune_tryFrequency: saw response level {} at frequency {}", radioResponse.rssi, freqMHz);
                return radioResponse.rssi;
            } else {
                LOG.warn("tune_tryFrequency: invalid radio response:" + ByteUtil.shortHexString(radioResponse.getPayload()));
            }
        }
        return 0;
    }


    public double quickTuneForPump(double startFrequencyMHz) {
        double betterFrequency = startFrequencyMHz;
        double stepsize = 0.05;
        for (int tries = 0; tries < 4; tries++) {
            double evenBetterFrequency = quickTunePumpStep(betterFrequency, stepsize);
            if (evenBetterFrequency == 0.0) {
                // could not see the pump at all.
                // Try again at larger step size
                stepsize += 0.05;
            } else {
                if ((int) (evenBetterFrequency * 100) == (int) (betterFrequency * 100)) {
                    // value did not change, so we're done.
                    break;
                }
                betterFrequency = evenBetterFrequency; // and go again.
            }
        }
        if (betterFrequency == 0.0) {
            // we've failed... caller should try a full scan for pump
            LOG.error("quickTuneForPump: failed to find pump");
        } else {
            rfspy.setBaseFrequency(betterFrequency);
            if (betterFrequency != startFrequencyMHz) {
                LOG.info("quickTuneForPump: new frequency is {}MHz", betterFrequency);
            } else {
                LOG.info("quickTuneForPump: pump frequency is the same: {}MHz", startFrequencyMHz);
            }
        }
        return betterFrequency;
    }


    private double quickTunePumpStep(double startFrequencyMHz, double stepSizeMHz) {
        LOG.info("Doing quick radio tune for receiver ({})", receiverDeviceID);
        wakeUp(false);
        int startRssi = tune_tryFrequency(startFrequencyMHz);
        double lowerFrequency = startFrequencyMHz - stepSizeMHz;
        int lowerRssi = tune_tryFrequency(lowerFrequency);
        double higherFrequency = startFrequencyMHz + stepSizeMHz;
        int higherRssi = tune_tryFrequency(higherFrequency);

        if ((higherRssi == 0.0) && (lowerRssi == 0.0) && (startRssi == 0.0)) {
            // we can't see the pump at all...
            return 0.0;
        }
        if (higherRssi > startRssi) {
            // need to move higher
            return higherFrequency;
        } else if (lowerRssi > startRssi) {
            // need to move lower.
            return lowerFrequency;
        }
        return startFrequencyMHz;
    }


    private void rememberLastGoodPumpCommunicationTime() {
        lastGoodReceiverCommunicationTime = System.currentTimeMillis();

        SP.putLong(MedtronicConst.Statistics.LastGoodPumpCommunicationTime, lastGoodReceiverCommunicationTime);
        MedtronicUtil.getPumpStatus().setLastCommunicationToNow();
    }


    private long getLastGoodReceiverCommunicationTime() {
        // If we have a value of zero, we need to load from prefs.
        if (lastGoodReceiverCommunicationTime == 0L) {
            lastGoodReceiverCommunicationTime = SP.getLong(MedtronicConst.Statistics.LastGoodPumpCommunicationTime, 0L);
            // Might still be zero, but that's fine.
        }
        double minutesAgo = (System.currentTimeMillis() - lastGoodReceiverCommunicationTime) / (1000.0 * 60.0);
        LOG.trace("Last good pump communication was " + minutesAgo + " minutes ago.");
        return lastGoodReceiverCommunicationTime;
    }


    public PumpStatus getPumpStatus() {
        return pumpStatus;
    }


    public void clearNotConnectedCount() {
        if (rfspy != null) {
            rfspy.notConnectedCount = 0;
        }
    }
}
