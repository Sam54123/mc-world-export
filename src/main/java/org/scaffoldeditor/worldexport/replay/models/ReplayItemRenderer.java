package org.scaffoldeditor.worldexport.replay.models;

import org.scaffoldeditor.worldexport.mat.Material;
import org.scaffoldeditor.worldexport.mat.MaterialConsumer;
import org.scaffoldeditor.worldexport.mat.PromisedReplayTexture;
import org.scaffoldeditor.worldexport.mat.ReplayTexture;
import org.scaffoldeditor.worldexport.mat.TextureExtractor;
import org.scaffoldeditor.worldexport.util.MeshUtils;
import org.scaffoldeditor.worldexport.vcap.ObjVertexConsumer;

import de.javagl.obj.Obj;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.model.BakedModel;
import net.minecraft.client.render.model.json.ModelTransformation.Mode;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.item.ItemStack;

public class ReplayItemRenderer {

    public static final Material ITEM_MAT = new Material().setColor("world").setRoughness(1).setTransparent(true);

    private static ReplayTexture worldTex;

    public static void renderItem(ItemStack stack, Mode renderMode, boolean leftHanded, MatrixStack matrices, Obj obj, BakedModel model, MaterialConsumer materials) {
        renderItem(stack, renderMode, leftHanded, matrices, obj, model);
        addMaterials(materials);
    }

    public static void renderItem(ItemStack stack, Mode renderMode, boolean leftHanded, MatrixStack matrices, Obj obj, BakedModel model) {
        if (stack.isEmpty()) return;
        
        obj.setActiveMaterialGroupName("item");
        VertexConsumerProvider vertices = MeshUtils.wrapVertexConsumer(new ObjVertexConsumer(obj));
        MinecraftClient.getInstance().getItemRenderer().renderItem(stack, renderMode, leftHanded, matrices, vertices, 255, 0, model);
    }

    /**
     * Add the materials required to render items to a material consumer.
     * @param materials Material consumer to add to.
     */
    public static void addMaterials(MaterialConsumer materials) {
        if (worldTex == null) {
            worldTex = new PromisedReplayTexture(TextureExtractor.getAtlasTexture());
        }

        materials.addMaterial("item", ITEM_MAT);
        materials.addTexture("world", worldTex);
    }
}
