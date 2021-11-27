package org.scaffoldeditor.worldexport;

import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.scaffoldeditor.worldexport.export.ExportContext;
import org.scaffoldeditor.worldexport.export.Frame;
import org.scaffoldeditor.worldexport.export.Material;
import org.scaffoldeditor.worldexport.export.MeshWriter;
import org.scaffoldeditor.worldexport.export.TextureExtractor;
import org.scaffoldeditor.worldexport.export.VcapMeta;
import org.scaffoldeditor.worldexport.export.ExportContext.ModelEntry;
import org.scaffoldeditor.worldexport.export.Frame.IFrame;
import org.scaffoldeditor.worldexport.export.Frame.PFrame;
import org.scaffoldeditor.worldexport.export.MeshWriter.MeshInfo;

import de.javagl.obj.Obj;
import de.javagl.obj.ObjWriter;
import net.minecraft.block.BlockState;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtList;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.WorldAccess;

/**
 * Captures and exports a voxel capture file. Each instance
 * represents one file being exported.
 */
public class VcapExporter {
    private static Logger LOGGER = LogManager.getLogger();

    public final WorldAccess world;
    public final ChunkPos minChunk;
    public final ChunkPos maxChunk;
    public final List<Frame> frames = new ArrayList<>();
    public final ExportContext context;

    /**
     * Create a new export instance.
     * 
     * @param world    World to capture.
     * @param minChunk Bounding box min.
     * @param maxChunk Bounding box max.
     */
    public VcapExporter(WorldAccess world, ChunkPos minChunk, ChunkPos maxChunk) {
        this.world = world;
        this.minChunk = minChunk;
        this.maxChunk = maxChunk;
        context = new ExportContext();
    }

    /**
     * <p>
     * Save the export instance to a file asynchronously.
     * </p>
     * <p>
     * <b>Warning:</b> Due to the need to extract the atlas texture from
     * the GPU, this future will not complete untill the next frame is rendered.
     * Do not block on a thread that will stop the rendering of the next frame.
     * 
     * @param os Output stream to write to.
     * @return A future that completes when the file has been saved.
     */
    public CompletableFuture<Void> saveAsync(OutputStream os) {
        return CompletableFuture.runAsync(() -> {
            try {
                save(os);
            } catch (IOException e) {
                throw new CompletionException(e);
            }
        });
    }

