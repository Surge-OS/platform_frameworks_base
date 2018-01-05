/*
 * Copyright (C) 2012 The Android Open Source Project
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

package com.android.internal.widget;

import android.app.PendingIntent;
import android.app.trust.IStrongAuthTracker;
import android.os.Bundle;
import android.security.recoverablekeystore.KeyEntryRecoveryData;
import android.security.recoverablekeystore.KeyStoreRecoveryData;
import android.security.recoverablekeystore.KeyStoreRecoveryMetadata;
import com.android.internal.widget.ICheckCredentialProgressCallback;
import com.android.internal.widget.VerifyCredentialResponse;

import java.util.Map;

/** {@hide} */
interface ILockSettings {
    void setBoolean(in String key, in boolean value, in int userId);
    void setLong(in String key, in long value, in int userId);
    void setString(in String key, in String value, in int userId);
    boolean getBoolean(in String key, in boolean defaultValue, in int userId);
    long getLong(in String key, in long defaultValue, in int userId);
    String getString(in String key, in String defaultValue, in int userId);
    void setLockCredential(in String credential, int type, in String savedCredential, int requestedQuality, int userId);
    void resetKeyStore(int userId);
    VerifyCredentialResponse checkCredential(in String credential, int type, int userId,
            in ICheckCredentialProgressCallback progressCallback);
    VerifyCredentialResponse verifyCredential(in String credential, int type, long challenge, int userId);
    VerifyCredentialResponse verifyTiedProfileChallenge(String credential, int type, long challenge, int userId);
    boolean checkVoldPassword(int userId);
    boolean havePattern(int userId);
    boolean havePassword(int userId);
    void setSeparateProfileChallengeEnabled(int userId, boolean enabled, String managedUserPassword);
    boolean getSeparateProfileChallengeEnabled(int userId);
    void registerStrongAuthTracker(in IStrongAuthTracker tracker);
    void unregisterStrongAuthTracker(in IStrongAuthTracker tracker);
    void requireStrongAuth(int strongAuthReason, int userId);
    void systemReady();
    void userPresent(int userId);
    int getStrongAuthForUser(int userId);

    long addEscrowToken(in byte[] token, int userId);
    boolean removeEscrowToken(long handle, int userId);
    boolean isEscrowTokenActive(long handle, int userId);
    boolean setLockCredentialWithToken(String credential, int type, long tokenHandle,
            in byte[] token, int requestedQuality, int userId);
    void unlockUserWithToken(long tokenHandle, in byte[] token, int userId);

    // RecoverableKeyStoreLoader methods.
    // {@code ServiceSpecificException} may be thrown to signal an error, which caller can
    // convert to  {@code RecoverableKeyStoreLoader}.
    void initRecoveryService(in String rootCertificateAlias, in byte[] signedPublicKeyList);
    KeyStoreRecoveryData getRecoveryData(in byte[] account);
    byte[] generateAndStoreKey(String alias);
    void setSnapshotCreatedPendingIntent(in PendingIntent intent);
    Map getRecoverySnapshotVersions();
    void setServerParameters(long serverParameters);
    void setRecoveryStatus(in String packageName, in String[] aliases, int status);
    Map getRecoveryStatus(in String packageName);
    void setRecoverySecretTypes(in int[] secretTypes);
    int[] getRecoverySecretTypes();
    int[] getPendingRecoverySecretTypes();
    void recoverySecretAvailable(in KeyStoreRecoveryMetadata recoverySecret);
    byte[] startRecoverySession(in String sessionId,
            in byte[] verifierPublicKey, in byte[] vaultParams, in byte[] vaultChallenge,
            in List<KeyStoreRecoveryMetadata> secrets);
    Map/*<String, byte[]>*/ recoverKeys(in String sessionId, in byte[] recoveryKeyBlob,
            in List<KeyEntryRecoveryData> applicationKeys);
}
