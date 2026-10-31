package me.cortex.nvidium.renderers;

import me.cortex.nvidium.gl.RenderDevice;
import me.cortex.nvidium.gl.buffers.IDeviceMappedBuffer;
import me.cortex.nvidium.gl.shader.Shader;
import me.cortex.nvidium.sodiumCompat.ShaderLoader;
import me.cortex.nvidium.util.UploadingBufferStream;
import net.minecraft.client.renderer.ShaderDefines;
import net.minecraft.resources.Identifier;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL41C;
import org.lwjgl.opengl.GL43C;
import org.lwjgl.system.MemoryUtil;

import static me.cortex.nvidium.gl.shader.ShaderType.COMPUTE;
import static org.lwjgl.opengl.ARBDirectStateAccess.nglClearNamedBufferSubData;
import static org.lwjgl.opengl.GL42C.GL_ALL_BARRIER_BITS;
import static org.lwjgl.opengl.GL42C.glMemoryBarrier;
import static org.lwjgl.opengl.NVShaderBufferLoad.glProgramUniformui64NV;

/**
 * Builds indexed indirect draws for the Iris shader pack programs from nvidium's per region visible section commands
 * (see terrain/iris/draw_builder.comp). Each kind of draw gets its own output buffers.
 */
public class IrisDrawBuilder {
    public static final int MAX_DRAWS = 1 << 17;
    public static final int MAX_QUADS_PER_DRAW = 1 << 17;
    public static final int DRAW_COMMAND_SIZE = 20;

    public static final int MODE_TRANSLUCENT = 1;
    public static final int MODE_NO_FACE_CULLING = 2;
    public static final int MODE_COMPONENT_X = 0;
    public static final int MODE_COMPONENT_Y = 1 << 2;
    public static final int MODE_COMPONENT_Z = 2 << 2;
    public static final int MODE_SLOT_LIST = 16;

    public record Output(IDeviceMappedBuffer commands, IDeviceMappedBuffer offsets, IDeviceMappedBuffer drawCount) {}

    private final Pass countPass;
    private final Pass scanPass;
    private final Pass writePass;
    private final IDeviceMappedBuffer commandDraws;
    private final Output[] outputs;
    private final IDeviceMappedBuffer quadIndexBuffer;

    private record Pass(Shader shader, int inCommands, int sectionSlots, int commandCount, int commandDraws, int outCommands, int outOffsets,
                        int outDrawCount, int maxDraws, int maxQuadsPerDraw, int mode) {
        static Pass create(String define) {
            Shader shader = Shader.make()
                    .addSource(COMPUTE, ShaderLoader.parse(Identifier.fromNamespaceAndPath("nvidium", "terrain/iris/draw_builder.comp"),
                            ShaderDefines.builder().define(define)))
                    .compile();
            int id = shader.getId();
            return new Pass(shader, loc(id, "inCommands"), loc(id, "sectionSlots"), loc(id, "commandCount"), loc(id, "commandDraws"), loc(id, "outCommands"),
                    loc(id, "outOffsets"), loc(id, "outDrawCount"), loc(id, "maxDraws"), loc(id, "maxQuadsPerDraw"), loc(id, "mode"));
        }

        private static int loc(int program, String name) {
            return GL20C.glGetUniformLocation(program, name);
        }

        void dispatch(long inCommands, long sectionSlots, int commandCount, IDeviceMappedBuffer commandDraws, Output output, int mode, int groups) {
            int id = this.shader.getId();
            this.shader.bind();
            setPointer(id, this.inCommands, inCommands);
            setPointer(id, this.sectionSlots, sectionSlots);
            setPointer(id, this.commandDraws, commandDraws.getDeviceAddress());
            setPointer(id, this.outCommands, output.commands.getDeviceAddress());
            setPointer(id, this.outOffsets, output.offsets.getDeviceAddress());
            setPointer(id, this.outDrawCount, output.drawCount.getDeviceAddress());
            if (this.commandCount != -1) GL41C.glProgramUniform1ui(id, this.commandCount, commandCount);
            if (this.maxDraws != -1) GL41C.glProgramUniform1ui(id, this.maxDraws, MAX_DRAWS);
            if (this.maxQuadsPerDraw != -1) GL41C.glProgramUniform1ui(id, this.maxQuadsPerDraw, MAX_QUADS_PER_DRAW);
            if (this.mode != -1) GL41C.glProgramUniform1ui(id, this.mode, mode);
            GL43C.glDispatchCompute(groups, 1, 1);
            glMemoryBarrier(GL_ALL_BARRIER_BITS);
        }

        private static void setPointer(int program, int location, long address) {
            if (location != -1) {
                glProgramUniformui64NV(program, location, address);
            }
        }
    }

