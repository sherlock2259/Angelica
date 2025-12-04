package com.gtnewhorizons.angelica.hudcaching;

import com.gtnewhorizon.gtnhlib.client.renderer.CapturingTessellator;
import com.gtnewhorizon.gtnhlib.client.renderer.TessellatorManager;
import com.gtnewhorizon.gtnhlib.client.renderer.postprocessing.CustomFramebuffer;
import com.gtnewhorizon.gtnhlib.client.renderer.postprocessing.SharedDepthFramebuffer;
import com.gtnewhorizon.gtnhlib.client.renderer.vbo.VertexBuffer;
import com.gtnewhorizon.gtnhlib.client.renderer.vertex.DefaultVertexFormat;
import com.gtnewhorizons.angelica.compat.ModStatus;
import com.gtnewhorizons.angelica.compat.holoinventory.HoloInventoryReflectionCompat;
import com.gtnewhorizons.angelica.config.AngelicaConfig;
import com.gtnewhorizons.angelica.glsm.GLStateManager;
import com.gtnewhorizons.angelica.mixins.interfaces.GuiIngameAccessor;
import com.gtnewhorizons.angelica.mixins.interfaces.GuiIngameForgeAccessor;
import com.gtnewhorizons.angelica.mixins.interfaces.RenderGameOverlayEventAccessor;
import com.kentington.thaumichorizons.common.ThaumicHorizons;
import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiIngame;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.EntityRenderer;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.shader.Framebuffer;
import net.minecraftforge.client.GuiIngameForge;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.event.world.WorldEvent;
import org.lwjgl.opengl.GL11;
import thaumcraft.common.Thaumcraft;
import xaero.common.core.XaeroMinimapCore;

import static com.gtnewhorizons.angelica.loading.AngelicaTweaker.LOGGER;

// See LICENSE-HUDCaching.md for license information.

public class HUDCaching {

    private static final Minecraft mc = Minecraft.getMinecraft();
    public static SharedDepthFramebuffer framebuffer;
    private static boolean dirty = true;
    private static long nextHudRefresh;

    private static VertexBuffer quadVAO;
    private static float quadWidth;
    private static float quadHeight;

    public static boolean renderingCacheOverride;

    /*
     * Some HUD features cause problems/inaccuracies when being rendered into cache.
     * We capture those and render them later
     */
    // Vignette texture has no alpha
    public static boolean renderVignetteCaptured;
    // Helmet & portal are chances other mods render vignette
    // For example Thaumcraft renders warp effect during this
    public static boolean renderHelmetCaptured;
    public static float renderPortalCapturedTicks;
    // Crosshairs need to be blended with the scene
    public static boolean renderCrosshairsCaptured;

    private final static RenderGameOverlayEvent fakeTextEvent = new RenderGameOverlayEvent.Text(new RenderGameOverlayEvent(0, null, 0, 0), null, null);
    private final static RenderGameOverlayEvent.Post fakePostEvent = new RenderGameOverlayEvent.Post(new RenderGameOverlayEvent(0, null, 0, 0), RenderGameOverlayEvent.ElementType.HELMET);

    public static final HUDCaching INSTANCE = new HUDCaching();

    private HUDCaching() {}

    // highest so it runs before the GLSM load event
    @SubscribeEvent(priority = EventPriority.HIGH)
    public void onJoinWorld(WorldEvent.Load event) {
        if (event.world.isRemote){
            LOGGER.info("World loaded - Initializing HUDCaching");
            init();
        }
    }

    public static void init() {
        if (framebuffer == null) {
            framebuffer = new SharedDepthFramebuffer(CustomFramebuffer.STENCIL_BUFFER);
        }
    }

