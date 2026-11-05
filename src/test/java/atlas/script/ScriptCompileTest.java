package atlas.script;

import org.junit.Assume;
import org.junit.Test;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * Scripts as players write them compile against this client's classes alone -- no Minecraft classes, as in the
 * game, where the Minecraft jar on the class path is obfuscated -- with the JDK's compiler and with ECJ (the one
 * players download, in a JVM of its own: see IsolatedEcj), and their hooks are called (2026-11-03).
 */
public class ScriptCompileTest {

    static final String RAVEN = String.join("\n",
            "void onLoad() {",
            "    modules.registerSlider(\"Range\", 3.0, 1.0, 6.0, 0.1);",
            "    modules.registerSlider(\"Mode\", 0, new String[]{\"A\", \"B\"});",
            "    modules.registerButton(\"Chat\", true);",
            "    modules.registerColor(\"Color\", 255, 0, 0);",
            "    modules.registerKey(\"Key\", 0);",
            "    modules.registerDescription(\"Extra\");",
            "}",
            "void onEnable() { client.print(\"&aon\"); }",
            "void onPreUpdate() {",
            "    Entity player = client.getPlayer();",
            "    for (Entity e : world.getPlayerEntities()) {",
            "        if (e == player) continue;",
            "        double d = e.getPosition().distanceTo(player.getPosition());",
            "        if (d < modules.getSlider(scriptName, \"Range\")) {",
            "            float[] r = client.getRotationsToEntity(e);",
            "            client.setRotations(r[0], r[1]);",
            "        }",
            "    }",
            "    List<String> lines = world.getScoreboard();",
            "    Map<String, List<String>> teams = world.getTeams();",
            "}",
            "void onPreMotion(PlayerState state) { state.yaw += 1; }",
            "boolean onPacketSent(CPacket packet) {",
            "    if (packet instanceof C03) { C03 c = (C03) packet; return c.position == null || c.position.y > -64; }",
            "    if (packet instanceof C02) { return !((C02) packet).action.equals(\"ATTACK\") || true; }",
            "    return true;",
            "}",
            "boolean onPacketReceived(SPacket packet) {",
            "    if (packet instanceof S12) { S12 s = (S12) packet; return s.motion.y > -10; }",
            "    if (packet instanceof S08) { return ((S08) packet).enumFlags != null; }",
            "    return true;",
            "}",
            "void onRenderTick(float partialTicks) { render.text2d(\"hi\", 5, 5, 1, -1, true); render.rect(0, 0, 10, 10, 0x80000000); }",
            "void onRenderWorld(float pt) { render.block(new Vec3(0, 64, 0), 0xFF00FF00, true, true); }",
            "boolean onChat(String msg) { return !msg.contains(\"spam\"); }",
            "boolean onMouse(int button, boolean state) { return true; }",
            "boolean onKeyPress(char c, int key) { return true; }",
            "void onPrePlayerInput(MovementInput input) { input.jump = keybinds.isPressed(\"jump\"); }",
            "void onGuiUpdate(String name, boolean opened) { }",
            "void onWorldJoin(Entity entity) { }",
            "Float[] getRotations() { return null; }",
            "int counted;",
            "void count() { counted++; }",
            "void everything() {",
            "    Json j = Json.object().add(\"a\", 1).add(\"b\", \"x\");",
            "    client.async(() -> { Response r = new Request(\"GET\", \"https://example.com\").fetch(); if (r != null) client.print(r.code()); });",
            "    Simulation sim = Simulation.create(); sim.setForward(1); sim.tick(); Vec3 p = sim.getPosition();",
            "    bridge.add(\"k\", 1); Object o = bridge.get(\"k\");",
            "    config.set(\"a\", \"b\"); String v = config.get(\"a\");",
            "    client.sendPacket(new C0A()); client.sendPacket(new C03(true)); client.sendPacket(new C09(1));",
            "    client.sendPacket(new C03(new Vec3(0, 64, 0), 90f, 0f, true));",
            "    client.sendPacket(new C08(inventory.getStackInSlot(0), new Vec3(0, 0, 0), 1, new Vec3(0.5, 0.5, 0.5)));",
            "    client.sendPacket(new C07(new Vec3(0, 64, 0), \"START_DESTROY_BLOCK\", \"UP\"));",
            "    client.processPacket(new S12(1, new Vec3(0, 0.4, 0)));",
            "    ItemStack held = client.getPlayer().getHeldItem();",
            "    Block b = world.getBlockAt(0, 64, 0); String n = b.name;",
            "    Object[] hit = client.raycastBlock(4.5);",
            "    Message m = new Message(\"&ahi\"); m.appendStyle(\"HOVER\", \"SHOW_TEXT\", \"tip\", \" more\"); client.print(m);",
            "    gl.push(); gl.pop(); render.blur.prepare(); render.blur.apply(2, 3f); render.bloom.prepare();",
            "    int r = util.randomInt(1, 3); String s = util.color(\"&a\");",
            "    List<Integer> nums = new ArrayList<>(); nums.sort(Comparator.naturalOrder()); nums.forEach(x -> client.log(x));",
            "    WebSocket ws = new WebSocket(\"wss://example.com\") { @Override public void onMessage(String message) { client.print(message); } };",
            "    Image img = new Image(\"https://example.com/a.png\", true);",
            "    modules.enable(\"KillAura\"); boolean on = modules.isEnabled(\"Scaffold\"); Entity t = modules.getKillAuraTarget();",
            "    Vec3 screen = render.worldToScreen(0, 64, 0, 2, 1f);",
            "    for (NetworkPlayer np : world.getNetworkPlayers()) { client.print(np.getName() + np.getPing()); }",
            "    for (TileEntity te : world.getTileEntities()) { Vec3 at = te.getPosition(); }",
            "    Color c = new Color(255, 0, 0); Random random = new Random();",
            "}");

