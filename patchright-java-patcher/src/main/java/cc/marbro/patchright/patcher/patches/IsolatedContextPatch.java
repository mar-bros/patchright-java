package cc.marbro.patchright.patcher.patches;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.stmt.*;
import com.github.javaparser.ast.type.PrimitiveType;
import cc.marbro.patchright.patcher.assist.PatchUtil;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Adds the isolatedContext parameter to all evaluate/evaluateHandle methods.
 *
 * This allows users to control whether JavaScript is executed in the main
 * world or an isolated utility world (default: utility/isolated to avoid detection).
 *
 * Pattern for implementation classes (e.g., FrameImpl, JSHandleImpl):
 * - Add boolean isolatedContext parameter with default value true
 * - Change "world" property from hardcoded "main" to conditional on isolatedContext
 * - Add overload that delegates with default isolatedContext=true
 *
 * Pattern for interface classes (e.g., Frame, JSHandle):
 * - Add new method signature with isolatedContext parameter
 * - Add default method overload without the parameter
 *
 * Pattern for delegation classes (e.g., PageImpl, LocatorImpl):
 * - Add isolatedContext parameter and pass through to delegate
 */
public class IsolatedContextPatch {

    private static final String[] METHODS = {"evaluate", "evaluateHandle"};

    public static void apply(final Path playwrightPath) throws IOException {
        final Path implDir = playwrightPath.resolve("playwright/src/main/java/com/microsoft/playwright/impl");
        final Path apiDir = playwrightPath.resolve("playwright/src/main/java/com/microsoft/playwright");

        // IMPLEMENTATION CLASSES THAT SEND THE evaluateExpression MESSAGE
        for (final String className: new String[]{"FrameImpl", "JSHandleImpl", "WorkerImpl"})
            patchImplClass(implDir.resolve(className + ".java"), className);

        // DELEGATION CLASSES
        for (final String className: new String[]{"PageImpl", "LocatorImpl"})
            patchDelegationClass(implDir.resolve(className + ".java"), className);

        // PUBLIC API INTERFACES
        for (final String interfaceName: new String[]{"Frame", "JSHandle", "Worker", "Page", "Locator"})
            patchInterface(apiDir.resolve(interfaceName + ".java"), interfaceName);
    }

    // ADDS isolatedContext TO THE METHOD BUILDING THE PROTOCOL PARAMS AND SENDS THE WORLD IT SELECTS
    private static void patchImplClass(final Path filePath, final String className) throws IOException {
        final CompilationUnit cu = PatchUtil.parsePreserving(filePath);

        for (final String methodName: METHODS) {
            final MethodDeclaration method = target(cu, className, methodName, false);
            if (method == null) {
                System.out.println("  SKIP: " + className + "." + methodName + "() already has isolatedContext");
                continue;
            }

            final NodeList<Statement> stmts = method.getBody().get().getStatements();
            int expression = -1;
            int world = -1;
            for (int i = 0; i < stmts.size(); i++)
                for (final MethodCallExpr call: stmts.get(i).findAll(MethodCallExpr.class)) {
                    if (!call.getNameAsString().equals("addProperty") || call.getArguments().size() != 2 || !call.getArgument(0).isStringLiteralExpr())
                        continue;
                    final String key = call.getArgument(0).asStringLiteralExpr().getValue();
                    if (key.equals("expression")) expression = i;
                    else if (key.equals("world")) world = i;
                }
            if (expression < 0)
                throw new IllegalStateException(className + "." + methodName + "() no longer sets the expression property");

            // REPLACE THE HARDCODED "main" WORLD IN PLACE, OR ADD IT AFTER THE EXPRESSION WHEN ABSENT (WORKERS)
            final int at = world < 0 ? expression + 1 : world;
            if (world >= 0) stmts.remove(world);
            stmts.add(at, StaticJavaParser.parseStatement("params.addProperty(\"world\", isolatedContext ? \"utility\" : \"main\");"));
            stmts.add(at + 1, StaticJavaParser.parseStatement("params.addProperty(\"isolatedContext\", isolatedContext);"));

            method.addParameter(new Parameter(PrimitiveType.booleanType(), "isolatedContext"));
            addImplOverload(method);
            System.out.println("  Patched: " + className + "." + methodName + "()");
        }

        PatchUtil.writeCompilationUnit(cu, filePath);
    }

    // ADDS isolatedContext TO A METHOD THAT DELEGATES TO ANOTHER evaluate (PageImpl TO mainFrame, LocatorImpl TO THE ELEMENT HANDLE)
    private static void patchDelegationClass(final Path filePath, final String className) throws IOException {
        final CompilationUnit cu = PatchUtil.parsePreserving(filePath);

        for (final String methodName: METHODS) {
            final MethodDeclaration method = target(cu, className, methodName, false);
            if (method == null) {
                System.out.println("  SKIP: " + className + "." + methodName + "() already has isolatedContext");
                continue;
            }

            // "mainFrame.evaluate(expression, arg)" BECOMES "mainFrame.evaluate(expression, arg, isolatedContext)"
            final List<MethodCallExpr> calls = method.findAll(MethodCallExpr.class, c -> c.getNameAsString().equals(methodName) && c.getScope().isPresent());
            if (calls.isEmpty())
                throw new IllegalStateException(className + "." + methodName + "() no longer delegates to another " + methodName + "()");
            for (final MethodCallExpr call: calls)
                call.addArgument(new NameExpr("isolatedContext"));

            method.addParameter(new Parameter(PrimitiveType.booleanType(), "isolatedContext"));
            addImplOverload(method);
            System.out.println("  Patched: " + className + "." + methodName + "()");
        }

        PatchUtil.writeCompilationUnit(cu, filePath);
    }

