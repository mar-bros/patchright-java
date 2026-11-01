package cc.marbro.patchright.patcher.patches;

import cc.marbro.patchright.patcher.assist.PatchLog;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Patches the driver download script to use Patchright releases, instead of the official Playwright CDN.
 */
public class DriverPatch {

    /**
     * @link /playwright-java/scripts/DRIVER_VERSION
     */
    private static final String PATCH_DRIVER_VERSION = "scripts/DRIVER_VERSION";
    /**
     * @link /playwright-java/scripts/download_driver.sh
     */
    private static final String PATCH_DOWNLOAD_SH = "scripts/download_driver.sh";

    /**
     * https://github.com/Kaliiiiiiiiii-Vinyzu/patchright/releases
     */
    private static final String OLD_CDN_URL = "https://cdn.playwright.dev/builds/driver";
    private static final String NEW_CDN_URL = "https://github.com/Kaliiiiiiiiii-Vinyzu/patchright/releases/download";

    /**
     * _______________________playwright-${DRIVER_VERSION}-${PLATFORM}.zip
     * v${DRIVER_VERSION}/playwright-${DRIVER_VERSION}-${PLATFORM}.zip
     */
    private static final String OLD_URL_PATTERN = "URL=$URL/$FILE_NAME";
    private static final String NEW_URL_PATTERN = "URL=$URL/v${DRIVER_VERSION}/$FILE_NAME";
    // GITHUB RELEASES ANSWER WITH A REDIRECT, SO THE curl FALLBACK (NO wget) MUST FOLLOW IT AND FAIL ON HTTP ERRORS
    private static final String OLD_CURL = "curl -O $URL";
    private static final String NEW_CURL = "curl -fLO $URL";

    // SINCE 1.63 THE SCRIPT PACKS THE playwright-core NPM PACKAGE; PATCHRIGHT SHIPS THAT DRIVER AS patchright-core
    // (ITS 1.64 RELEASES HAVE NO DRIVER ZIPS)
    private static final String OLD_NPM_PACKAGE = "playwright-core@$DRIVER_VERSION";
    private static final String NEW_NPM_PACKAGE = "patchright-core@$DRIVER_VERSION";
    private static final String OLD_NPM_TARBALL = "playwright-core-$DRIVER_VERSION.tgz";
    private static final String NEW_NPM_TARBALL = "patchright-core-$DRIVER_VERSION.tgz";

    public static void apply(final Path playwrightPath, final String version) throws IOException {
        // 覆盖 DRIVER_VERSION 文件里面的版本，官网正式版中版本号可能会有 子后缀，导致 DownloadScript 读不到文件里面的版本，下载失败
        setPatchDriverVersion(playwrightPath, version);
        //
        patchDownloadScript(playwrightPath);
    }

    private static void setPatchDriverVersion(final Path playwrightPath, final String version) throws IOException {
        final Path scriptPath = playwrightPath.resolve(PATCH_DRIVER_VERSION);
        if (!Files.exists(scriptPath))
            throw new IllegalStateException(PATCH_DRIVER_VERSION + " not found at " + scriptPath);

        // NPM BUILDS KEEP THE DRIVER UPSTREAM PINS (1.64.0 SHIPS 1.64.1), WHICH patchright-core MIRRORS RELEASE BY RELEASE;
        // ZIP BUILDS AND SUFFIXED PINS FALL BACK TO THE JAVA VERSION, LIKE patchright's GITHUB RELEASES
        final String pinned = new String(Files.readAllBytes(scriptPath), StandardCharsets.UTF_8).trim();
        final Path downloadPath = playwrightPath.resolve(PATCH_DOWNLOAD_SH);
        final String script = Files.exists(downloadPath) ? new String(Files.readAllBytes(downloadPath), StandardCharsets.UTF_8) : "";
        final boolean npm = script.contains(OLD_NPM_PACKAGE) || script.contains(NEW_NPM_PACKAGE);
        final String driverVersion = npm && pinned.matches("\\d+\\.\\d+\\.\\d+") ? pinned : version;
        if (driverVersion == null) {
            PatchLog.info("  SKIP: no driver version given, keeping " + scriptPath);
            return;
        }

        Files.write(scriptPath, driverVersion.getBytes(StandardCharsets.UTF_8));
        PatchLog.info("  Patched: " + scriptPath + " -> " + driverVersion);
    }


    private static void patchDownloadScript(final Path playwrightPath) throws IOException {
        final Path scriptPath = playwrightPath.resolve(PATCH_DOWNLOAD_SH);
        if (!Files.exists(scriptPath))
            throw new IllegalStateException(PATCH_DOWNLOAD_SH + " not found at " + scriptPath);

        String content = new String(Files.readAllBytes(scriptPath), StandardCharsets.UTF_8);
        if (content.contains(NEW_CDN_URL) || content.contains(NEW_NPM_PACKAGE)) {
            PatchLog.info("  SKIP: " + scriptPath + " already downloads the patchright driver");
            return;
        }

        // ZIP DOWNLOAD FROM THE PLAYWRIGHT CDN (UP TO 1.60), OTHERWISE npm pack OF playwright-core (1.63+)
        final String[][] replacements = content.contains(OLD_CDN_URL)
            ? new String[][]{{OLD_CDN_URL, NEW_CDN_URL}, {OLD_URL_PATTERN, NEW_URL_PATTERN}, {OLD_CURL, NEW_CURL}}
            : new String[][]{{OLD_NPM_PACKAGE, NEW_NPM_PACKAGE}, {OLD_NPM_TARBALL, NEW_NPM_TARBALL}};
        for (final String[] replacement: replacements) {
            if (!content.contains(replacement[0]))
                throw new IllegalStateException(PATCH_DOWNLOAD_SH + " has no '" + replacement[0] + "' to point at the patchright driver");
            content = content.replace(replacement[0], replacement[1]);
        }

        Files.write(scriptPath, content.getBytes(StandardCharsets.UTF_8));
        PatchLog.info("  Patched: " + scriptPath);
    }
}
