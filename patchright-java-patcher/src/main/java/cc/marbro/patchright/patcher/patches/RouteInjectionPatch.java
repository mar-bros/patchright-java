package cc.marbro.patchright.patcher.patches;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.stmt.*;
import com.github.javaparser.ast.type.PrimitiveType;
import cc.marbro.patchright.patcher.assist.PatchUtil;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Adds InitScript route injection to BrowserContextImpl and PageImpl.
 * 
 * This intercepts document requests and rewrites them to trigger
 * patchright-init-script-inject.internal, ensuring that init scripts
 * are executed before any page scripts.
 * 
 * Mirrors the .NET Patchright implementation:
 * - Adds routeInjecting field and installInjectRoute() method
 * - Calls installInjectRoute() from addInitScript() and exposeBinding()
 */
public class RouteInjectionPatch {

    public static void apply(final Path playwrightPath) throws IOException {
        final Path implDir = playwrightPath.resolve("playwright/src/main/java/com/microsoft/playwright/impl");

        patchBrowserContextImpl(implDir.resolve("BrowserContextImpl.java"));
        patchPageImpl(implDir.resolve("PageImpl.java"));
        patchClockImpl(implDir.resolve("ClockImpl.java"));
        patchTracingImpl(implDir);
    }

    private static void patchBrowserContextImpl(final Path filePath) throws IOException {
        final CompilationUnit cu = PatchUtil.parsePreserving(filePath);

        final ClassOrInterfaceDeclaration clazz = requireClass(cu, "BrowserContextImpl");

        if (hasField(clazz, "routeInjecting")) {
            System.out.println("  SKIP: BrowserContextImpl already patched");
            return;
        }

        // Add field: boolean routeInjecting = false; (package-private so PageImpl can access)
        FieldDeclaration field = new FieldDeclaration();
        VariableDeclarator var = new VariableDeclarator(PrimitiveType.booleanType(), "routeInjecting",
            new BooleanLiteralExpr(false));
        field.addVariable(var);
        clazz.addMember(field);

        // Add installInjectRoute() method
        addInstallInjectRouteMethod(clazz, false);

        // Patch addInitScript(String) to call installInjectRoute() first
        injectCallAtMethodStart(clazz, "addInitScript", "installInjectRoute();",
            m -> m.getParameters().size() == 1 && m.getParameter(0).getTypeAsString().equals("String"));

        // Patch exposeBindingImpl to call installInjectRoute() first
        injectCallAtMethodStart(clazz, "exposeBindingImpl", "installInjectRoute();", null);

        PatchUtil.writeCompilationUnit(cu, filePath);
        System.out.println("  Patched: BrowserContextImpl (route injection)");
    }

    private static void patchPageImpl(final Path filePath) throws IOException {
        final CompilationUnit cu = PatchUtil.parsePreserving(filePath);

        final ClassOrInterfaceDeclaration clazz = requireClass(cu, "PageImpl");

        if (hasField(clazz, "routeInjecting")) {
            System.out.println("  SKIP: PageImpl already patched");
            return;
        }

        // Add field: boolean routeInjecting = false;
        FieldDeclaration field = clazz.addField(PrimitiveType.booleanType(), "routeInjecting",
            Modifier.Keyword.PRIVATE);
        field.getVariable(0).setInitializer(new BooleanLiteralExpr(false));

        // Add installInjectRoute() method (checks both page and context)
        addInstallInjectRouteMethod(clazz, true);

        // Patch addInitScriptImpl to call installInjectRoute() first
        injectCallAtMethodStart(clazz, "addInitScriptImpl", "installInjectRoute();", null);

        // Patch exposeBindingImpl to call installInjectRoute() first
        injectCallAtMethodStart(clazz, "exposeBindingImpl", "installInjectRoute();", null);

        PatchUtil.writeCompilationUnit(cu, filePath);
        System.out.println("  Patched: PageImpl (route injection)");
    }

    private static void patchClockImpl(final Path filePath) throws IOException {
        final CompilationUnit cu = PatchUtil.parsePreserving(filePath);

        // browserContext field is declared as ChannelOwner, must cast
        injectCallAtMethodStart(requireClass(cu, "ClockImpl"), "install",
            "((BrowserContextImpl) browserContext).installInjectRoute();", null);

        PatchUtil.writeCompilationUnit(cu, filePath);
        System.out.println("  Patched: ClockImpl (route injection)");
    }