    // TURNS THE ABSTRACT evaluate INTO A DEFAULT OVERLOAD OF A NEW ABSTRACT ONE TAKING isolatedContext
    // THE AST LOCATES THE DECLARATION BUT THE EDIT IS TEXTUAL, SINCE LexicalPreservingPrinter MISPRINTS setDefault()
    // REUSING THE ORIGINAL TEXT KEEPS ITS ANNOTATIONS (JSPECIFY @Nullable SINCE 1.63)
    private static void patchInterface(final Path filePath, final String interfaceName) throws IOException {
        String content = PatchUtil.readFile(filePath);
        final CompilationUnit cu = StaticJavaParser.parse(content);

        for (final String methodName: METHODS) {
            final MethodDeclaration method = target(cu, interfaceName, methodName, true);
            if (method == null) {
                System.out.println("  SKIP: " + interfaceName + "." + methodName + "() already has isolatedContext");
                continue;
            }

            // E.G. "@Nullable Object evaluate(String expression, @Nullable Object arg);"
            final String decl = method.getTokenRange().get().toString();
            final int start = content.indexOf(decl);
            if (start < 0 || start != content.lastIndexOf(decl))
                throw new IllegalStateException(interfaceName + "." + methodName + "() declaration is not unique in " + filePath.getFileName());
            final String indent = content.substring(content.lastIndexOf('\n', start) + 1, start);
            final int paramsEnd = decl.lastIndexOf(')');

            final StringBuilder args = new StringBuilder();
            for (final Parameter param: method.getParameters())
                args.append(param.getNameAsString()).append(", ");
            final String defaultDecl = "default " + decl.substring(0, paramsEnd + 1) + " {\n"
                + indent + "  return " + methodName + "(" + args + "true);\n"
                + indent + "}";
            final String newAbstractDecl = decl.substring(0, paramsEnd) + ", boolean isolatedContext" + decl.substring(paramsEnd);
            content = content.substring(0, start) + defaultDecl + "\n" + indent + newAbstractDecl + content.substring(start + decl.length());
            System.out.println("  Patched interface: " + interfaceName + "." + methodName + "()");
        }

        PatchUtil.writeFile(filePath, content);
    }

    // --- Helper methods ---

    // RETURNS THE ONLY UPSTREAM DECLARATION TO PATCH, OR NULL WHEN AN isolatedContext VARIANT ALREADY EXISTS
    private static MethodDeclaration target(final CompilationUnit cu, final String typeName, final String methodName, final boolean bodyless) {
        final ClassOrInterfaceDeclaration type = cu.findFirst(ClassOrInterfaceDeclaration.class, c -> c.getNameAsString().equals(typeName))
            .orElseThrow(() -> new IllegalStateException(typeName + " not found"));
        final List<MethodDeclaration> methods = type.getMethodsByName(methodName).stream()
            .filter(m -> m.getBody().isPresent() != bodyless)
            .collect(Collectors.toList());
        for (final MethodDeclaration method: methods)
            if (method.getParameterByName("isolatedContext").isPresent()) return null;
        if (methods.size() != 1)
            throw new IllegalStateException("Expected one " + (bodyless ? "abstract " : "") + typeName + "." + methodName + "(), found " + methods.size());
        return methods.get(0);
    }

    // ADDS A BACKWARD-COMPATIBLE OVERLOAD THAT CALLS THE PATCHED METHOD WITH isolatedContext=true
    private static void addImplOverload(final MethodDeclaration patchedMethod) {
        final ClassOrInterfaceDeclaration parentClass = patchedMethod.findAncestor(ClassOrInterfaceDeclaration.class).get();
        final MethodDeclaration overload = new MethodDeclaration();
        overload.setName(patchedMethod.getNameAsString());
        overload.setType(patchedMethod.getType().clone());
        overload.addAnnotation("Override");
        overload.setModifiers(Modifier.Keyword.PUBLIC);

        // Copy all parameters except isolatedContext
        for (final Parameter param: patchedMethod.getParameters())
            if (!param.getNameAsString().equals("isolatedContext")) overload.addParameter(param.clone());

        // Create body: return this.methodName(param1, param2, ..., true);
        final BlockStmt body = new BlockStmt();
        final MethodCallExpr call = new MethodCallExpr(patchedMethod.getNameAsString());
        for (final Parameter param: overload.getParameters())
            call.addArgument(new NameExpr(param.getNameAsString()));
        call.addArgument(new BooleanLiteralExpr(true)); // default isolatedContext = true

        if (patchedMethod.getType().isVoidType()) body.addStatement(new ExpressionStmt(call));
        else body.addStatement(new ReturnStmt(call));
        overload.setBody(body);

        // Add after the patched method
        final int idx = parentClass.getMembers().indexOf(patchedMethod);
        parentClass.getMembers().add(idx + 1, overload);
    }
}