    static final String ATLAS = String.join("\n",
            "import java.util.concurrent.TimeUnit;",
            "",
            "final BooleanProperty chat = bool(\"chat\", true);",
            "final FloatProperty range = number(\"range\", 3.0F, 1.0F, 6.0F);",
            "final ModeProperty mode = mode(\"mode\", \"Nearest\", \"Nearest\", \"Lowest HP\");",
            "final IntProperty delay = integer(\"delay\", 2, 0, 10);",
            "final ColorProperty tint = color(\"tint\", 0x55FFFF);",
            "int keys;",
            "void onEnable() { print(\"on \" + range.getValue()); suffix(mode.getModeString()); }",
            "void onTick() {",
            "    Entity target = nearestPlayer(range.getValue());",
            "    if (target != null) { float[] r = lookAt(target); look(r[0], r[1]); }",
            "}",
            "void onUpdate(UpdateEvent event) { float y = event.getYaw(); }",
            "boolean onAttack(Entity target) { return chat.getValue(); }",
            "void onRender2D(float pt) { render.text2d(name(), 4, 4, 1, -1, true); }",
            "void onKey(int key) { keys += key; }",
            "long later() { return TimeUnit.SECONDS.toMillis(1); }",
            "boolean other() { return module(\"KillAura\") != null && setting(\"KillAura\", \"range\") != null; }");

    private static List<File> ownClassesOnly() {
        File own = ScriptCompiler.codeSource(ScriptSource.class);
        assertNotNull(own);
        return Collections.singletonList(own);
    }

    private static ScriptCompiler.Result compile(JavaCompiler javac, ScriptKind kind, String name, String source,
                                                 File dir) {
        ScriptSource.Wrapped wrapped = ScriptSource.wrap(kind, name, source, 1);
        assertNull(wrapped.problem);
        File sources = new File(dir, "src/" + ScriptSource.PACKAGE);
        List<ScriptCompiler.Unit> units = Arrays.asList(
                new ScriptCompiler.Unit(new File(sources, wrapped.scriptClass + ".java"), wrapped.code,
                        wrapped.headerLines, name + ".java"),
                new ScriptCompiler.Unit(new File(sources, wrapped.hostClass + ".java"), wrapped.hostCode, 0, "(host)"));
        return ScriptCompiler.compile(javac, units, new File(dir, "classes"), ownClassesOnly());
    }

    private static void assertCompiles(JavaCompiler javac, String label) throws Exception {
        for (ScriptKind kind : ScriptKind.values()) {
            File dir = Files.createTempDirectory("atlas-script-" + label).toFile();
            try {
                ScriptCompiler.Result result = compile(javac, kind, "Sample", kind.raven() ? RAVEN : ATLAS, dir);
                assertTrue(label + " " + kind + ": " + result.problems, result.ok);
            } finally {
                ScriptManager.deleteTree(dir);
            }
        }
    }

    @Test
    public void bothKindsCompileWithTheJdkCompiler() throws Exception {
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        Assume.assumeNotNull(javac);
        assertCompiles(javac, "javac");
    }

    @Test
    public void bothKindsCompileWithEcj() throws Exception {
        for (ScriptKind kind : ScriptKind.values()) {
            IsolatedEcj.Outcome outcome = IsolatedEcj.compile(kind,
                    Collections.singletonList(kind.raven() ? RAVEN : ATLAS)).get(0);
            assertTrue("ecj " + kind + ": " + outcome.errors, outcome.ok);
        }
    }

