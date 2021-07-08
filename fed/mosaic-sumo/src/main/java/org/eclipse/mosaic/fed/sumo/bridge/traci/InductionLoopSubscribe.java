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

package org.eclipse.mosaic.fed.sumo.bridge.traci;


import static org.eclipse.mosaic.fed.sumo.bridge.traci.constants.CommandRetrieveInductionLoopState.VAR_LAST_STEP_MEAN_SPEED;
import static org.eclipse.mosaic.fed.sumo.bridge.traci.constants.CommandRetrieveInductionLoopState.VAR_LAST_STEP_MEAN_VEHICLE_LENGTH;
import static org.eclipse.mosaic.fed.sumo.bridge.traci.constants.CommandRetrieveInductionLoopState.VAR_LAST_STEP_VEHICLE_DATA;

import org.eclipse.mosaic.fed.sumo.bridge.Bridge;
import org.eclipse.mosaic.fed.sumo.bridge.CommandException;
import org.eclipse.mosaic.fed.sumo.bridge.CommandRegister;
import org.eclipse.mosaic.fed.sumo.bridge.TraciVersion;
import org.eclipse.mosaic.fed.sumo.bridge.api.complex.Status;
import org.eclipse.mosaic.fed.sumo.bridge.traci.constants.CommandVariableSubscriptions;
import org.eclipse.mosaic.rti.TIME;
import org.eclipse.mosaic.rti.api.InternalFederateException;

import java.util.Arrays;
import java.util.Collection;

public class InductionLoopSubscribe
        extends AbstractTraciCommand<Void>
        implements org.eclipse.mosaic.fed.sumo.bridge.api.InductionLoopSubscribe {

    /**
     * Default constructor for {@link InductionLoopSubscribe}.
     * Called by {@link CommandRegister#getOrCreate(java.lang.Class)}.
     * Access needs to be public, because command is called using Reflection.
     *
     * @see <a href="https://sumo.dlr.de/docs/TraCI/Object_Variable_Subscription.html">Subscriptions</a>
     */
    @SuppressWarnings("WeakerAccess")
    public InductionLoopSubscribe() {
        this(Arrays.asList(
                VAR_LAST_STEP_MEAN_SPEED,
                VAR_LAST_STEP_MEAN_VEHICLE_LENGTH,
                VAR_LAST_STEP_VEHICLE_DATA
        ));
    }

    /**
     * Creates a new {@link InductionLoopSubscribe} object.
     * Access needs to be public, because command is called using Reflection.
     *
     * @param subscriptionCodes variables to subscribe to
     * @see <a href="https://sumo.dlr.de/docs/TraCI/Induction_Loop_Value_Retrieval.html">Induction Loop Value Retrieval</a>
     */
    @SuppressWarnings("WeakerAccess")
    public InductionLoopSubscribe(Collection<Integer> subscriptionCodes) {
        super(TraciVersion.LOWEST);

        TraciCommandWriterBuilder write = write()
                .command(CommandVariableSubscriptions.COMMAND_SUBSCRIBE_INDUCTION_LOOP_VALUES)
                .writeDoubleParam() // start time
                .writeDoubleParam() // end time
                .writeStringParam() // Object laneId
                .writeByte(subscriptionCodes.size());

        for (Integer subscriptionCode : subscriptionCodes) {
            write.writeByte(subscriptionCode);
        }

        read()
                .expectByte(CommandVariableSubscriptions.RESPONSE_SUBSCRIBE_INDUCTION_LOOP_VALUES)
                .skipString()
                .expectByte(subscriptionCodes.size())
                .skipRemaining();
    }

    /**
     * This method executes the command with the given arguments.
     *
     * @param bridge        Connection to traci.
     * @param inductionLoopId Id of the induction loop.
     * @param startTime       start time to subscribe to induction loop.
     * @param endTime         end time of subscribe.
     * @throws CommandException     if the status code of the response is ERROR. The connection to SUMO is still available.
     * @throws InternalFederateException if some serious error occurs during writing or reading. The TraCI connection is shut down.
     */
    public void execute(Bridge bridge, String inductionLoopId, long startTime, long endTime) throws CommandException, InternalFederateException {
        super.execute(bridge, ((double) startTime) / TIME.SECOND, ((double) endTime) / TIME.SECOND, inductionLoopId);
    }

    @Override
    protected Void constructResult(Status status, Object... objects) {
        return null;
    }
}
