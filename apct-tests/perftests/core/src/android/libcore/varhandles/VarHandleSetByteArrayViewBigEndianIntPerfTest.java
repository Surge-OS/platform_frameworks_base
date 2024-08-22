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

import androidx.benchmark.BenchmarkState;
import androidx.benchmark.junit4.BenchmarkRule;

import androidx.test.filters.LargeTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

import java.util.Arrays;
import java.nio.ByteOrder;

@RunWith(AndroidJUnit4.class)
@LargeTest
public class VarHandleSetByteArrayViewBigEndianIntPerfTest {
    @Rule public BenchmarkRule mBenchmarkRule = new BenchmarkRule();
    static final int VALUE = 42;
    byte[] mArray1 = { (byte) (VALUE >> 24), (byte) (VALUE >> 16), (byte) (VALUE >> 8), (byte) VALUE };
    byte[] mArray2 = { (byte) (-1 >> 24), (byte) (-1 >> 16), (byte) (-1 >> 8), (byte) VALUE };
    VarHandle mVh;

    public VarHandleSetByteArrayViewBigEndianIntPerfTest() throws Throwable {
        mVh = MethodHandles.byteArrayViewVarHandle(int[].class, ByteOrder.BIG_ENDIAN);
  }

    @After
    public void teardown() {
        if (!Arrays.equals(mArray2, mArray1)) {
            throw new RuntimeException("array has unexpected values: " +
                mArray2[0] + " " + mArray2[1] + " " + mArray2[2] + " " + mArray2[3]);
        }
    }

    @Test
    public void run() {
        byte[] a = mArray2;
        final BenchmarkState state = mBenchmarkRule.getState();
        while (state.keepRunning()) {
            mVh.set(a, 0, VALUE);
            mVh.set(a, 0, VALUE);
        }
    }
}