    @SuppressWarnings("unused")
    public static void renderCachedHud(EntityRenderer renderer, GuiIngame ingame, float partialTicks, boolean hasScreen, int mouseX, int mouseY) {
        // 确保帧缓冲已初始化
        if (framebuffer == null) {
            init();
        }
        
        if (ModStatus.isXaerosMinimapLoaded && ingame instanceof GuiIngameForge) {
            // this used to be called by asming into renderGameOverlay, but we removed it
            XaeroMinimapCore.beforeIngameGuiRender(partialTicks);
        }

        if (!OpenGlHelper.isFramebufferEnabled() || framebuffer == null) {
            ingame.renderGameOverlay(partialTicks, hasScreen, mouseX, mouseY);
            return;
        }

        if (System.currentTimeMillis() > nextHudRefresh) {
            dirty = true;
        }

        if (dirty) {
            dirty = false;
            nextHudRefresh = System.currentTimeMillis() + (1000 / AngelicaConfig.hudCachingFPS);
            
            // 设置帧缓冲
            if (framebuffer.framebufferWidth != mc.displayWidth || framebuffer.framebufferHeight != mc.displayHeight) {
                framebuffer.createBindFramebuffer(mc.displayWidth, mc.displayHeight);
            } else {
                framebuffer.clearBindFramebuffer();
            }
            
            // 确保深度测试和模板测试正确启用
            GLStateManager.enableDepthTest();
            GLStateManager.enableStencilTest();
            GLStateManager.glDepthMask(true);
            GLStateManager.glClearDepth(1.0);
            GLStateManager.glClear(GL11.GL_DEPTH_BUFFER_BIT | GL11.GL_STENCIL_BUFFER_BIT);
            
            renderingCacheOverride = true;
            ingame.renderGameOverlay(partialTicks, hasScreen, mouseX, mouseY);
            renderingCacheOverride = false;
            mc.getFramebuffer().bindFramebuffer(false);
        } else {
            renderer.setupOverlayRendering();
        }

        ScaledResolution resolution = new ScaledResolution(mc, mc.displayWidth, mc.displayHeight);
        int width = resolution.getScaledWidth();
        int height = resolution.getScaledHeight();
        GLStateManager.enableBlend();

        // reset the color that may be applied by some items
        GLStateManager.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);

        // render bits that were captured when rendering into cache
        GuiIngameAccessor gui = (GuiIngameAccessor) ingame;
        if (renderVignetteCaptured) {
            gui.callRenderVignette(mc.thePlayer.getBrightness(partialTicks), width, height);
        } else {
            GLStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, 1, 0);
        }

        if (ingame instanceof GuiIngameForge) {
            GuiIngameForgeAccessor guiForge = ((GuiIngameForgeAccessor) ingame);
            if (renderHelmetCaptured) {
                guiForge.callRenderHelmet(resolution, partialTicks, hasScreen, mouseX, mouseY);
                if (ModStatus.isHoloInventoryLoaded) {
                    HoloInventoryReflectionCompat.setAngelicaOverride(false);
                    // only settings the partial ticks as mouseX and mouseY are not used in renderEvent
                    ((RenderGameOverlayEventAccessor) fakePostEvent).setPartialTicks(partialTicks);
                    HoloInventoryReflectionCompat.renderEvent(fakePostEvent);
                }
            }
            if (renderPortalCapturedTicks > 0) {
                guiForge.callRenderPortal(width, height, partialTicks);
            }
            if (renderCrosshairsCaptured) {
                if (ModStatus.isXaerosMinimapLoaded) {
                    // this fixes the crosshair going invisible when no lines are being drawn under the minimap
                    GLStateManager.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
                }
                guiForge.callRenderCrosshairs(width, height);
            }
            if (ModStatus.isThaumcraftLoaded || ModStatus.isThaumicHorizonsLoaded) {
                ((RenderGameOverlayEventAccessor) fakeTextEvent).setPartialTicks(partialTicks);
                ((RenderGameOverlayEventAccessor) fakeTextEvent).setResolution(resolution);
                ((RenderGameOverlayEventAccessor) fakeTextEvent).setMouseX(mouseX);
                ((RenderGameOverlayEventAccessor) fakeTextEvent).setMouseY(mouseY);
                if (ModStatus.isThaumcraftLoaded) {
                    Thaumcraft.instance.renderEventHandler.renderOverlay(fakeTextEvent);
                }
                if (ModStatus.isThaumicHorizonsLoaded) {
                    ThaumicHorizons.instance.renderEventHandler.renderOverlay(fakeTextEvent);
                }
            }
        } else {
            if (renderHelmetCaptured) {
                gui.callRenderPumpkinBlur(width, height);
            }
            if (renderPortalCapturedTicks > 0) {
                gui.callRenderPortal(renderPortalCapturedTicks, width, height);
            }
        }

        // render cached frame
        GLStateManager.enableBlend();
        GLStateManager.tryBlendFuncSeparate(GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GLStateManager.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
        framebuffer.bindFramebufferTexture();
        // 设置纹理参数，确保渲染正确
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        drawTexturedRect((float) resolution.getScaledWidth_double(), (float) resolution.getScaledHeight_double());

        GLStateManager.tryBlendFuncSeparate(GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO);
        mc.getTextureManager().bindTexture(Gui.icons);
    }

    /**
     * We are skipping certain render calls when rendering into cache,
     * however, we cannot skip the GL state changes. This will fix
     * the state before we start rendering
     */
    public static void fixGLStateBeforeRenderingCache() {
        GLStateManager.glDepthMask(true);
        GLStateManager.enableDepthTest();
        GLStateManager.enableAlphaTest();
        GLStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO);
        GLStat
