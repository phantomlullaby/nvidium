package me.cortex.nvidium.util;

import me.cortex.nvidium.Nvidium;
import me.cortex.nvidium.gl.RenderDevice;
import me.cortex.nvidium.gl.buffers.IDeviceMappedBuffer;
import me.cortex.nvidium.gl.buffers.PersistentSparseAddressableBuffer;

import static org.lwjgl.opengl.ARBDirectStateAccess.nglClearNamedBufferData;
import static org.lwjgl.opengl.GL11.glFinish;
import static org.lwjgl.opengl.GL11.glGetInteger;
import static org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE;
import static org.lwjgl.opengl.GL30C.GL_R8UI;
import static org.lwjgl.opengl.GL30C.GL_RED_INTEGER;
import static org.lwjgl.opengl.NVXGPUMemoryInfo.GL_GPU_MEMORY_INFO_CURRENT_AVAILABLE_VIDMEM_NVX;

//TODO: make it not remove and immediately deallocate the sparse pages, wait until the end of a frame to deallocate
// since committing pages is not cheap
public class BufferArena {
    SegmentedManager segments = new SegmentedManager();
    private final RenderDevice device;
    public final IDeviceMappedBuffer buffer;
    private long totalQuads;
    private final int vertexFormatSize;

    private final long memory_size;

    //The fallback buffer is allocated at full size, and the driver can silently place a buffer that big in system memory, which
    // makes terrain rendering ~4x slower. It did so when the renderer was rebuilt (several times when Vivecraft starts VR with a
    // shader pack), and for any new 2 GiB buffer once Vivecraft's per eye Iris pipelines were allocated. So the buffer of a
    // deleted renderer is kept here for the next one, and a new buffer that did not land in video memory is replaced by a smaller one
    private static IDeviceMappedBuffer spareBuffer;
    private static final long MIN_FALLBACK_MEMORY = 512L << 20;

    public BufferArena(RenderDevice device, long memory, int vertexFormatSize) {
        this.device = device;
        this.vertexFormatSize = vertexFormatSize;
        if (Nvidium.SUPPORTS_PERSISTENT_SPARSE_ADDRESSABLE_BUFFER) {
            buffer = device.createSparseBuffer(80000000000L);//Create a 80gb buffer
        } else {
            if (spareBuffer != null && spareBuffer.getSize() <= memory) {
                buffer = spareBuffer;
                spareBuffer = null;
            } else {
                releaseSpareBuffer();
                buffer = createInVideoMemory(device, memory);
            }
            memory = buffer.getSize();
            this.segments.setLimit(memory/(4L*this.vertexFormatSize));
        }
        this.memory_size = memory;
        //Reserve index 0
        this.allocQuads(1);
    }

    public int allocQuads(int quadCount) {
        totalQuads += quadCount;
        int addr = (int) segments.alloc(quadCount);
        if (addr == SegmentedManager.SIZE_LIMIT) {
            return addr;
        }
        if (buffer instanceof PersistentSparseAddressableBuffer psab) {
            psab.ensureAllocated(Integer.toUnsignedLong(addr) * 4L * vertexFormatSize, quadCount * 4L * vertexFormatSize);
        }
        return addr;
    }

    public void free(int addr) {
        int count = segments.free(addr);
        totalQuads -= count;
        if (buffer instanceof PersistentSparseAddressableBuffer psab) {
            psab.deallocate(Integer.toUnsignedLong(addr) * 4L * vertexFormatSize, count * 4L * vertexFormatSize);
        }
    }

    public long upload(UploadingBufferStream stream, int addr) {
        return stream.upload(buffer, Integer.toUnsignedLong(addr)*4L*vertexFormatSize, (int) segments.getSize(addr)*4*vertexFormatSize);
    }

    public void delete() {
        if (buffer instanceof PersistentSparseAddressableBuffer) {
            buffer.delete();
        } else {
            releaseSpareBuffer();
            spareBuffer = buffer;
        }
    }

    private static IDeviceMappedBuffer createInVideoMemory(RenderDevice device, long size) {
        while (true) {
            //Clearing the buffer makes the driver back it with memory, it is in video memory if the free video memory went down by its size
            glFinish();
            long freeBefore = glGetInteger(GL_GPU_MEMORY_INFO_CURRENT_AVAILABLE_VIDMEM_NVX) * 1024L;
            IDeviceMappedBuffer buffer = device.createDeviceOnlyMappedBuffer(size);
            nglClearNamedBufferData(buffer.getId(), GL_R8UI, GL_RED_INTEGER, GL_UNSIGNED_BYTE, 0);
            glFinish();
            long freeAfter = glGetInteger(GL_GPU_MEMORY_INFO_CURRENT_AVAILABLE_VIDMEM_NVX) * 1024L;
            if (freeBefore - freeAfter >= size / 2 || size / 2 < MIN_FALLBACK_MEMORY) {
                return buffer;
            }
            Nvidium.LOGGER.warn("The {} MB terrain buffer was not placed in video memory, retrying with {} MB", size >> 20, size >> 21);
            buffer.delete();
            size /= 2;
        }
    }

    //Frees the buffer kept from the last deleted renderer, for when the next renderer won't use it
    public static void releaseSpareBuffer() {
        if (spareBuffer != null) {
            spareBuffer.delete();
            spareBuffer = null;
        }
    }

    public int getAllocatedMB() {
        if (buffer instanceof PersistentSparseAddressableBuffer psab) {
            return (int) ((psab.getPagesCommitted() * PersistentSparseAddressableBuffer.PAGE_SIZE) / (1024 * 1024));
        } else {
            return (int) (memory_size/(1024*1024));
        }
    }

    public int getUsedMB() {
        return (int) ((totalQuads * vertexFormatSize * 4)/(1024*1024));
    }

    public long getMemoryUsed() {
        if (buffer instanceof PersistentSparseAddressableBuffer psab) {
            return (psab.getPagesCommitted() * PersistentSparseAddressableBuffer.PAGE_SIZE);
        } else {
            return memory_size;
        }
    }

    public float getFragmentation() {
        long expected = totalQuads * vertexFormatSize * 4;
        return (float) ((double)expected/getMemoryUsed());
    }

    public boolean canReuse(int addr, int quads) {
        return this.segments.getSize(addr) == quads;
    }
}