    @Test
    public void errorsComeBackAtTheUsersLine() throws Exception {
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        Assume.assumeNotNull(javac);
        File dir = Files.createTempDirectory("atlas-script-err").toFile();
        try {
            String source = "import java.util.UUID;\n\nvoid onEnable() {\n    int x = \"not a number\";\n}\n";
            ScriptCompiler.Result result = compile(javac, ScriptKind.RAVEN16, "Broken", source, dir);
            assertFalse(result.ok);
            boolean atLine4 = false;
            for (ScriptCompiler.Problem problem : result.problems) {
                atLine4 |= problem.error && problem.line == 4 && problem.file.equals("Broken.java");
            }
            assertTrue(result.problems.toString(), atLine4);
        } finally {
            ScriptManager.deleteTree(dir);
        }
    }

    @Test
    public void loadedScriptsAnswerTheirHooks() throws Exception {
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        Assume.assumeNotNull(javac);
        File dir = Files.createTempDirectory("atlas-script-run").toFile();
        try {
            for (ScriptKind kind : ScriptKind.values()) {
                ScriptSource.Wrapped wrapped = ScriptSource.wrap(kind, "Run" + kind.name(), kind.raven() ? RAVEN : ATLAS, 1);
                File out = new File(dir, kind.label);
                List<ScriptCompiler.Unit> units = Arrays.asList(
                        new ScriptCompiler.Unit(new File(out, "src/" + wrapped.scriptClass + ".java"), wrapped.code,
                                wrapped.headerLines, "Run.java"),
                        new ScriptCompiler.Unit(new File(out, "src/" + wrapped.hostClass + ".java"), wrapped.hostCode, 0, "(host)"));
                ScriptCompiler.Result result = ScriptCompiler.compile(javac, units, new File(out, "classes"),
                        ScriptCompiler.classpath());
                assertTrue(result.problems.toString(), result.ok);
                Map<String, byte[]> classes = ScriptManager.readClasses(new File(out, "classes"));
                assertTrue(classes.containsKey(ScriptSource.PACKAGE + "." + wrapped.scriptClass));
                ScriptLoader loader = new ScriptLoader(getClass().getClassLoader(), classes);
                Object instance = loader.loadClass(ScriptSource.PACKAGE + "." + wrapped.scriptClass).newInstance();
                ScriptEntry entry = new ScriptEntry("Run" + kind.name(), kind, new File("Run.java"));
                entry.scriptClass = wrapped.scriptClass;
                entry.headerLines = wrapped.headerLines;
                ScriptRuntime runtime = new ScriptRuntime(entry, instance);
                if (kind.raven()) {
                    assertEquals(0, runtime.ask("onChat", "buy spam now"));
                    assertEquals(1, runtime.ask("onChat", "hello"));
                    assertEquals("a script without the hook is not asked", -1, runtime.ask("onPacketReceivedX", "x"));
                    runtime.call("COUNT");
                    runtime.call("count");
                    java.lang.reflect.Field counted = instance.getClass().getDeclaredField("counted");
                    counted.setAccessible(true);
                    assertEquals("hooks are found in any case", 2, counted.getInt(instance));
                    assertNull(runtime.value("getRotations", Float[].class));
                } else {
                    runtime.call("onKey", 5);
                    runtime.call("onKey", 7);
                    java.lang.reflect.Field keys = instance.getClass().getDeclaredField("keys");
                    keys.setAccessible(true);
                    assertEquals(12, keys.getInt(instance));
                    assertEquals(Boolean.TRUE, instance.getClass().getMethod("currentSuffix") != null);
                }
            }
        } finally {
            ScriptManager.deleteTree(dir);
        }
    }

    @Test
    public void wrappingKeepsTheUsersLineNumbers() {
        String source = "import java.util.UUID;\nimport java.io.File;\n// comment\nvoid onLoad() {}\n";
        ScriptSource.Wrapped wrapped = ScriptSource.wrap(ScriptKind.ATLAS, "Lines", source, 3);
        String[] lines = wrapped.code.split("\n", -1);
        assertEquals("void onLoad() {}", lines[wrapped.headerLines + 3]);
        assertTrue(wrapped.code.contains("import java.util.UUID;"));
        assertTrue(wrapped.scriptClass.startsWith("sa_Lines_"));
        assertTrue(wrapped.hostCode.contains("extends atlas.script.ScriptHost"));
        assertNotNull(ScriptSource.wrap(ScriptKind.RAVEN17, "Remote", "load - \"https://x/y.java\"\nvoid onLoad() {}", 1).problem);
        assertTrue("a name that is no identifier still makes one",
                ScriptSource.classId("my script (2) 日本", 1).matches("[A-Za-z0-9_]+"));
    }
}
