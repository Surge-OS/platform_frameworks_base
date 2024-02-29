/*
 * Copyright (C) 2022 The Android Open Source Project
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
// This file is generated by generate_java.py do not directly modify!
package android.libcore.varhandles;

import android.perftests.utils.BenchmarkState;
import android.perftests.utils.PerfStatusReporter;

import androidx.test.filters.LargeTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.util.Arrays;

@RunWith(AndroidJUnit4.class)
@LargeTest
public class VarHandleSetByteArrayViewLittleEndianIntPerfTest {
    @Rule public PerfStatusReporter mPerfStatusReporter = new PerfStatusReporter();
    static final int VALUE = 42;
    byte[] mArray1 = {
        (byte) VALUE, (byte) (VALUE >> 8), (byte) (VALUE >> 16), (byte) (VALUE >> 24)
    };
    byte[] mArray2 = {(byte) VALUE, (byte) (-1 >> 8), (byte) (-1 >> 16), (byte) (-1 >> 24)};
    VarHandle mVh;

    public VarHandleSetByteArrayViewLittleEndianIntPerfTest() throws Throwable {
        mVh = MethodHandles.byteArrayViewVarHandle(int[].class, ByteOrder.LITTLE_ENDIAN);
    }

    @After
    public void teardown() {
        if (!Arrays.equals(mArray2, mArray1)) {
            throw new RuntimeException(
                    "array has unexpected values: "
                            + mArray2[0]
                            + " "
                            + mArray2[1]
                            + " "
                            + mArray2[2]
                            + " "
                            + mArray2[3]);
        }
    }

    @Test
    public void run() {
        byte[] a = mArray2;
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            mVh.set(a, 0, VALUE);
            mVh.set(a, 0, VALUE);
        }
    }
}
