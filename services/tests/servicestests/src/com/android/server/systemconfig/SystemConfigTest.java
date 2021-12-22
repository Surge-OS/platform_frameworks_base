/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.server.systemconfig;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertEquals;
import static org.testng.Assert.expectThrows;

import android.os.Build;
import android.platform.test.annotations.Presubmit;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Log;
import android.util.Xml;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.server.SystemConfig;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.xmlpull.v1.XmlPullParser;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Arrays;
import java.util.Map;
import java.util.Scanner;
import java.util.Set;

/**
 * Tests for {@link SystemConfig}.
 *
 * Build/Install/Run:
 *  atest FrameworksServicesTests:SystemConfigTest
 */
@SmallTest
@Presubmit
@RunWith(AndroidJUnit4.class)
public class SystemConfigTest {
    private static final String LOG_TAG = "SystemConfigTest";

    private SystemConfig mSysConfig;
    private File mFooJar;

    @Rule public TemporaryFolder mTemporaryFolder = new TemporaryFolder();

    @Before
    public void setUp() throws Exception {
        mSysConfig = new SystemConfigTestClass();
        mFooJar = createTempFile(
                mTemporaryFolder.getRoot().getCanonicalFile(), "foo.jar", "JAR");
    }

    /**
     * Subclass of SystemConfig without running the constructor.
     */
    private class SystemConfigTestClass extends SystemConfig {
        SystemConfigTestClass() {
            super(false);
        }
    }

    private void readPermissions(File libraryDir, int permissionFlag) {
        final XmlPullParser parser = Xml.newPullParser();
        mSysConfig.readPermissions(parser, libraryDir, permissionFlag);
    }

    /**
     * Tests that readPermissions works correctly for the tag: install-in-user-type
     */
    @Test
    public void testInstallInUserType() throws Exception {
        final String contents1 =
                  "<permissions>\n"
                + "    <install-in-user-type package=\"com.android.package1\">\n"
                + "        <install-in user-type=\"FULL\" />\n"
                + "        <install-in user-type=\"PROFILE\" />\n"
                + "    </install-in-user-type>\n"
                + "    <install-in-user-type package=\"com.android.package2\">\n"
                + "        <install-in user-type=\"FULL\" />\n"
                + "        <install-in user-type=\"PROFILE\" />\n"
                + "        <do-not-install-in user-type=\"GUEST\" />\n"
                + "    </install-in-user-type>\n"
                + "</permissions>";

        final String contents2 =
                  "<permissions>\n"
                + "    <install-in-user-type package=\"com.android.package2\">\n"
                + "        <install-in user-type=\"SYSTEM\" />\n"
                + "        <do-not-install-in user-type=\"PROFILE\" />\n"
                + "    </install-in-user-type>\n"
                + "</permissions>";

        final String contents3 =
                  "<permissions>\n"
                + "    <install-in-user-type package=\"com.android.package2\">\n"
                + "        <install-in invalid-attribute=\"ADMIN\" />\n" // Ignore invalid attribute
                + "    </install-in-user-type>\n"
                + "    <install-in-user-type package=\"com.android.package2\">\n"
                + "        <install-in user-type=\"RESTRICTED\" />\n"  // Valid
                + "    </install-in-user-type>\n"
                + "    <install-in-user-type>\n" // Ignored since missing package name
                + "        <install-in user-type=\"ADMIN\" />\n"
                + "    </install-in-user-type>\n"
                + "</permissions>";

        Map<String, Set<String>> expectedWhite = new ArrayMap<>();
        expectedWhite.put("com.android.package1",
                new ArraySet<>(Arrays.asList("FULL", "PROFILE")));
        expectedWhite.put("com.android.package2",
                new ArraySet<>(Arrays.asList("FULL", "PROFILE", "RESTRICTED", "SYSTEM")));

        Map<String, Set<String>> expectedBlack = new ArrayMap<>();
        expectedBlack.put("com.android.package2",
                new ArraySet<>(Arrays.asList("GUEST", "PROFILE")));

        final File folder1 = createTempSubfolder("folder1");
        createTempFile(folder1, "permFile1.xml", contents1);

        final File folder2 = createTempSubfolder("folder2");
        createTempFile(folder2, "permFile2.xml", contents2);

        // Also, make a third file, but with the name folder1/permFile2.xml, to prove no conflicts.
        createTempFile(folder1, "permFile2.xml", contents3);

        readPermissions(folder1, /* No permission needed anyway */ 0);
        readPermissions(folder2, /* No permission needed anyway */ 0);

        Map<String, Set<String>> actualWhite = mSysConfig.getAndClearPackageToUserTypeWhitelist();
        Map<String, Set<String>> actualBlack = mSysConfig.getAndClearPackageToUserTypeBlacklist();

        assertEquals("Whitelist was not cleared", 0,
                mSysConfig.getAndClearPackageToUserTypeWhitelist().size());
        assertEquals("Blacklist was not cleared", 0,
                mSysConfig.getAndClearPackageToUserTypeBlacklist().size());

        assertEquals("Incorrect whitelist.", expectedWhite, actualWhite);
        assertEquals("Incorrect blacklist", expectedBlack, actualBlack);
    }

