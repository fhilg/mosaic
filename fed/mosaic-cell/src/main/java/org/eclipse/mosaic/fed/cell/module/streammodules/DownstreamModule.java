/*
 * Copyright (c) 2020 Fraunhofer FOKUS and others. All rights reserved.
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contact: mosaic@fokus.fraunhofer.de
 */

package org.eclipse.mosaic.fed.cell.module.streammodules;

import org.eclipse.mosaic.fed.cell.chain.ChainManager;
import org.eclipse.mosaic.fed.cell.config.model.CNetworkProperties;
import org.eclipse.mosaic.fed.cell.message.CellModuleMessage;
import org.eclipse.mosaic.fed.cell.message.GeocasterResult;
import org.eclipse.mosaic.fed.cell.message.StreamResult;
import org.eclipse.mosaic.fed.cell.module.CellModuleNames;
import org.eclipse.mosaic.fed.cell.utility.RegionUtility;
import org.eclipse.mosaic.fed.cell.viz.StreamListener.StreamParticipant;
import org.eclipse.mosaic.fed.cell.viz.StreamListener.StreamProperties;
import org.eclipse.mosaic.interactions.communication.V2xFullMessageReception;
import org.eclipse.mosaic.interactions.communication.V2xMessageAcknowledgement;
import org.eclipse.mosaic.interactions.communication.V2xMessageReception;
import org.eclipse.mosaic.lib.enums.ProtocolType;
import org.eclipse.mosaic.lib.objects.v2x.MessageStreamRouting;
import org.eclipse.mosaic.lib.objects.v2x.V2xMessage;
import org.eclipse.mosaic.lib.objects.v2x.V2xReceiverInformation;
import org.eclipse.mosaic.lib.util.scheduling.Event;
import org.eclipse.mosaic.rti.TIME;
import org.eclipse.mosaic.rti.api.InternalFederateException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This module simulates the Downlink in the RAN-Part of the cellular network.
 * When messages successfully passed the Downlink module, they go to the receiver applications.
 * This module supports 2 different modes - Unicast and Multi/Broadcast.
 */
public final class DownstreamModule extends AbstractStreamModule {

    private static final Logger log = LoggerFactory.getLogger(DownstreamModule.class);

    /**
     * Creates a new {@link DownstreamModule} object.
     *
     * @param chainManager Manage the interaction between cell modules and the MOSAIC interfaces.
     */
    public DownstreamModule(ChainManager chainManager) {
        super(CellModuleNames.DOWNSTREAM_MODULE, chainManager, log);
    }

    @Override
    public void processEvent(Event event) throws Exception {
        // The DownstreamModule is normally called after the GeocasterModule
        Object resource = event.getResource();
        if (resource == null) {
            throw new RuntimeException("No input message (event resource) for " + moduleName);
        }
        if (!(resource instanceof CellModuleMessage resultMessage)) {
            throw new RuntimeException("The resource of the event is not a CellModuleResultMessage");
        }

        if (resultMessage.getResource() instanceof GeocasterResult) {
            // handle message transmission
            GeocasterResult geocasterResult = resultMessage.getResource();
            processMessage(geocasterResult, event.getTime());
        } else if (resultMessage.getResource() instanceof StreamResult
                && resultMessage.getEmittingModule().equals(CellModuleNames.DOWNSTREAM_MODULE)) {
            // or clean up pending messages (generated by DownstreamModule itself)
            freeBandwidth(resultMessage);
        } else {
            throw new RuntimeException("Unsupported input message (resource of the event resource) for " + moduleName);
        }
    }

    /**
     * Process the message for Downlink transmission (either Uni- or Multi/Broad-cast).
     *
     * @param geocasterResult  Encapsulated Geocaster result from the previous module (Geocaster).
     * @param messageStartTime Simulation time to start the message transmission.
     */
    private void processMessage(final GeocasterResult geocasterResult, final long messageStartTime) throws InternalFederateException {
        log.debug("t={}: Entering processMessage() of module {}",
                TIME.format(messageStartTime), getModuleName());
        switch (geocasterResult.getDownstreamMode()) {
            case DownlinkUnicast -> doUnicast(geocasterResult, messageStartTime);
            case DownlinkMulticast -> doMulticast(geocasterResult, messageStartTime);
            default -> throw new RuntimeException("Unsupported transmission mode " + geocasterResult.getDownstreamMode());
        }
    }

