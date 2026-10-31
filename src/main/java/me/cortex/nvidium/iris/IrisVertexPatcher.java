package me.cortex.nvidium.iris;

import io.github.douira.glsl_transformer.ast.node.Identifier;
import io.github.douira.glsl_transformer.ast.node.TranslationUnit;
import io.github.douira.glsl_transformer.ast.node.Version;
import io.github.douira.glsl_transformer.ast.node.declaration.InterfaceBlockDeclaration;
import io.github.douira.glsl_transformer.ast.node.expression.ReferenceExpression;
import io.github.douira.glsl_transformer.ast.node.expression.unary.FunctionCallExpression;
import io.github.douira.glsl_transformer.ast.node.external_declaration.DeclarationExternalDeclaration;
import io.github.douira.glsl_transformer.ast.node.external_declaration.ExternalDeclaration;
import io.github.douira.glsl_transformer.ast.print.PrintType;
import io.github.douira.glsl_transformer.ast.query.Root;
import io.github.douira.glsl_transformer.ast.query.RootSupplier;
import io.github.douira.glsl_transformer.ast.transform.ASTInjectionPoint;
import io.github.douira.glsl_transformer.ast.transform.JobParameters;
import io.github.douira.glsl_transformer.ast.transform.SingleASTTransformer;

import java.util.ArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Adapts an Iris patched sodium terrain vertex shader to nvidium's draws. Sodium passes the camera relative region
 * origin as a push constant per region, nvidium draws many regions in one multi draw, so the origin becomes a per draw
 * (instanced) vertex attribute instead. Everything else stays the shader pack's code.
 */
public final class IrisVertexPatcher {
    public static final String REGION_OFFSET_ATTRIBUTE = "iris_RegionOffset";

    private static final Pattern VERSION_PATTERN = Pattern.compile("#version\\s+(\\d+)");

    private IrisVertexPatcher() {}

    public static String patch(String vertexSource) {
        Matcher matcher = VERSION_PATTERN.matcher(vertexSource);
        if (!matcher.find()) {
            throw new IllegalArgumentException("Vertex shader has no #version directive");
        }

        var transformer = new SingleASTTransformer<JobParameters>();
        transformer.setRootSupplier(RootSupplier.PREFIX_UNORDERED_ED_EXACT);
        transformer.setPrintType(PrintType.INDENTED);
        transformer.getLexer().version = Version.fromNumber(Integer.parseInt(matcher.group(1)));
        transformer.setTransformation((TranslationUnit tree, Root root) -> {
            removePushConstants(tree);
            removeSectionTimeFetch(transformer, root);
            tree.parseAndInjectNodes(transformer, ASTInjectionPoint.BEFORE_DECLARATIONS,
                    "in vec3 " + REGION_OFFSET_ATTRIBUTE + ";",
                    //Only used for the chunk fade in, which nvidium does not have
                    "const int iris_CurrentTime = 0;",
                    "const uint iris_RegionID = 0u;",
                    //The depth pre-pass program shares this vertex shader, its depth must match the color pass exactly
                    "invariant gl_Position;");
        });
        return transformer.transform(vertexSource);
    }

    private static void removePushConstants(TranslationUnit tree) {
        for (ExternalDeclaration declaration : new ArrayList<>(tree.getChildren())) {
            if (declaration instanceof DeclarationExternalDeclaration external
                    && external.getDeclaration() instanceof InterfaceBlockDeclaration block
                    && block.getBlockName().getName().equals("iris_SodiumPushConstants")) {
                external.detachAndDelete();
                return;
            }
        }
        throw new IllegalStateException("Iris terrain vertex shader has no iris_SodiumPushConstants block, unsupported Iris version?");
    }

    //Sodium's chunk fade in reads a per section build time from a buffer texture that nvidium does not have, use "no time" (-1)
    private static void removeSectionTimeFetch(SingleASTTransformer<JobParameters> transformer, Root root) {
        for (Identifier identifier : new ArrayList<>(root.identifierIndex.get("u_SectionTimeInfo"))) {
            if (identifier.getParent() instanceof ReferenceExpression reference
                    && reference.getParent() instanceof FunctionCallExpression call
                    && call.getFunctionName() != null && call.getFunctionName().getName().equals("texelFetch")) {
                call.replaceByAndDelete(transformer.parseExpression(root, "ivec4(-1)"));
            }
        }
    }
}