    // TRACING MAY ALSO BELONG TO A STANDALONE APIRequestContext, SO THE CURRENT PARENT IS CHECKED ON EACH start()
    // THE PARENT CHANGES WHEN THE CONTEXT ADOPTS ITS TRACING, SO IT CAN'T BE CAPTURED IN THE CONSTRUCTOR;
    // ChannelOwner.parent GETS PACKAGE-PRIVATE TO BE READABLE FROM TracingImpl
    private static void patchTracingImpl(final Path implDir) throws IOException {
        final Path ownerPath = implDir.resolve("ChannelOwner.java");
        final CompilationUnit owner = PatchUtil.parsePreserving(ownerPath);
        requireClass(owner, "ChannelOwner").getFieldByName("parent")
            .orElseThrow(() -> new IllegalStateException("ChannelOwner.parent not found in " + ownerPath))
            .removeModifier(Modifier.Keyword.PRIVATE);
        PatchUtil.writeCompilationUnit(owner, ownerPath);

        final Path tracingPath = implDir.resolve("TracingImpl.java");
        final CompilationUnit cu = PatchUtil.parsePreserving(tracingPath);
        injectCallAtMethodStart(requireClass(cu, "TracingImpl"), "start",
            "if (parent instanceof BrowserContextImpl) { ((BrowserContextImpl) parent).installInjectRoute(); }", null);

        PatchUtil.writeCompilationUnit(cu, tracingPath);
        System.out.println("  Patched: TracingImpl (route injection)");
    }

    // --- Helper methods ---

    /**
     * Add the installInjectRoute() method to a class.
     * @param isPage if true, also checks context.routeInjecting
     */
    private static void addInstallInjectRouteMethod(ClassOrInterfaceDeclaration clazz, boolean isPage) {
        String contextCheck = isPage ?
            "if (routeInjecting || browserContext.routeInjecting) { return; }" :
            "if (routeInjecting) { return; }";

        String routeBody;
        if (isPage) {
            routeBody = String.join("\n",
                "void installInjectRoute() {",
                "    " + contextCheck,
                "    route(\"**/*\", route -> {",
                "        try {",
                "            com.microsoft.playwright.Request request = route.request();",
                "            if (\"document\".equals(request.resourceType())",
                "                && request.url().startsWith(\"http\")) {",
                "                String protocol = request.url().split(\":\")[0];",
                "                route.fallback(new com.microsoft.playwright.Route.FallbackOptions()",
                "                    .setUrl(protocol + \"://patchright-init-script-inject.internal/\"));",
                "            } else {",
                "                route.fallback();",
                "            }",
                "        } catch (Exception e) {",
                "            try { route.fallback(); } catch (Exception ignored) {}",
                "        }",
                "    });",
                "    routeInjecting = true;",
                "}");
        } else {
            routeBody = String.join("\n",
                "void installInjectRoute() {",
                "    " + contextCheck,
                "    route(\"**/*\", route -> {",
                "        try {",
                "            com.microsoft.playwright.Request request = route.request();",
                "            if (\"document\".equals(request.resourceType())",
                "                && request.url().startsWith(\"http\")) {",
                "                String protocol = request.url().split(\":\")[0];",
                "                route.fallback(new com.microsoft.playwright.Route.FallbackOptions()",
                "                    .setUrl(protocol + \"://patchright-init-script-inject.internal/\"));",
                "            } else {",
                "                route.fallback();",
                "            }",
                "        } catch (Exception e) {",
                "            try { route.fallback(); } catch (Exception ignored) {}",
                "        }",
                "    });",
                "    routeInjecting = true;",
                "}");
        }

        MethodDeclaration method = StaticJavaParser.parseMethodDeclaration(routeBody);
        clazz.addMember(method);
    }

    private static boolean hasField(ClassOrInterfaceDeclaration clazz, String name) {
        return clazz.getFields().stream()
            .flatMap(f -> f.getVariables().stream())
            .anyMatch(v -> v.getNameAsString().equals(name));
    }

    @FunctionalInterface
    interface MethodFilter {
        boolean test(MethodDeclaration m);
    }

    private static void injectCallAtMethodStart(final ClassOrInterfaceDeclaration clazz,
                                                 final String methodName, final String statement,
                                                 final MethodFilter extraFilter) {
        final List<MethodDeclaration> methods = clazz.findAll(MethodDeclaration.class, m ->
            m.getNameAsString().equals(methodName) && m.getBody().isPresent() &&
            (extraFilter == null || extraFilter.test(m)));
        if (methods.isEmpty())
            throw new IllegalStateException(clazz.getNameAsString() + "." + methodName + "() not found");

        for (final MethodDeclaration method: methods) {
            final BlockStmt body = method.getBody().get();
            if (!body.toString().contains("installInjectRoute"))
                body.getStatements().addFirst(StaticJavaParser.parseStatement(statement));
        }
    }

    private static ClassOrInterfaceDeclaration requireClass(final CompilationUnit cu, final String className) {
        return cu.getClassByName(className)
            .orElseThrow(() -> new IllegalStateException(className + " not found"));
    }
}
