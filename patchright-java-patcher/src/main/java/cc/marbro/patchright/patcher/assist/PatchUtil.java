package cc.marbro.patchright.patcher.assist;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Utility methods shared across patch classes.
 */
public class PatchUtil {

    /**
     * Parse a Java file with lexical preservation enabled.
     * This ensures formatting is preserved when writing back.
     */
    public static CompilationUnit parsePreserving(Path filePath) throws IOException {
        CompilationUnit cu = StaticJavaParser.parse(filePath);
        LexicalPreservingPrinter.setup(cu);
        return cu;
    }

    /**
     * Write a CompilationUnit back to a file, preserving the original formatting.
     * Uses LexicalPreservingPrinter which only changes the parts that were modified.
     */
    public static void writeCompilationUnit(CompilationUnit cu, Path filePath) throws IOException {
        String output;
        try {
            output = LexicalPreservingPrinter.print(cu);
        } catch (Exception e) {
            // Fallback to default printer if LexicalPreservingPrinter fails
            System.out.println("  WARNING: LexicalPreservingPrinter failed, using default printer for " + filePath.getFileName());
            output = cu.toString();
        }
        Files.write(filePath, output.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Read file content as string.
     */
    public static String readFile(Path filePath) throws IOException {
        return new String(Files.readAllBytes(filePath), StandardCharsets.UTF_8);
    }

    /**
     * Write string content to file.
     */
    public static void writeFile(Path filePath, String content) throws IOException {
        Files.write(filePath, content.getBytes(StandardCharsets.UTF_8));
    }
}
