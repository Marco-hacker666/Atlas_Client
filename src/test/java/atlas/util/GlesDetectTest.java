package atlas.util;

import atlas.util.render.GlesDetect;
import atlas.util.render.RoundRectGeometry;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.*;

/** Mobile-safe mode: renderer detection and the plain rounded-rect geometry (2026-10-15). */
public class GlesDetectTest {

    // ---- GL strings (as read from each renderer's source) ----

    @Test
    public void gl4esCurrent() {
        assertEquals("gl4es", GlesDetect.translationLayer("GL4ES wrapping Qualcomm", "GL4ES using Adreno (TM) 650",
                "2.1 gl4es wrapper 1.1.5", "1.20 via gl4es"));
    }

    @Test
    public void holyGl4esPojav() {
        assertEquals("gl4es", GlesDetect.translationLayer("ptitSeb", "GL4ES wrapper", "2.1", null));
    }

    @Test
    public void kryptonWrapper() {
        assertEquals("Krypton Wrapper", GlesDetect.translationLayer("ptitSeb & BZLZHH",
                "Adreno (TM) 740 | OpenGL ES 3.2", "4.6 Krypton Wrapper 0.4.5",
                "4.50 Krypton Wrapper with glslang and SPIRV-Cross"));
    }

    @Test
    public void kryptonWithColourCodes() {
        /* LIBGL_USE_MC_COLOR puts section signs into the version string. */
        assertEquals("Krypton Wrapper", GlesDetect.translationLayer(null, null,
                "3.3 §aKrypton Wrapper §b0.4.5§r", null));
    }

    @Test
    public void mobileGlues() {
        assertEquals("MobileGlues", GlesDetect.translationLayer("Swung0x48, BZLZHH, Tungsten",
                "Mali-G78 | OpenGL ES 3.2", "4.0.0 MobileGlues 1.2", null));
    }

    @Test
    public void unnamedLayerStillNamesGles() {
        assertEquals("OpenGL ES", GlesDetect.translationLayer("ARM", "Mali-G78 | OpenGL ES 3.2", "3.3", null));
    }

    @Test
    public void desktopDriversAreNotMatched() {
        assertNull(GlesDetect.translationLayer("NVIDIA Corporation", "NVIDIA GeForce RTX 3060/PCIe/SSE2",
                "4.6.0 NVIDIA 560.94", "4.60 NVIDIA"));
        assertNull(GlesDetect.translationLayer("ATI Technologies Inc.", "AMD Radeon RX 6600",
                "4.6.14800 Compatibility Profile Context", "4.60"));
        assertNull(GlesDetect.translationLayer("Intel", "Intel(R) UHD Graphics 620", "4.6.0 - Build 31.0.101", "4.60 - Build"));
        assertNull(GlesDetect.translationLayer("Mesa", "Mesa Intel(R) Xe Graphics (TGL GT2)", "4.6 (Compatibility Profile) Mesa 24.0", "4.60"));
        assertNull(GlesDetect.translationLayer(null, null, null, null));
    }

    // ---- Android signals ----

    private static String android(Map<String, String> env, Map<String, String> props, boolean buildProp) {
        return GlesDetect.android(env::get, props::get, path -> buildProp && "/system/build.prop".equals(path));
    }

    @Test
    public void pojavEnvironment() {
        Map<String, String> env = new HashMap<String, String>();
        env.put("POJAV_RENDERER", "opengles2");
        assertEquals("env POJAV_RENDERER", android(env, new HashMap<String, String>(), false));
    }

    @Test
    public void androidSystemPartitionOnLinux() {
        Map<String, String> props = new HashMap<String, String>();
        props.put("os.name", "Linux");
        assertEquals("/system/build.prop", android(new HashMap<String, String>(), props, true));
    }

    @Test
    public void buildPropIgnoredOffLinux() {
        Map<String, String> props = new HashMap<String, String>();
        props.put("os.name", "Windows 11");
        assertNull(android(new HashMap<String, String>(), props, true));
    }