    @Test
    public void testComponentOverride() throws Exception {
        final String contents =
                "<permissions>"
                + "    <component-override package=\"com.android.package1\">\n"
                + "        <component class=\"com.android.package1.Full\" enabled=\"true\"/>"
                + "        <component class=\".Relative\" enabled=\"false\" />\n"
                + "    </component-override>"
                + "    <component-override package=\"com.android.package2\" >\n"
                + "        <component class=\"com.android.package3.Relative2\" enabled=\"yes\" />\n"
                + "    </component-override>\n"
                + "</permissions>";

        final File folder = createTempSubfolder("folder");
        createTempFile(folder, "component-override.xml", contents);

        readPermissions(folder, /* No permission needed anyway */ 0);

        final ArrayMap<String, Boolean> packageOneExpected = new ArrayMap<>();
        packageOneExpected.put("com.android.package1.Full", true);
        packageOneExpected.put("com.android.package1.Relative", false);

        final ArrayMap<String, Boolean> packageTwoExpected = new ArrayMap<>();
        packageTwoExpected.put("com.android.package3.Relative2", true);

        final Map<String, Boolean> packageOne = mSysConfig.getComponentsEnabledStates(
                "com.android.package1");
        assertEquals(packageOneExpected, packageOne);

        final Map<String, Boolean> packageTwo = mSysConfig.getComponentsEnabledStates(
                "com.android.package2");
        assertEquals(packageTwoExpected, packageTwo);
    }

    /**
     * Tests that readPermissions works correctly with {@link SystemConfig#ALLOW_APP_CONFIGS}
     * permission flag for the tag: allowlisted-staged-installer.
     */
    @Test
    public void readPermissions_allowAppConfigs_parsesStagedInstallerWhitelist()
            throws IOException {
        final String contents =
                "<config>\n"
                + "    <whitelisted-staged-installer package=\"com.android.package1\" />\n"
                + "</config>";
        final File folder = createTempSubfolder("folder");
        createTempFile(folder, "staged-installer-whitelist.xml", contents);

        readPermissions(folder, /* Grant all permission flags */ ~0);

        assertThat(mSysConfig.getWhitelistedStagedInstallers())
                .containsExactly("com.android.package1");
        assertThat(mSysConfig.getModulesInstallerPackageName()).isNull();
    }

    @Test
    public void readPermissions_parsesStagedInstallerWhitelist_modulesInstaller()
            throws IOException {
        final String contents =
                "<config>\n"
                + "    <whitelisted-staged-installer package=\"com.android.package1\" "
                + "         isModulesInstaller=\"true\" />\n"
                + "</config>";
        final File folder = createTempSubfolder("folder");
        createTempFile(folder, "staged-installer-whitelist.xml", contents);

        readPermissions(folder, /* Grant all permission flags */ ~0);

        assertThat(mSysConfig.getWhitelistedStagedInstallers())
                .containsExactly("com.android.package1");
        assertThat(mSysConfig.getModulesInstallerPackageName())
                .isEqualTo("com.android.package1");
    }

