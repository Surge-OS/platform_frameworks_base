/*
 * Copyright (C) 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.powerstats;

import java.io.FileInputStream;
import java.io.IOException;

/**
 * This class implements a utility to parse ODPM data out
 * of incident reports contained in bugreports.  The data
 * is output to STDOUT in csv format.
 */
public class PowerStatsServiceProtoParser {
    private static void printEnergyMeterInfo(PowerStatsServiceMeterProto proto) {
        String csvHeader = new String();
        for (int i = 0; i < proto.getChannelInfoCount(); i++) {
            ChannelInfoProto energyMeterInfo = proto.getChannelInfo(i);
            csvHeader += "Index,Timestamp," + energyMeterInfo.getChannelId()
                + "/" + energyMeterInfo.getChannelName() + ",";
        }
        System.out.println(csvHeader);
    }

    private static void printEnergyMeasurements(PowerStatsServiceMeterProto proto) {
        int energyMeterInfoCount = proto.getChannelInfoCount();

        if (energyMeterInfoCount > 0) {
            int energyMeasurementCount = proto.getEnergyMeasurementCount();
            int energyMeasurementSetCount = energyMeasurementCount / energyMeterInfoCount;

            for (int i = 0; i < energyMeasurementSetCount; i++) {
                String csvRow = new String();
                for (int j = 0; j < energyMeterInfoCount; j++) {
                    EnergyMeasurementProto energyMeasurement =
                            proto.getEnergyMeasurement(i * energyMeterInfoCount + j);
                    csvRow += energyMeasurement.getChannelId() + ","
                        + energyMeasurement.getTimestampMs() + ","
                        + energyMeasurement.getEnergyUws() + ",";
                }
                System.out.println(csvRow);
            }
        } else {
            System.out.println("Error:  energyMeterInfoCount is zero");
        }
    }

    private static void printEnergyConsumerId(PowerStatsServiceModelProto proto) {
        String csvHeader = new String();
        for (int i = 0; i < proto.getEnergyConsumerIdCount(); i++) {
            EnergyConsumerIdProto energyConsumerId = proto.getEnergyConsumerId(i);
            csvHeader += "Index,Timestamp," + energyConsumerId.getEnergyConsumerId() + ",";
        }
        System.out.println(csvHeader);
    }

    private static void printEnergyConsumerResults(PowerStatsServiceModelProto proto) {
        int energyConsumerIdCount = proto.getEnergyConsumerIdCount();

        if (energyConsumerIdCount > 0) {
            int energyConsumerResultCount = proto.getEnergyConsumerResultCount();
            int energyConsumerResultSetCount = energyConsumerResultCount / energyConsumerIdCount;

            for (int i = 0; i < energyConsumerResultSetCount; i++) {
                String csvRow = new String();
                for (int j = 0; j < energyConsumerIdCount; j++) {
                    EnergyConsumerResultProto energyConsumerResult =
                            proto.getEnergyConsumerResult(i * energyConsumerIdCount + j);
                    csvRow += energyConsumerResult.getEnergyConsumerId() + ","
                        + energyConsumerResult.getTimestampMs() + ","
                        + energyConsumerResult.getEnergyUws() + ",";
                }
                System.out.println(csvRow);
            }
        } else {
            System.out.println("Error:  energyConsumerIdCount is zero");
        }
    }

    private static void printPowerEntityInfo(PowerStatsServiceResidencyProto proto) {
        String csvHeader = new String();
        for (int i = 0; i < proto.getPowerEntityInfoCount(); i++) {
            PowerEntityInfoProto powerEntityInfo = proto.getPowerEntityInfo(i);
            csvHeader += powerEntityInfo.getPowerEntityId() + ","
                + powerEntityInfo.getPowerEntityName() + ",";
            for (int j = 0; j < powerEntityInfo.getStatesCount(); j++) {
                StateInfoProto stateInfo = powerEntityInfo.getStates(j);
                csvHeader += stateInfo.getStateId() + "," + stateInfo.getStateName() + ",";
            }
        }
        System.out.println(csvHeader);
    }

    private static void printStateResidencyResult(PowerStatsServiceResidencyProto proto) {
        for (int i = 0; i < proto.getStateResidencyResultCount(); i++) {
            String csvRow = new String();

            StateResidencyResultProto stateResidencyResult = proto.getStateResidencyResult(i);
            csvRow += stateResidencyResult.getPowerEntityId() + ",";

            for (int j = 0; j < stateResidencyResult.getStateResidencyDataCount(); j++) {
                StateResidencyProto stateResidency = stateResidencyResult.getStateResidencyData(j);
                csvRow += stateResidency.getStateId() + ","
                    + stateResidency.getTotalTimeInStateMs() + ","
                    + stateResidency.getTotalStateEntryCount() + ","
                    + stateResidency.getLastEntryTimestampMs() + ",";
            }
            System.out.println(csvRow);
        }
    }

    private static void generateCsvFile(String pathToIncidentReport) {
        try {
            // Print power meter data.
            IncidentReportMeterProto irMeterProto =
                    IncidentReportMeterProto.parseFrom(new FileInputStream(pathToIncidentReport));

            if (irMeterProto.hasIncidentReport()) {
                PowerStatsServiceMeterProto pssMeterProto = irMeterProto.getIncidentReport();
                printEnergyMeterInfo(pssMeterProto);
                printEnergyMeasurements(pssMeterProto);
            } else {
                System.out.println("Meter incident report not found.  Exiting.");
            }

            // Print power model data.
            IncidentReportModelProto irModelProto =
                    IncidentReportModelProto.parseFrom(new FileInputStream(pathToIncidentReport));

            if (irModelProto.hasIncidentReport()) {
                PowerStatsServiceModelProto pssModelProto = irModelProto.getIncidentReport();
                printEnergyConsumerId(pssModelProto);
                printEnergyConsumerResults(pssModelProto);
            } else {
                System.out.println("Model incident report not found.  Exiting.");
            }

            // Print state residency data.
            IncidentReportResidencyProto irResidencyProto =
                    IncidentReportResidencyProto.parseFrom(
                        new FileInputStream(pathToIncidentReport));

            if (irResidencyProto.hasIncidentReport()) {
                PowerStatsServiceResidencyProto pssResidencyProto =
                        irResidencyProto.getIncidentReport();
                printPowerEntityInfo(pssResidencyProto);
                printStateResidencyResult(pssResidencyProto);
            } else {
                System.out.println("Residency incident report not found.  Exiting.");
            }

        } catch (IOException e) {
            System.out.println("Unable to open incident report file: " + pathToIncidentReport);
            System.out.println(e);
        }
    }

    /**
     * This is the entry point to parse the ODPM data out of incident reports.
     * It requires one argument which is the path to the incident_report.proto
     * file captured in a bugreport.
     *
     * @param args Path to incident_report.proto passed in from command line.
     */
    public static void main(String[] args) {
        if (args.length > 0) {
            generateCsvFile(args[0]);
        } else {
            System.err.println("Usage: PowerStatsServiceProtoParser <incident_report.proto>");
            System.err.println("Missing path to incident_report.proto.  Exiting.");
            System.exit(1);
        }
    }
}