    @Test
    public void emptyEnvValueIsNotASign() {
        Map<String, String> env = new HashMap<String, String>();
        env.put("POJAV_RENDERER", "");
        Map<String, String> props = new HashMap<String, String>();
        props.put("os.name", "Linux");
        props.put("java.vendor", "Eclipse Adoptium");
        assertNull(android(env, props, false));
    }

    @Test
    public void throwingSourcesAreNotASign() {
        assertNull(GlesDetect.android(k -> {
            throw new SecurityException("no");
        }, k -> {
            throw new SecurityException("no");
        }, p -> false));
    }

    // ---- the setting ----

    @Test
    public void settingOverridesDetection() {
        assertTrue(GlesDetect.active(GlesDetect.AUTO, true));
        assertFalse(GlesDetect.active(GlesDetect.AUTO, false));
        assertTrue(GlesDetect.active(GlesDetect.ON, false));
        assertFalse(GlesDetect.active(GlesDetect.OFF, true));
    }

    // ---- geometry ----

    @Test
    public void squareRectIsFourCorners() {
        float[] p = RoundRectGeometry.outline(10, 20, 30, 40, 0, 0, 0, 0);
        assertArrayEquals(new float[]{10, 20, 40, 20, 40, 60, 10, 60}, p, 1e-4F);
    }

    @Test
    public void roundedOutlineStaysInsideAndTouchesEdges() {
        float x = 5, y = 7, w = 50, h = 20, r = 6;
        float[] p = RoundRectGeometry.outline(x, y, w, h, r, r, r, r);
        assertEquals(4 * (RoundRectGeometry.segments(r) + 1) * 2, p.length);
        float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        for (int i = 0; i < p.length; i += 2) {
            assertTrue(p[i] >= x - 1e-3F && p[i] <= x + w + 1e-3F);
            assertTrue(p[i + 1] >= y - 1e-3F && p[i + 1] <= y + h + 1e-3F);
            minX = Math.min(minX, p[i]);
            maxX = Math.max(maxX, p[i]);
            minY = Math.min(minY, p[i + 1]);
            maxY = Math.max(maxY, p[i + 1]);
        }
        assertEquals(x, minX, 1e-3F);
        assertEquals(x + w, maxX, 1e-3F);
        assertEquals(y, minY, 1e-3F);
        assertEquals(y + h, maxY, 1e-3F);
        /* Clockwise on screen (y down) gives a positive shoelace area. */
        double area = 0;
        for (int i = 0; i < p.length; i += 2) {
            int j = (i + 2) % p.length;
            area += p[i] * p[j + 1] - p[j] * p[i + 1];
        }
        assertTrue(area > 0);
        assertTrue(area / 2 < w * h);
    }

    @Test
    public void radiusIsClampedToHalfTheShortSide() {
        assertEquals(5.0F, RoundRectGeometry.clampRadius(20, 40, 10), 0.0F);
        assertEquals(0.0F, RoundRectGeometry.clampRadius(-3, 40, 10), 0.0F);
    }

    @Test
    public void segmentsStayFew() {
        assertEquals(0, RoundRectGeometry.segments(0));
        assertEquals(2, RoundRectGeometry.segments(1));
        assertEquals(8, RoundRectGeometry.segments(100));
    }

    @Test
    public void cornerBlend() {
        int tl = 0xFF000000, tr = 0xFFFF0000, br = 0xFFFFFFFF, bl = 0x00000000;
        assertEquals(tl, RoundRectGeometry.blend(tl, tr, br, bl, 0, 0));
        assertEquals(tr, RoundRectGeometry.blend(tl, tr, br, bl, 1, 0));
        assertEquals(br, RoundRectGeometry.blend(tl, tr, br, bl, 1, 1));
        assertEquals(bl, RoundRectGeometry.blend(tl, tr, br, bl, 0, 1));
        assertEquals(0x12345678, RoundRectGeometry.blend(0x12345678, 0x12345678, 0x12345678, 0x12345678, 0.3F, 0.9F));
    }
}