    @Test
    public void readPermissions_parsesStagedInstallerWhitelist_multipleModulesInstallers()
            throws IOException {
        final String contents =
                "<config>\n"
                + "    <whitelisted-staged-installer package=\"com.android.package1\" "
                + "         isModulesInstaller=\"true\" />\n"
                + "    <whitelisted-staged-installer package=\"com.android.package2\" "
                + "         isModulesInstaller=\"true\" />\n"
                + "</config>";
        final File folder = createTempSubfolder("folder");
        createTempFile(folder, "staged-installer-whitelist.xml", contents);

        IllegalStateException e = expectThrows(
                IllegalStateException.class,
                () -> readPermissions(folder, /* Grant all permission flags */ ~0));

        assertThat(e).hasMessageThat().contains("Multiple modules installers");
    }

    /**
     * Tests that readPermissions works correctly without {@link SystemConfig#ALLOW_APP_CONFIGS}
     * permission flag for the tag: allowlisted-staged-installer.
     */
    @Test
    public void readPermissions_notAllowAppConfigs_wontParseStagedInstallerWhitelist()
            throws IOException {
        final String contents =
                "<config>\n"
                + "    <whitelisted-staged-installer package=\"com.android.package1\" />\n"
                + "</config>";
        final File folder = createTempSubfolder("folder");
        createTempFile(folder, "staged-installer-whitelist.xml", contents);

        readPermissions(folder, /* Grant all but ALLOW_APP_CONFIGS flag */ ~0x08);

        assertThat(mSysConfig.getWhitelistedStagedInstallers()).isEmpty();
    }

    /**
     * Tests that readPermissions works correctly with {@link SystemConfig#ALLOW_VENDOR_APEX}
     * permission flag for the tag: {@code allowed-vendor-apex}.
     */
    @Test
    public void readPermissions_allowVendorApex_parsesVendorApexAllowList()
            throws IOException {
        final String contents =
                "<config>\n"
                        + "    <allowed-vendor-apex package=\"com.android.apex1\" "
                        + "installerPackage=\"com.installer\" />\n"
                        + "</config>";
        final File folder = createTempSubfolder("folder");
        createTempFile(folder, "vendor-apex-allowlist.xml", contents);

        readPermissions(folder, /* Grant all permission flags */ ~0);

        assertThat(mSysConfig.getAllowedVendorApexes())
                .containsExactly("com.android.apex1", "com.installer");
    }

    /**
     * Tests that readPermissions works correctly with {@link SystemConfig#ALLOW_VENDOR_APEX}
     * permission flag for the tag: {@code allowed-vendor-apex}.
     */
    @Test
    public void readPermissions_allowVendorApex_parsesVendorApexAllowList_noPackage()
            throws IOException {
        final String contents =
                "<config>\n"
                        + "    <allowed-vendor-apex/>\n"
                        + "</config>";
        final File folder = createTempSubfolder("folder");
        createTempFile(folder, "vendor-apex-allowlist.xml", contents);

        readPermissions(folder, /* Grant all permission flags */ ~0);

        assertThat(mSysConfig.getAllowedVendorApexes()).isEmpty();
    }


    /**
     * Tests that readPermissions works correctly without {@link SystemConfig#ALLOW_VENDOR_APEX}
     * permission flag for the tag: {@code allowed-oem-apex}.
     */
    @Test
    public void readPermissions_notAllowVendorApex_doesNotParseVendorApexAllowList()
            throws IOException {
        final String contents =
                "<config>\n"
                        + "    <allowed-vendor-apex package=\"com.android.apex1\" />\n"
                        + "</config>";
        final File folder = createTempSubfolder("folder");
        createTempFile(folder, "vendor-apex-allowlist.xml", contents);

        readPermissions(folder, /* Grant all but ALLOW_VENDOR_APEX flag */ ~0x400);

        assertThat(mSysConfig.getAllowedVendorApexes()).isEmpty();
    }