    public IrisDrawBuilder(RenderDevice device, UploadingBufferStream uploadStream, int maxCommands, int outputCount) {
        this.countPass = Pass.create("PASS_COUNT");
        this.scanPass = Pass.create("PASS_SCAN");
        this.writePass = Pass.create("PASS_WRITE");
        this.commandDraws = device.createDeviceOnlyMappedBuffer(maxCommands * 4L);
        this.outputs = new Output[outputCount];
        for (int i = 0; i < outputCount; i++) {
            this.outputs[i] = new Output(device.createDeviceOnlyMappedBuffer((long) MAX_DRAWS * DRAW_COMMAND_SIZE),
                    device.createDeviceOnlyMappedBuffer(MAX_DRAWS * 16L), device.createDeviceOnlyMappedBuffer(4));
        }
        this.quadIndexBuffer = createQuadIndexBuffer(device, uploadStream);
    }

    //Indices for MAX_QUADS_PER_DRAW quads, two triangles (0,1,2) (2,3,0) each
    private static IDeviceMappedBuffer createQuadIndexBuffer(RenderDevice device, UploadingBufferStream uploadStream) {
        long size = MAX_QUADS_PER_DRAW * 6L * 4L;
        IDeviceMappedBuffer buffer = device.createDeviceOnlyMappedBuffer(size);
        long data = uploadStream.upload(buffer, 0, size);
        for (int quad = 0; quad < MAX_QUADS_PER_DRAW; quad++) {
            long ptr = data + quad * 24L;
            int vertex = quad * 4;
            MemoryUtil.memPutInt(ptr, vertex);
            MemoryUtil.memPutInt(ptr + 4, vertex + 1);
            MemoryUtil.memPutInt(ptr + 8, vertex + 2);
            MemoryUtil.memPutInt(ptr + 12, vertex + 2);
            MemoryUtil.memPutInt(ptr + 16, vertex + 3);
            MemoryUtil.memPutInt(ptr + 20, vertex);
        }
        uploadStream.commit();
        return buffer;
    }

    /**
     * Must be called with nvidium's scene data bound at uniform binding 0.
     * @param inCommands device address of (section count, regionId << 8) commands
     * @param sectionSlots with MODE_SLOT_LIST, device address of the section slot list (one byte per section, 256 per region)
     */
    public Output build(int outputIndex, long inCommands, long sectionSlots, int commandCount, int mode) {
        Output output = this.outputs[outputIndex];
        nglClearNamedBufferSubData(output.drawCount.getId(), GL30C.GL_R32UI, 0, 4, GL30C.GL_RED_INTEGER, GL15C.GL_UNSIGNED_INT, 0);
        if (commandCount == 0) {
            return output;
        }
        this.countPass.dispatch(inCommands, sectionSlots, commandCount, this.commandDraws, output, mode, commandCount);
        this.scanPass.dispatch(inCommands, sectionSlots, commandCount, this.commandDraws, output, mode, 1);
        this.writePass.dispatch(inCommands, sectionSlots, commandCount, this.commandDraws, output, mode, commandCount);
        return output;
    }

    public IDeviceMappedBuffer getQuadIndexBuffer() {
        return this.quadIndexBuffer;
    }

    public void delete() {
        this.countPass.shader.delete();
        this.scanPass.shader.delete();
        this.writePass.shader.delete();
        this.commandDraws.delete();
        for (Output output : this.outputs) {
            output.commands.delete();
            output.offsets.delete();
            output.drawCount.delete();
        }
        this.quadIndexBuffer.delete();
    }
}
