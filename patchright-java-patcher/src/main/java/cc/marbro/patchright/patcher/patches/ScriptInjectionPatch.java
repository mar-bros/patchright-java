package cc.marbro.patchright.patcher.patches;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.expr.NameExpr;
import cc.marbro.patchright.patcher.assist.PatchUtil;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Removes sourceURL annotations from scripts to avoid automation detection.
 * 
 * In the original Playwright, scripts get a sourceURL comment appended,
 * which can reveal automation. Patchright removes this.
 */
public class ScriptInjectionPatch {

    public static void apply(Path playwrightPath) throws IOException {
        patchAddSourceUrlToScript(playwrightPath);
    }

    /**
     * Patches Utils.addSourceUrlToScript() to simply return the source unchanged.
     * 
     * Original:
     *   static String addSourceUrlToScript(String source, Path path) {
     *     return source + "\n//# sourceURL=" + path.toString().replace("\n", "");
     *   }
     * 
     * Patched:
     *   static String addSourceUrlToScript(String source, Path path) {
     *     return source;
     *   }
     */
    private static void patchAddSourceUrlToScript(final Path playwrightPath) throws IOException {
        final Path utilsPath = playwrightPath.resolve(
            "playwright/src/main/java/com/microsoft/playwright/impl/Utils.java");

        final CompilationUnit cu = PatchUtil.parsePreserving(utilsPath);

        final List<MethodDeclaration> methods = cu.findAll(MethodDeclaration.class, m -> m.getNameAsString().equals("addSourceUrlToScript"));
        if (methods.isEmpty())
            throw new IllegalStateException("Utils.addSourceUrlToScript() not found in " + utilsPath);
        for (final MethodDeclaration method: methods) {
            // Replace body with just "return source;"
            final BlockStmt newBody = new BlockStmt();
            newBody.addStatement(new ReturnStmt(new NameExpr("source")));
            method.setBody(newBody);
        }

        PatchUtil.writeCompilationUnit(cu, utilsPath);
        System.out.println("  Patched: Utils.addSourceUrlToScript()");
    }
}