    /**
     * Tests that readPermissions works correctly for a library with on-bootclasspath-before
     * and on-bootclasspath-since.
     */
    @Test
    public void readPermissions_allowLibs_parsesSimpleLibrary() throws IOException {
        String contents =
                "<permissions>\n"
                + "    <library \n"
                + "        name=\"foo\"\n"
                + "        file=\"" + mFooJar + "\"\n"
                + "        on-bootclasspath-before=\"10\"\n"
                + "        on-bootclasspath-since=\"20\"\n"
                + "     />\n\n"
                + " </permissions>";
        parseSharedLibraries(contents);
        assertFooIsOnlySharedLibrary();
        SystemConfig.SharedLibraryEntry entry = mSysConfig.getSharedLibraries().get("foo");
        assertThat(entry.onBootclasspathBefore).isEqualTo(10);
        assertThat(entry.onBootclasspathSince).isEqualTo(20);
    }

    /**
     * Tests that readPermissions works correctly for a library using the new
     * {@code apex-library} tag.
     */
    @Test
    public void readPermissions_allowLibs_parsesUpdatableLibrary() throws IOException {
        String contents =
                "<permissions>\n"
                        + "    <apex-library \n"
                        + "        name=\"foo\"\n"
                        + "        file=\"" + mFooJar + "\"\n"
                        + "        on-bootclasspath-before=\"10\"\n"
                        + "        on-bootclasspath-since=\"20\"\n"
                        + "     />\n\n"
                        + " </permissions>";
        parseSharedLibraries(contents);
        assertFooIsOnlySharedLibrary();
        SystemConfig.SharedLibraryEntry entry = mSysConfig.getSharedLibraries().get("foo");
        assertThat(entry.onBootclasspathBefore).isEqualTo(10);
        assertThat(entry.onBootclasspathSince).isEqualTo(20);
    }

    /**
     * Tests that readPermissions for a library with {@code min-device-sdk} lower than the current
     * SDK results in the library being added to the shared libraries.
     */
    @Test
    public void readPermissions_allowLibs_allowsOldMinSdk() throws IOException {
        String contents =
                "<permissions>\n"
                + "    <library \n"
                + "        name=\"foo\"\n"
                + "        file=\"" + mFooJar + "\"\n"
                + "        min-device-sdk=\"30\"\n"
                + "     />\n\n"
                + " </permissions>";
        parseSharedLibraries(contents);
        assertFooIsOnlySharedLibrary();
    }

    /**
     * Tests that readPermissions for a library with {@code min-device-sdk} equal to the current
     * SDK results in the library being added to the shared libraries.
     */
    @Test
    public void readPermissions_allowLibs_allowsCurrentMinSdk() throws IOException {
        String contents =
                "<permissions>\n"
                + "    <library \n"
                + "        name=\"foo\"\n"
                + "        file=\"" + mFooJar + "\"\n"
                + "        min-device-sdk=\"" + Build.VERSION.SDK_INT + "\"\n"
                + "     />\n\n"
                + " </permissions>";
        parseSharedLibraries(contents);
        assertFooIsOnlySharedLibrary();
    }

    /**
     * Tests that readPermissions for a library with {@code min-device-sdk} greater than the current
     * SDK results in the library being ignored.
     */
    @Test
    public void readPermissions_allowLibs_ignoresMinSdkInFuture() throws IOException {
        String contents =
                "<permissions>\n"
                + "    <library \n"
                + "        name=\"foo\"\n"
                + "        file=\"" + mFooJar + "\"\n"
                + "        min-device-sdk=\"" + (Build.VERSION.SDK_INT + 1) + "\"\n"
                + "     />\n\n"
                + " </permissions>";
        parseSharedLibraries(contents);
        assertThat(mSysConfig.getSharedLibraries()).isEmpty();
    }

