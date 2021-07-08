/*
 * Copyright (c) 2021 Fraunhofer FOKUS and others. All rights reserved.
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
package org.eclipse.mosaic.fed.sumo.bridge.libsumo;

import org.eclipse.mosaic.fed.sumo.bridge.Bridge;
import org.eclipse.mosaic.fed.sumo.bridge.CommandException;
import org.eclipse.mosaic.fed.sumo.bridge.api.complex.AbstractSubscriptionResult;
import org.eclipse.mosaic.fed.sumo.bridge.api.complex.InductionLoopSubscriptionResult;
import org.eclipse.mosaic.fed.sumo.bridge.api.complex.InductionLoopVehicleData;
import org.eclipse.mosaic.fed.sumo.bridge.api.complex.LaneAreaSubscriptionResult;
import org.eclipse.mosaic.fed.sumo.bridge.api.complex.TrafficLightSubscriptionResult;
import org.eclipse.mosaic.fed.sumo.bridge.api.complex.VehicleSubscriptionResult;
import org.eclipse.mosaic.lib.geo.CartesianPoint;
import org.eclipse.mosaic.lib.util.objects.Position;
import org.eclipse.mosaic.rti.TIME;
import org.eclipse.mosaic.rti.api.InternalFederateException;

import org.eclipse.sumo.libsumo.InductionLoop;
import org.eclipse.sumo.libsumo.LaneArea;
import org.eclipse.sumo.libsumo.Simulation;
import org.eclipse.sumo.libsumo.StringVector;
import org.eclipse.sumo.libsumo.TraCIPosition;
import org.eclipse.sumo.libsumo.TrafficLight;
import org.eclipse.sumo.libsumo.Vehicle;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;


public class SimulationSimulateStep implements org.eclipse.mosaic.fed.sumo.bridge.api.SimulationSimulateStep {

    final static Set<String> VEHICLE_SUBSCRIPTIONS = new HashSet<>();
    final static Set<String> INDUCTION_LOOP_SUBSCRIPTIONS = new HashSet<>();
    final static Set<String> LANE_AREA_SUBSCRIPTIONS = new HashSet<>();
    final static Set<String> TRAFFIC_LIGHT_SUBSCRIPTIONS = new HashSet<>();

    public SimulationSimulateStep() {
        VEHICLE_SUBSCRIPTIONS.clear();
        INDUCTION_LOOP_SUBSCRIPTIONS.clear();
        LANE_AREA_SUBSCRIPTIONS.clear();
        TRAFFIC_LIGHT_SUBSCRIPTIONS.clear();
    }

    public List<AbstractSubscriptionResult> execute(Bridge bridge, long time) throws CommandException, InternalFederateException {
        Simulation.step((double) (time) / TIME.SECOND);

        List<AbstractSubscriptionResult> results = new ArrayList<>();
        readVehicles(results);
        readInductionLoops(results);
        readLaneAreas(results);
        readTrafficLights(results);

        return results;
    }

    private void readVehicles(List<AbstractSubscriptionResult> results) {
        String mosaicVehicleId;
        for (String sumoVehicleId : Vehicle.getIDList()) {
            mosaicVehicleId = Bridge.VEHICLE_ID_TRANSFORMER.fromExternalId(sumoVehicleId);

            if (!VEHICLE_SUBSCRIPTIONS.contains(mosaicVehicleId)) {
                continue;
            }

            VehicleSubscriptionResult result = new VehicleSubscriptionResult();
            result.id = mosaicVehicleId;
            result.speed = Vehicle.getSpeed(sumoVehicleId);
            result.distanceDriven = Vehicle.getDistance(sumoVehicleId);
            result.heading = Vehicle.getAngle(sumoVehicleId);
            result.slope = Vehicle.getSlope(sumoVehicleId);
            result.acceleration = Vehicle.getAcceleration(sumoVehicleId);
            result.minGap = Vehicle.getMinGap(sumoVehicleId);

            TraCIPosition traCIPosition = Vehicle.getPosition(sumoVehicleId);
            result.position = new Position(CartesianPoint.xyz(traCIPosition.getX(), traCIPosition.getY(), traCIPosition.getZ() < -1000 ? 0 : traCIPosition.getZ()));

            result.stoppedStateEncoded = Vehicle.getStopState(sumoVehicleId);
            result.signalsEncoded = Vehicle.getSignals(sumoVehicleId);

            result.routeId = Vehicle.getRouteID(sumoVehicleId);

            result.edgeId = Vehicle.getRoadID(sumoVehicleId);
            result.lanePosition = Vehicle.getLanePosition(sumoVehicleId);
            result.lateralLanePosition = Vehicle.getLateralLanePosition(sumoVehicleId);
            result.laneIndex = Vehicle.getLaneIndex(sumoVehicleId);

            result.co2 = Vehicle.getCO2Emission(sumoVehicleId);
            result.co = Vehicle.getCOEmission(sumoVehicleId);
            result.hc = Vehicle.getHCEmission(sumoVehicleId);
            result.pmx = Vehicle.getPMxEmission(sumoVehicleId);
            result.nox = Vehicle.getNOxEmission(sumoVehicleId);
            result.fuel = Vehicle.getFuelConsumption(sumoVehicleId);

            results.add(result);
        }

        for (String arrived : Simulation.getArrivedIDList()) {
            VEHICLE_SUBSCRIPTIONS.remove(Bridge.VEHICLE_ID_TRANSFORMER.fromExternalId(arrived));
        }
    }


    private void readInductionLoops(List<AbstractSubscriptionResult> results) {
        StringVector inductionLoopIds = InductionLoop.getIDList();

        for (String inductionLoop : inductionLoopIds) {
            if (!INDUCTION_LOOP_SUBSCRIPTIONS.contains(inductionLoop)) {
                continue;
            }

            InductionLoopSubscriptionResult result = new InductionLoopSubscriptionResult();
            result.id = inductionLoop;
            result.meanSpeed = InductionLoop.getLastStepMeanSpeed(inductionLoop);
            result.meanVehicleLength = InductionLoop.getLastStepMeanLength(inductionLoop);
            result.vehiclesOnInductionLoop = new ArrayList<>();

            for (String vehicle: InductionLoop.getLastStepVehicleIDs(inductionLoop)) {
                InductionLoopVehicleData vehicleData = new InductionLoopVehicleData();
                vehicleData.vehicleId = Bridge.VEHICLE_ID_TRANSFORMER.fromExternalId(vehicle);
                //TODO add more vehicle data! (use InductionLoop.getVehicleData(inductionLoop), which is not fully implemented yet)
                result.vehiclesOnInductionLoop.add(vehicleData);
            }
            results.add(result);
        }
    }

    private void readLaneAreas(List<AbstractSubscriptionResult> results) {
        for (String laneArea : LaneArea.getIDList()) {
            if (!LANE_AREA_SUBSCRIPTIONS.contains(laneArea)) {
                continue;
            }

            LaneAreaSubscriptionResult result = new LaneAreaSubscriptionResult();
            result.id = laneArea;
            result.vehicleCount = LaneArea.getLastStepVehicleNumber(laneArea);
            result.meanSpeed = LaneArea.getLastStepMeanSpeed(laneArea);
            result.haltingVehicles = LaneArea.getLastStepHaltingNumber(laneArea);
            result.length = LaneArea.getLength(laneArea);

            for (String vehicle: LaneArea.getLastStepVehicleIDs(laneArea)) {
                result.vehicles.add(Bridge.VEHICLE_ID_TRANSFORMER.fromExternalId(vehicle));
            }
            results.add(result);
        }
    }

    private void readTrafficLights(List<AbstractSubscriptionResult> results) {
        for (String trafficLight : TrafficLight.getIDList()) {
            if (!TRAFFIC_LIGHT_SUBSCRIPTIONS.contains(trafficLight)) {
                continue;
            }

            TrafficLightSubscriptionResult result = new TrafficLightSubscriptionResult();
            result.id = trafficLight;
            result.currentProgramId = TrafficLight.getProgram(trafficLight);
            result.assumedNextPhaseSwitchTime = (long) (TrafficLight.getNextSwitch(trafficLight) * TIME.SECOND);
            result.currentPhaseIndex = TrafficLight.getPhase(trafficLight);
            result.currentStateEncoded = TrafficLight.getRedYellowGreenState(trafficLight);
            results.add(result);
        }

    }
}