    /**
     * In DownlinkUnicast, (try to) send the message individually to all receivers in all regions
     * (this can also be only a single receiver in one region).
     * The Downstream Module has no following modules in the basic configuration,
     * but in case of a successful transmission the RTI receivers should get the message.
     *
     * @param geocasterResult  Encapsulated Geocaster result from the previous module (Geocaster).
     * @param messageStartTime Simulation time to start the message transmission.
     */
    private void doUnicast(final GeocasterResult geocasterResult, final long messageStartTime) throws InternalFederateException {
        for (CNetworkProperties region : geocasterResult.getReceivers().keySet()) {
            for (String receiverId : geocasterResult.getReceivers().get(region)) {
                // create stream
                StreamProcessor.Input processingInput = new StreamProcessor.Input()
                        .module(CellModuleNames.DOWNSTREAM_MODULE, null)
                        .message(messageStartTime, geocasterResult.getV2xMessage(), geocasterResult.getDownstreamMode())
                        .node(receiverId, region);

                final StreamProcessor.Result processingResult = doStreamProcessing(processingInput);
                final CellModuleMessage cellModuleMessage = processResult(processingInput, processingResult);
                if (processingResult.isAcknowledged()) {

                    V2xReceiverInformation receiverInformation = extractReceiverInformation(processingResult, messageStartTime);
                    // The message was successfully sent.
                    // Now send the payload to the receiver.
                    // StreamResult streamResult = (StreamResult) re.getResource();
                    // TODO: better ReceiverInformation handling
                    sendReceptionInteraction(
                            geocasterResult.isFullMessage(),
                            processingResult.getMessageEndTime(), receiverId,
                            processingInput.getV2xMessage(),
                            receiverInformation
                    );
                    notifyStreamListeners(processingInput, processingResult, cellModuleMessage);
                    sendAck(processingInput, processingResult);
                    // Note: The NAck was already sent in doStreamProcessing()
                }
            }
        }
    }

    /**
     * In DownlinkMulticast, only (try to) send the message via broadcast once in each region
     * the Downstream Module has no following modules in the basic configuration.
     * But in case of a successful transmission the RTI receivers should get the message.
     *
     * @param geocasterResult  Encapsulated Geocaster result from the previous module (Geocaster).
     * @param messageStartTime Simulation time to start the message transmission.
     */
    private void doMulticast(final GeocasterResult geocasterResult, final long messageStartTime) throws InternalFederateException {
        for (CNetworkProperties region : geocasterResult.getReceivers().keySet()) {
            final StreamProcessor.Input processingInput = new StreamProcessor.Input()
                    .module(CellModuleNames.DOWNSTREAM_MODULE, null)
                    .message(messageStartTime, geocasterResult.getV2xMessage(), geocasterResult.getDownstreamMode())
                    .node(null, region);

            final StreamProcessor.Result processingResult = doStreamProcessing(processingInput);
            final CellModuleMessage cellModuleMessage = processResult(processingInput, processingResult);

            if (processingResult.isAcknowledged()) {
                V2xReceiverInformation receiverInformation = extractReceiverInformation(processingResult, messageStartTime);

                // TODO: better ReceiverInformation handling
                for (String receiver : geocasterResult.getReceivers().get(region)) {
                    sendReceptionInteraction(
                            geocasterResult.isFullMessage(),
                            processingResult.getMessageEndTime(), receiver,
                            processingInput.getV2xMessage(),
                            receiverInformation
                    );
                }
                notifyStreamListeners(processingInput, processingResult, cellModuleMessage);
                // sendAck(input, processingResult); // no ack is sent in multicast TODO get confirmed
            }
        }
    }

    private V2xReceiverInformation extractReceiverInformation(StreamProcessor.Result processingResult, long messageStartTime) {
        return new V2xReceiverInformation(processingResult.getMessageEndTime())
                .sendTime(messageStartTime)
                .neededBandwidth(processingResult.getRequiredBandwidthInBps());
    }

    private void sendReceptionInteraction(boolean isFullMessage, long messageEndTime, String receiverId,
                                          V2xMessage message, V2xReceiverInformation receiverInformation) {
        if (isFullMessage) {
            chainManager.sendInteractionToRti(
                    new V2xFullMessageReception(messageEndTime, receiverId, message, receiverInformation)
            );
        } else {
            chainManager.sendInteractionToRti(
                    new V2xMessageReception(messageEndTime, receiverId, message.getId(), receiverInformation)
            );
        }
    }

    private void notifyStreamListeners(StreamProcessor.Input parameters, StreamProcessor.Result result, CellModuleMessage cellModuleMessage) throws InternalFederateException {
        String senderId = parameters.getV2xMessage().getRouting().getSource().getSourceName();
        String senderRegion = RegionUtility.getRegionForNode(senderId).id;
        String applicationClass = "*";

        if (cellModuleMessage.getResource() instanceof StreamResult streamResult) {
            long transmissionEndTime = result.getMessageEndTime();
            if (streamResult.getV2xMessage().getRouting() instanceof MessageStreamRouting streamRouting) {
                transmissionEndTime += streamRouting.getStreamingDuration();
            }
            chainManager.notifyStreamListeners(
                    new StreamParticipant(senderRegion, parameters.getMessageStartTime()),
                    new StreamParticipant(parameters.getRegion().id, transmissionEndTime),
                    new StreamProperties(applicationClass, streamResult.getConsumedBandwidth()));
        } else {
            throw new InternalFederateException("The results from the DownstreamModule did not contain a StreamResult object");
        }
    }

    /**
     * In Transport Control Protocol (TCP) the communication entities use
     * acknowledgement messages in order to notify about successful reception.
     *
     * @param input  Input data.
     * @param result Info of sent message.
     */
    private void sendAck(StreamProcessor.Input input, StreamProcessor.Result result) {
        if (input.getV2xMessage().getRouting().getDestination().getProtocolType().equals(ProtocolType.TCP)) {
            // Send the ack to the sender.
            chainManager.sendInteractionToRti(
                    new V2xMessageAcknowledgement(result.getMessageEndTime(), input.getV2xMessage())
            );
        }
    }
}
