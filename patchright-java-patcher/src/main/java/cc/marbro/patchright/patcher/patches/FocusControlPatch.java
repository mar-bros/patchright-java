package cc.marbro.patchright.patcher.patches;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.MarkerAnnotationExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import cc.marbro.patchright.patcher.assist.PatchUtil;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Adds focusControl parameter to browser launch and context creation options.
 * <p>
 * This parameter controls browser focus behavior, preventing focus-related
 * detection of automation.
 * <p>
 * Targets:
 * - BrowserType.LaunchOptions (inner class in BrowserType.java)
 * - BrowserType.LaunchPersistentContextOptions
 * - Browser.NewContextOptions
 * - Browser.NewPageOptions
 * - BrowserTypeImpl.launchImpl() - to pass focusControl to protocol
 * - BrowserImpl.newContext() - to pass focusControl to protocol
 */
public class FocusControlPatch {

    public static void apply(final Path playwrightPath) throws IOException {
        final Path apiDir = playwrightPath.resolve("playwright/src/main/java/com/microsoft/playwright");
        final Path implDir = apiDir.resolve("impl");

        // Add focusControl field to Options classes
        addFocusControlToOptions(apiDir.resolve("BrowserType.java"), new String[]{"LaunchOptions", "LaunchPersistentContextOptions"});
        addFocusControlToOptions(apiDir.resolve("Browser.java"), new String[]{"NewContextOptions", "NewPageOptions"});

        // THE IMPLS NEED NO CHANGE WHILE GSON SERIALIZES THE WHOLE OPTIONS OBJECT, PUBLIC focusControl INCLUDED
        // (NewPageOptions REACHES newContext THROUGH A GSON-BASED convertType)
        requireGsonOptions(implDir.resolve("BrowserTypeImpl.java"), "BrowserTypeImpl", "launch", "launchPersistentContext");
        requireGsonOptions(implDir.resolve("BrowserImpl.java"), "BrowserImpl", "newContext");
    }

    /**
     * Add focusControl field + setter to Options inner classes.
     */
    private static void addFocusControlToOptions(final Path filePath, final String[] classNames) throws IOException {
        final CompilationUnit cu = PatchUtil.parsePreserving(filePath);
        // SINCE 1.63 THE OPTIONS FIELDS ARE JSPECIFY @Nullable INSIDE A @NullMarked PACKAGE
        final boolean jspecify = cu.getImports().stream()
                .anyMatch(i -> i.getNameAsString().equals("org.jspecify.annotations.Nullable"));

        for (final String className: classNames) {
            final List<ClassOrInterfaceDeclaration> classes = cu.findAll(ClassOrInterfaceDeclaration.class,
                    c -> c.getNameAsString().equals(className));
            if (classes.isEmpty())
                throw new IllegalStateException(className + " not found in " + filePath.getFileName());

            for (final ClassOrInterfaceDeclaration clazz: classes) {
                if (hasField(clazz, "focusControl")) continue;

                // Add field: public Boolean focusControl;
                final ClassOrInterfaceType type = new ClassOrInterfaceType(null, "Boolean");
                if (jspecify) type.setAnnotations(new NodeList<>(new MarkerAnnotationExpr("Nullable")));
                clazz.addField(type, "focusControl", Modifier.Keyword.PUBLIC);

                // Add setter method
                String setterBody = String.join("\n",
                        className + " setFocusControl(boolean focusControl) {",
                        "    this.focusControl = focusControl;",
                        "    return this;",
                        "}");
                MethodDeclaration setter = StaticJavaParser.parseMethodDeclaration(setterBody);
                setter.setModifier(Modifier.Keyword.PUBLIC, true);
                clazz.addMember(setter);

                System.out.println("  Added focusControl to " + className);
            }
        }

        PatchUtil.writeCompilationUnit(cu, filePath);
    }

    // FAILS WHEN A METHOD NO LONGER SERIALIZES ITS OPTIONS WITH toJsonTree(options), WHICH WOULD DROP focusControl
    private static void requireGsonOptions(final Path filePath, final String className, final String... methodNames) throws IOException {
        final CompilationUnit cu = StaticJavaParser.parse(filePath);
        for (final String methodName: methodNames) {
            final boolean serialized = cu.findAll(MethodDeclaration.class, m ->
                    m.getNameAsString().equals(methodName)
                    && m.findAncestor(ClassOrInterfaceDeclaration.class).map(c -> c.getNameAsString().equals(className)).orElse(false))
                .stream()
                .flatMap(m -> m.findAll(MethodCallExpr.class, c -> c.getNameAsString().equals("toJsonTree")).stream())
                .anyMatch(c -> c.getArguments().size() == 1 && c.getArgument(0).toString().equals("options"));
            if (!serialized)
                throw new IllegalStateException(className + "." + methodName + "() no longer serializes options via toJsonTree(options), focusControl would be dropped");
            System.out.println("  " + className + "." + methodName + "(): focusControl auto-serialized by Gson (no code change needed)");
        }
    }

    private static boolean hasField(ClassOrInterfaceDeclaration clazz, String name) {
        return clazz.getFields().stream()
                .flatMap(f -> f.getVariables().stream())
                .anyMatch(v -> v.getNameAsString().equals(name));
    }
}
