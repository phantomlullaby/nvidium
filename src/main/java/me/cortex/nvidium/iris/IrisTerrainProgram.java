package me.cortex.nvidium.iris;

import com.mojang.renderpearl.api.vertex.VertexFormat;
import com.mojang.renderpearl.api.vertex.VertexFormatElement;
import me.cortex.nvidium.Nvidium;
import net.fabricmc.loader.api.FabricLoader;
import net.irisshaders.iris.pipeline.programs.ExtendedShader;
import net.irisshaders.iris.pipeline.programs.IrisBindings;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL31C;
import org.lwjgl.opengl.GL45C;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.IntFunction;

/**
 * A shader pack sodium terrain program (Iris patched vertex shader adapted by {@link IrisVertexPatcher}, unchanged fragment
 * shader), with a vertex array reading Iris' chunk vertex format from nvidium's terrain buffer. Uniforms, samplers,
 * images, the target framebuffer and blend overrides are handled by an Iris {@link ExtendedShader} built around the program.
 */
final class IrisTerrainProgram {
    //UBO binding of Iris' sodium u_Globals block, binding 0 is nvidium's scene data
    static final int GLOBALS_BINDING = 1;

    //Vertex buffer bindings of the vertex array
    private static final int VERTEX_BINDING = 0;
    private static final int OFFSET_BINDING = 1;

    private final ExtendedShader shader;
    private final int vertexArray;

    private IrisTerrainProgram(ExtendedShader shader, int vertexArray) {
        this.shader = shader;
        this.vertexArray = vertexArray;
    }

    static IrisTerrainProgram compile(String name, String vertex, String fragment, VertexFormat format, IntFunction<ExtendedShader> shaderFactory) {
        if (Nvidium.IS_DEBUG || IrisCompat.irisDebugEnabled()) {
            dump(name, vertex, fragment);
        }

        List<VertexFormatElement> elements = format.getElements();
        int program = GL20C.glCreateProgram();
        int vertexShader = 0;
        int fragmentShader = 0;
        try {
            vertexShader = compileStage(name, GL20C.GL_VERTEX_SHADER, vertex);
            fragmentShader = compileStage(name, GL20C.GL_FRAGMENT_SHADER, fragment);
            GL20C.glAttachShader(program, vertexShader);
            GL20C.glAttachShader(program, fragmentShader);
            for (int i = 0; i < elements.size(); i++) {
                GL20C.glBindAttribLocation(program, i, elements.get(i).name());
            }
            GL20C.glBindAttribLocation(program, elements.size(), IrisVertexPatcher.REGION_OFFSET_ATTRIBUTE);
            GL20C.glLinkProgram(program);
            if (GL20C.glGetProgrami(program, GL20C.GL_LINK_STATUS) != GL20C.GL_TRUE) {
                throw new IllegalStateException("Linking " + name + " failed: " + GL20C.glGetProgramInfoLog(program));
            }
        } catch (RuntimeException e) {
            GL20C.glDeleteProgram(program);
            dump(name, vertex, fragment);
            throw e;
        } finally {
            for (int shader : new int[]{vertexShader, fragmentShader}) {
                if (shader != 0) {
                    GL20C.glDetachShader(program, shader);
                    GL20C.glDeleteShader(shader);
                }
            }
        }

        //Same sampler units / block bindings Iris gives its own programs, except u_Globals which would collide with the scene data
        IrisBindings.apply(program);
        int globals = GL31C.glGetUniformBlockIndex(program, "u_Globals");
        if (globals != GL31C.GL_INVALID_INDEX) {
            GL31C.glUniformBlockBinding(program, globals, GLOBALS_BINDING);
        }

        return new IrisTerrainProgram(shaderFactory.apply(program), createVertexArray(elements));
    }