    /**
     * Tests that readPermissions for a library with {@code max-device-sdk} less than the current
     * SDK results in the library being ignored.
     */
    @Test
    public void readPermissions_allowLibs_ignoredOldMaxSdk() throws IOException {
        String contents =
                "<permissions>\n"
                + "    <library \n"
                + "        name=\"foo\"\n"
                + "        file=\"" + mFooJar + "\"\n"
                + "        max-device-sdk=\"30\"\n"
                + "     />\n\n"
                + " </permissions>";
        parseSharedLibraries(contents);
        assertThat(mSysConfig.getSharedLibraries()).isEmpty();
    }

    /**
     * Tests that readPermissions for a library with {@code max-device-sdk} equal to the current
     * SDK results in the library being added to the shared libraries.
     */
    @Test
    public void readPermissions_allowLibs_allowsCurrentMaxSdk() throws IOException {
        String contents =
                "<permissions>\n"
                + "    <library \n"
                + "        name=\"foo\"\n"
                + "        file=\"" + mFooJar + "\"\n"
                + "        max-device-sdk=\"" + Build.VERSION.SDK_INT + "\"\n"
                + "     />\n\n"
                + " </permissions>";
        parseSharedLibraries(contents);
        assertFooIsOnlySharedLibrary();
    }

    /**
     * Tests that readPermissions for a library with {@code max-device-sdk} greater than the current
     * SDK results in the library being added to the shared libraries.
     */
    @Test
    public void readPermissions_allowLibs_allowsMaxSdkInFuture() throws IOException {
        String contents =
                "<permissions>\n"
                + "    <library \n"
                + "        name=\"foo\"\n"
                + "        file=\"" + mFooJar + "\"\n"
                + "        max-device-sdk=\"" + (Build.VERSION.SDK_INT + 1) + "\"\n"
                + "     />\n\n"
                + " </permissions>";
        parseSharedLibraries(contents);
        assertFooIsOnlySharedLibrary();
    }

    private void parseSharedLibraries(String contents) throws IOException {
        File folder = createTempSubfolder("permissions_folder");
        createTempFile(folder, "permissions.xml", contents);
        readPermissions(folder, /* permissionFlag = ALLOW_LIBS */ 0x02);
    }

    /**
     * Creates folderName/fileName in the mTemporaryFolder and fills it with the contents.
     *
     * @param folderName subdirectory of mTemporaryFolder to put the file, creating if needed
     * @return the folder
     */
    private File createTempSubfolder(String folderName)
            throws IOException {
        File folder = new File(mTemporaryFolder.getRoot(), folderName);
        folder.mkdir();
        return folder;
    }

    /**
     * Creates folderName/fileName in the mTemporaryFolder and fills it with the contents.
     *
     * @param folder   pre-existing subdirectory of mTemporaryFolder to put the file
     * @param fileName name of the file (e.g. filename.xml) to create
     * @param contents contents to write to the file
     * @return the newly created file
     */
    private File createTempFile(File folder, String fileName, String contents)
            throws IOException {
        File file = new File(folder, fileName);
        BufferedWriter bw = new BufferedWriter(new FileWriter(file));
        bw.write(contents);
        bw.close();

        // Print to logcat for test debugging.
        Log.d(LOG_TAG, "Contents of file " + file.getAbsolutePath());
        Scanner input = new Scanner(file);
        while (input.hasNextLine()) {
            Log.d(LOG_TAG, input.nextLine());
        }

        return file;
    }

    private void assertFooIsOnlySharedLibrary() {
        assertThat(mSysConfig.getSharedLibraries().size()).isEqualTo(1);
        SystemConfig.SharedLibraryEntry entry = mSysConfig.getSharedLibraries().get("foo");
        assertThat(entry.name).isEqualTo("foo");
        assertThat(entry.filename).isEqualTo(mFooJar.toString());
    }
}
