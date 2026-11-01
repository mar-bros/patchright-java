package cc.marbro.patchright.patcher.patches;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Patches Maven POM metadata to rebrand playwright-java as patchright-java.
 * <p>
 * Changes:
 * - groupId: com.microsoft.playwright -> user-specified groupId
 * - artifactId: playwright -> patchright
 * - Package descriptions and URLs
 */
public class MetadataPatch {

    public static void apply(final Path playwrightPath, final String newGroupId, final String version) throws IOException {

        // Patch all pom.xml files
        patchPomFiles(playwrightPath, newGroupId);

        // Patch version if specified
        if (version != null) {
            // TODO 无需修改版本号，sh 里面已经切换到了 tag，
            //  TODO 如果要修改，也是使用 mvn versions:set -DnewVersion=xxx 来修改版本号，直接修改 pom.xml 可能会导致版本不一致，下载失败
            // patchVersion(playwrightPath, version);
        }
    }

    private static void patchPomFiles(final Path playwrightPath, final String newGroupId) throws IOException {
        // {regex, replacement}; EVERY RULE MUST MATCH SOME POM UNLESS AN EARLIER RUN ALREADY APPLIED IT
        final String[][] rules = {
                // Replace groupId
                {Pattern.quote("<groupId>com.microsoft.playwright</groupId>"), "<groupId>" + newGroupId + "</groupId>"},
                // Replace main artifactId (playwright -> patchright); driver AND driver-bundle KEEP THEIR artifactIds
                {Pattern.quote("<artifactId>playwright</artifactId>"), "<artifactId>patchright</artifactId>"},
                // UPDATE NAMES ("Playwright - Main Library", "Playwright Parent Project", ...)
                {Pattern.quote("<name>Playwright"), "<name>Patchright"},
                // Update description
                {"(?<!Undetected )Java library to automate", "Undetected Java library to automate"},
                // Update URLs
                {Pattern.quote("https://github.com/microsoft/playwright-java"), "https://github.com/mar-bros/patchright-java"},
        };
        final int[] matches = new int[rules.length];
        final boolean[] applied = new boolean[rules.length];

        Files.walkFileTree(playwrightPath, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(final Path file, final BasicFileAttributes attrs) throws IOException {
                if (!file.getFileName().toString().equals("pom.xml")) return FileVisitResult.CONTINUE;

                final String original = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
                String content = original;
                for (int i = 0; i < rules.length; i++) {
                    final Matcher matcher = Pattern.compile(rules[i][0]).matcher(content);
                    while (matcher.find()) matches[i]++;
                    content = matcher.replaceAll(Matcher.quoteReplacement(rules[i][1]));
                    if (content.contains(rules[i][1])) applied[i] = true;
                }

                if (!content.equals(original)) {
                    Files.write(file, content.getBytes(StandardCharsets.UTF_8));
                    System.out.println("  Patched: " + file);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult preVisitDirectory(final Path dir, final BasicFileAttributes attrs) {
                // Skip hidden directories
                if (dir.getFileName().toString().startsWith(".")) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }
        });

        for (int i = 0; i < rules.length; i++)
            if (!applied[i])
                throw new IllegalStateException("No pom.xml under " + playwrightPath + " matches '" + rules[i][0] + "'");
        System.out.println("  Metadata rule matches: " + Arrays.toString(matches));
    }

    private static void patchVersion(Path playwrightPath, String version) throws IOException {
        // The version is typically in the root pom.xml
        Path rootPom = playwrightPath.resolve("pom.xml");
        if (!rootPom.toFile().exists()) {
            return;
        }

        String content = new String(Files.readAllBytes(rootPom), StandardCharsets.UTF_8);

        // Replace the first <version> after the <artifactId> in the project declaration
        // This is fragile but matches the typical Maven POM structure
        content = content.replaceFirst(
                "(<artifactId>patchright</artifactId>\\s*<version>)[^<]+(</version>)",
                "$1" + version + "$2");

        Files.write(rootPom, content.getBytes(StandardCharsets.UTF_8));
        System.out.println("  Patched version to: " + version);
    }
}
