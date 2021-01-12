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

package com.android.internal.os;

import android.os.BatteryConsumer;
import android.os.BatteryStats;
import android.os.BatteryUsageStats;
import android.os.BatteryUsageStatsQuery;
import android.os.SystemBatteryConsumer;
import android.os.UserHandle;
import android.util.Log;
import android.util.SparseArray;

import java.util.List;

/**
 * Estimates power consumed by the screen(s)
 */
public class ScreenPowerCalculator extends PowerCalculator {
    private static final String TAG = "ScreenPowerCalculator";
    private static final boolean DEBUG = BatteryStatsHelper.DEBUG;

    private final UsageBasedPowerEstimator mScreenOnPowerEstimator;
    private final UsageBasedPowerEstimator mScreenFullPowerEstimator;

    public ScreenPowerCalculator(PowerProfile powerProfile) {
        mScreenOnPowerEstimator = new UsageBasedPowerEstimator(
                powerProfile.getAveragePower(PowerProfile.POWER_SCREEN_ON));
        mScreenFullPowerEstimator = new UsageBasedPowerEstimator(
                powerProfile.getAveragePower(PowerProfile.POWER_SCREEN_FULL));
    }

    @Override
    public void calculate(BatteryUsageStats.Builder builder, BatteryStats batteryStats,
            long rawRealtimeUs, long rawUptimeUs, BatteryUsageStatsQuery query,
            SparseArray<UserHandle> asUsers) {
        final long durationMs = computeDuration(batteryStats, rawRealtimeUs,
                BatteryStats.STATS_SINCE_CHARGED);
        final double powerMah = computePower(batteryStats, rawRealtimeUs,
                BatteryStats.STATS_SINCE_CHARGED, durationMs);
        if (powerMah != 0) {
            builder.getOrCreateSystemBatteryConsumerBuilder(SystemBatteryConsumer.DRAIN_TYPE_SCREEN)
                    .setUsageDurationMillis(BatteryConsumer.TIME_COMPONENT_USAGE, durationMs)
                    .setConsumedPower(BatteryConsumer.POWER_COMPONENT_USAGE, powerMah);
        }
    }

    /**
     * Screen power is the additional power the screen takes while the device is running.
     */
    @Override
    public void calculate(List<BatterySipper> sippers, BatteryStats batteryStats,
            long rawRealtimeUs, long rawUptimeUs, int statsType, SparseArray<UserHandle> asUsers) {
        final long durationMs = computeDuration(batteryStats, rawRealtimeUs, statsType);
        final double powerMah = computePower(batteryStats, rawRealtimeUs, statsType, durationMs);
        if (powerMah != 0) {
            final BatterySipper bs = new BatterySipper(BatterySipper.DrainType.SCREEN, null, 0);
            bs.usagePowerMah = powerMah;
            bs.usageTimeMs = durationMs;
            bs.sumPower();
            sippers.add(bs);
        }
    }

    private long computeDuration(BatteryStats batteryStats, long rawRealtimeUs, int statsType) {
        return batteryStats.getScreenOnTime(rawRealtimeUs, statsType) / 1000;
    }

    private double computePower(BatteryStats batteryStats, long rawRealtimeUs, int statsType,
            long durationMs) {
        double power = mScreenOnPowerEstimator.calculatePower(durationMs);
        for (int i = 0; i < BatteryStats.NUM_SCREEN_BRIGHTNESS_BINS; i++) {
            final long brightnessTime =
                    batteryStats.getScreenBrightnessTime(i, rawRealtimeUs, statsType) / 1000;
            final double binPowerMah = mScreenFullPowerEstimator.calculatePower(brightnessTime)
                    * (i + 0.5f) / BatteryStats.NUM_SCREEN_BRIGHTNESS_BINS;
            if (DEBUG && binPowerMah != 0) {
                Log.d(TAG, "Screen bin #" + i + ": time=" + brightnessTime
                        + " power=" + formatCharge(binPowerMah));
            }
            power += binPowerMah;
        }
        return power;
    }
}
