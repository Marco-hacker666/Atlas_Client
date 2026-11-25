package atlas.management;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class StackSamplerTest {
    private static StackTraceElement f(String type, String method) {
        return new StackTraceElement(type, method, null, -1);
    }

    @Test
    public void atlasWorkIsNamedByTheModuleThatAskedForIt() {
        StackTraceElement[] stack = {
                f("org.lwjgl.opengl.GL11", "glDrawArrays"),
                f("atlas.util.RenderUtil", "drawRect"),
                f("atlas.module.modules.HUD", "onRender2D"),
                f("atlas.event.EventManager", "call"),
                f("net.minecraft.client.renderer.EntityRenderer", "func_181560_a")
        };
        assertEquals("Atlas HUD.onRender2D", StackSampler.owner(stack));
        assertTrue(StackSampler.atlasAnywhere(stack));
    }

    @Test
    public void atlasUtilityAloneIsNamedByItself() {
        StackTraceElement[] stack = {
                f("java.util.HashMap", "get"),
                f("atlas.util.AsyncLog", "append"),
                f("net.minecraft.client.Minecraft", "func_71407_l")
        };
        assertEquals("Atlas AsyncLog.append", StackSampler.owner(stack));
    }

    @Test
    public void otherModsAreNamedByTheirPackage() {
        StackTraceElement[] stack = {
                f("org.lwjgl.opengl.GL11", "glVertex3f"),
                f("dev.tr7zw.skinlayers.render.CustomizableModelPart", "render"),
                f("ASMEventHandler_42_SkinLayers_onRender_RenderPlayerEvent", "invoke"),
                f("net.minecraftforge.fml.common.eventhandler.EventBus", "post")
        };
        assertEquals("mod dev.tr7zw.skinlayers", StackSampler.owner(stack));
        assertFalse(StackSampler.atlasAnywhere(stack));
    }

    @Test
    public void forgeGeneratedHandlersAreNotOptiFine() {
        StackTraceElement[] stack = {
                f("ASMEventHandler_7_Foo_onTick_ClientTickEvent", "invoke"),
                f("net.minecraftforge.fml.common.eventhandler.EventBus", "post"),
                f("net.minecraft.client.Minecraft", "func_71407_l")
        };
        assertEquals("Minecraft Minecraft.func_71407_l", StackSampler.owner(stack));
    }

    @Test
    public void optiFineAndMixinHandlersAndVanilla() {
        assertEquals("OptiFine Config", StackSampler.owner(new StackTraceElement[]{
                f("Config", "isShaders"), f("net.minecraft.client.renderer.RenderGlobal", "func_174970_a")}));
        assertEquals("mixin Block.handler$shouldSideBeRendered$zbn000", StackSampler.owner(new StackTraceElement[]{
                f("java.util.HashMap", "get"),
                f("net.minecraft.block.Block", "handler$shouldSideBeRendered$zbn000"),
                f("net.minecraft.block.Block", "func_176225_a")}));
        assertEquals("Minecraft RenderGlobal.func_174970_a", StackSampler.owner(new StackTraceElement[]{
                f("org.lwjgl.opengl.GL11", "glCallList"),
                f("net.minecraft.client.renderer.RenderGlobal", "func_174970_a")}));
        assertEquals("idle", StackSampler.owner(new StackTraceElement[0]));
    }
}