    private static int createVertexArray(List<VertexFormatElement> elements) {
        int vertexArray = GL45C.glCreateVertexArrays();
        for (int i = 0; i < elements.size(); i++) {
            VertexFormatElement element = elements.get(i);
            GL45C.glEnableVertexArrayAttrib(vertexArray, i);
            GL45C.glVertexArrayAttribBinding(vertexArray, i, VERTEX_BINDING);
            int offset = element.offset();
            switch (element.format()) {
                case RG32_UINT -> GL45C.glVertexArrayAttribIFormat(vertexArray, i, 2, GL11C.GL_UNSIGNED_INT, offset);
                case R32_UINT -> GL45C.glVertexArrayAttribIFormat(vertexArray, i, 1, GL11C.GL_UNSIGNED_INT, offset);
                case RG16_UINT -> GL45C.glVertexArrayAttribIFormat(vertexArray, i, 2, GL11C.GL_UNSIGNED_SHORT, offset);
                case RGBA8_UINT -> GL45C.glVertexArrayAttribIFormat(vertexArray, i, 4, GL11C.GL_UNSIGNED_BYTE, offset);
                case RGBA8_UNORM -> GL45C.glVertexArrayAttribFormat(vertexArray, i, 4, GL11C.GL_UNSIGNED_BYTE, true, offset);
                case RGBA8_SNORM -> GL45C.glVertexArrayAttribFormat(vertexArray, i, 4, GL11C.GL_BYTE, true, offset);
                default -> throw new IllegalStateException("Unsupported chunk vertex attribute " + element);
            }
        }

        //The camera relative region origin, one per draw (instanced, indexed with the draw's base instance)
        int offset = elements.size();
        GL45C.glEnableVertexArrayAttrib(vertexArray, offset);
        GL45C.glVertexArrayAttribBinding(vertexArray, offset, OFFSET_BINDING);
        GL45C.glVertexArrayAttribFormat(vertexArray, offset, 3, GL11C.GL_FLOAT, false, 0);
        GL45C.glVertexArrayBindingDivisor(vertexArray, OFFSET_BINDING, 1);
        return vertexArray;
    }

    void bindBuffers(int terrainBuffer, int stride, int offsetsBuffer, int indexBuffer) {
        GL45C.glVertexArrayVertexBuffer(this.vertexArray, VERTEX_BINDING, terrainBuffer, 0, stride);
        GL45C.glVertexArrayVertexBuffer(this.vertexArray, OFFSET_BINDING, offsetsBuffer, 0, 16);
        GL45C.glVertexArrayElementBuffer(this.vertexArray, indexBuffer);
    }

    int vertexArray() {
        return this.vertexArray;
    }

    //Applies the Iris state for the program and binds it
    void begin() {
        this.shader.iris$setupState(List.of());
    }

    void end() {
        this.shader.iris$clearState();
    }

    void delete() {
        this.shader.close();
        GL45C.glDeleteVertexArrays(this.vertexArray);
    }

    private static int compileStage(String name, int type, String source) {
        int shader = GL20C.glCreateShader(type);
        GL20C.glShaderSource(shader, source);
        GL20C.glCompileShader(shader);
        if (GL20C.glGetShaderi(shader, GL20C.GL_COMPILE_STATUS) != GL20C.GL_TRUE) {
            String log = GL20C.glGetShaderInfoLog(shader);
            GL20C.glDeleteShader(shader);
            throw new IllegalStateException("Compiling " + (type == GL20C.GL_VERTEX_SHADER ? "vertex" : "fragment") + " shader of " + name + " failed: " + log);
        }
        return shader;
    }

    private static void dump(String name, String vertex, String fragment) {
        Path dir = FabricLoader.getInstance().getGameDir().resolve("nvidium_iris_shaders");
        try {
            Files.createDirectories(dir);
            Files.writeString(dir.resolve(name + ".vsh"), vertex);
            Files.writeString(dir.resolve(name + ".fsh"), fragment);
        } catch (IOException e) {
            Nvidium.LOGGER.warn("Could not dump Iris terrain shaders for {}", name, e);
        }
    }
}