    /**
     * <p>
     * Save the export instance to a file.
     * </p>
     * <p>
     * <b>Warning:</b> Due to the need to extract the atlas texture from
     * the GPU, this method blocks untill the next frame is rendered. Do not
     * call from a thread that will stop the rendering of the next frame.
     * 
     * @param os Output stream to write to.
     * @throws IOException If an IO exception occurs while writing the file
     *                     or extracting the texture.
     */
    public void save(OutputStream os) throws IOException {
        ZipOutputStream out = new ZipOutputStream(os);

        // WORLD
        LOGGER.info("Compiling frames...");
        NbtList frames = new NbtList();
        this.frames.forEach(frame -> frames.add(frame.getFrameData()));
        NbtCompound worldData = new NbtCompound();
        worldData.put("frames", frames);

        out.putNextEntry(new ZipEntry("world.dat"));
        NbtIo.write(worldData, new DataOutputStream(out));
        out.closeEntry();

        // MODELS
        Random random = new Random();
        int numLayers = 0;

        for (ModelEntry model : context.models.keySet()) {
            String id = context.models.get(model);
            LOGGER.info("Writing mesh: "+id);

            MeshInfo info = MeshWriter.writeBlockMesh(model, random);
            writeMesh(info.mesh, id, out);

            if (info.numLayers > numLayers) {
                numLayers = info.numLayers;
            }
        }

        for (String id : context.fluidMeshes.keySet()) {
            LOGGER.info("Writing fluid mesh: "+id);
            writeMesh(context.fluidMeshes.get(id), id, out);
        }

        // Fluid meshes assume empty mesh is written.
        writeMesh(MeshWriter.empty().mesh, MeshWriter.EMPTY_MESH, out);

        // MATERIALS
        Material opaque = new Material();
        opaque.color = new Material.Field("world");
        opaque.roughness = new Material.Field(.7);
        opaque.useVertexColors = false;
        
        out.putNextEntry(new ZipEntry("mat/"+MeshWriter.WORLD_MAT+".json"));
        opaque.serialize(out);
        out.closeEntry();

        Material transparent = new Material();
        transparent.color = new Material.Field("world");
        transparent.roughness = new Material.Field(.7);
        transparent.transparent = true;
        transparent.useVertexColors = false;

        out.putNextEntry(new ZipEntry("mat/"+MeshWriter.TRANSPARENT_MAT+".json"));
        transparent.serialize(out);
        out.closeEntry();

        Material opaque_tinted = new Material();
        opaque_tinted.color = new Material.Field("world");
        opaque_tinted.roughness = new Material.Field(.7);
        opaque_tinted.useVertexColors = true;

        out.putNextEntry(new ZipEntry("mat/"+MeshWriter.TINTED_MAT+".json"));
        opaque_tinted.serialize(out);
        out.closeEntry();

        Material transparent_tinted = new Material();
        transparent_tinted.color = new Material.Field("world");
        transparent_tinted.roughness = new Material.Field(.7);
        transparent_tinted.transparent = true;
        transparent_tinted.useVertexColors = true;

        out.putNextEntry(new ZipEntry("mat/"+MeshWriter.TRANSPARENT_TINTED_MAT+".json"));
        transparent_tinted.serialize(out);
        out.closeEntry();

        // TEXTURE ATLAS
        LOGGER.info("Extracting world texture...");
        CompletableFuture<NativeImage> atlasFuture = TextureExtractor.getAtlas();
        // For some reason, NativeImage can only write to a file; not an output stream.
        NativeImage atlas;
        File atlasTemp = File.createTempFile("atlas-", ".png");
        atlasTemp.deleteOnExit();
        try {
            atlas = atlasFuture.get(5, TimeUnit.SECONDS);
        } catch (InterruptedException | ExecutionException e) {
            throw new IOException("Unable to retrieve texture atlas.", e);
        } catch (TimeoutException e) {
            throw new IOException("Texture retrieval timed out.");
        }

        atlas.writeTo(atlasTemp);

        out.putNextEntry(new ZipEntry("tex/world.png"));
        Files.copy(atlasTemp.toPath(), out);
        out.closeEntry();

        // META
        LOGGER.info("Writing Vcap metadata.");
        VcapMeta meta = new VcapMeta(numLayers);
        Gson gson = new GsonBuilder()
            .setPrettyPrinting()
            .create();
        
        out.putNextEntry(new ZipEntry("meta.json"));
        PrintWriter writer = new PrintWriter(out);
        writer.print(gson.toJson(meta));
        writer.flush();
        out.closeEntry();

        LOGGER.info("Finished writing Vcap.");
        out.close();
    }

    private static void writeMesh(Obj mesh, String id, ZipOutputStream out) throws IOException {
        ZipEntry modelEntry = new ZipEntry("mesh/"+id+".obj");
        out.putNextEntry(modelEntry);
        ObjWriter.write(mesh, out);
        out.closeEntry();
    }

    /**
     * <p>
     * Capture an intracoded frame and add it to the vcap.
     * </p>
     * <p>
     * Warning: depending on the size of the capture, this may
     * take multiple seconds.
     * </p>
     * 
     * @param time Time stamp of the frame, in seconds since the beginning
     *             of the animation.
     * @return The frame.
     */
    public IFrame captureIFrame(double time) {
        IFrame iFrame = IFrame.capture(world, minChunk, maxChunk, context, time);
        frames.add(iFrame);
        return iFrame;
    }

    public PFrame capturePFrame(double time, Map<BlockPos, BlockState> blocks) {
        return null;
    }
}