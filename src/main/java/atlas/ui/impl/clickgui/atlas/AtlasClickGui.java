package atlas.ui.impl.clickgui.atlas;

import atlas.Atlas;
import atlas.management.ModuleHealth;
import atlas.management.PacketHolds;
import atlas.module.Category;
import atlas.module.Module;
import atlas.module.ModuleCategories;
import atlas.property.Property;
import atlas.property.properties.BooleanProperty;
import atlas.property.properties.ColorProperty;
import atlas.property.properties.FloatProperty;
import atlas.property.properties.IntProperty;
import atlas.property.properties.KeyProperty;
import atlas.property.properties.ModeProperty;
import atlas.property.properties.PercentProperty;
import atlas.property.properties.TextProperty;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.input.Keyboard;
import org.lwjgl.opengl.GL11;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A menu built around finding things and understanding them, rather than
 * around looking like a menu.
 *
 * The existing styles all share a shape: columns of collapsible category
 * panels, a row per module, settings hidden behind an arrow. That shape works
 * when a client has thirty modules whose names say what they do. This one has
 * over a hundred, a dozen of which were added in the last week, and several
 * whose behaviour is a paragraph rather than a name -- a module that spends
 * eight minutes measuring without telling you why cannot be explained by being
 * called "Adaptive".
 *
 * So the design puts three things on screen at once, and nothing behind a
 * hover:
 *
 *   SEARCH. Typing filters every module in every category at once. With this
 *   many modules, remembering which category something was filed under is the
 *   actual obstacle, and a search box removes it entirely.
 *
 *   A DETAIL PANE. The selected module's description sits permanently on the
 *   right, wrapped and readable, with its key, whether the array list shows it,
 *   and every setting listed under it.
 *
 *   LIVE STATE. Each row carries the module's own status suffix -- the same
 *   text the array list shows -- so a probe in progress, a flag rate or a hit
 *   percentage is visible while deciding what to change.
 *
 * 2026-09-24: LIQUID GLASS. The window is real refracting glass over the
 * blurred world (Liquid), the text is SF Pro rasterised for the GUI scale
 * (LiquidFont), and everything that moves is on a spring (Spring): the
 * selection pills slide as droplets of clear glass that stretch with their
 * speed and settle with a small overshoot, switches squash as they travel,
 * the window pops open. The layout was prototyped in a browser against the
 * same shaders before any of it was written here.
 *
 * EVERY CONTROL IS HIT TESTED AGAINST WHERE IT WAS ACTUALLY DRAWN. The
 * previous version computed the layout twice -- once to draw it and once to
 * decide what a click had landed on -- and the two copies disagreed by six
 * pixels, so no slider could be dragged. Drawing a control registers the
 * rectangle it occupies; clicking looks the pointer up in that list.
 *
 * Nothing about the modules here is new behaviour. It reads the module list,
 * the property list and the descriptions that were already there, and it
 * writes only through the same setters the other menus use.
 */
public class AtlasClickGui extends GuiScreen {

    private static AtlasClickGui instance;

    // Layout, in scaled pixels.
    private static final int HEADER = 42;
    /* Set from the theme at the start of every frame: the density, the
       corner radius and whether the hint bar has a strip of its own. */
    private int FOOTER = 20;
    private float RADIUS = 18.0F;
    /** A module row, and the pitch rows are laid out at. */
    private int ROW_H = 24;
    private int ROW = 26;
    private int CAT_H = 26;
    /* 2026-10-11 (owner): the category list scrolls, and the column keeps a
       small empty strip at its foot. */
    private static final int SIDEBAR_FOOT = 22;
    private float sidebarScroll;
    private float sidebarMaxScroll;
    private float sidebarRight;
    /* 2026-10-11: the Appearance page's group list scrolls (ten groups did
       not fit, and the wheel did nothing there). */
    private float appearanceScroll;
    private float appearanceMaxScroll;
    private int CAT = 28;

    /** Nothing useful fits below these, so the resize grip stops here. */
    private static final int MIN_WIDTH = 520;
    private static final int MIN_HEIGHT = 280;
    private static final int DEFAULT_WIDTH = 720;
    private static final int DEFAULT_HEIGHT = 400;
    /** Means "no position chosen yet", which is drawn centred. */
    private static final int UNSET = Integer.MIN_VALUE;

    /* Text is opaque and bright; every surface behind it is glass. Text over a
       translucent panel has to be brighter than text over a solid one -- the
       background shows through and eats contrast. */
    /* Dark-mode values. Repainted at the start of every frame by paintPalette(),
       so light mode swaps them for dark text without touching any call site. */
    private static int TEXT = 0xFFF5F8FF;
    private static int DIM = 0xD1C3CEDF;
    private static int FAINT = 0x9E93A3BA;
    private static final int WHITE = 0xFFFFFFFF;
    /* The neutral every hover wash, divider and rim is mixed from: white over
       dark glass, ink over light glass. See ink(). */
    private static int INK = 0xFFFFFFFF;

    /** How the menu looks; edited on the Appearance page, kept in atlas-theme.json. */
    private final AtlasTheme theme = new AtlasTheme();
    private static float textScale = 1.0F;
    private float jelly = 1.0F;
    private float springSpeed = 1.0F;
    private float slideY;

    /** Where the window's size, position and last selection are remembered. */
    private static final File STATE = new File("./config/Atlas/atlas-ui.txt");

    private final List<String> categories = new ArrayList<String>();
    private final Map<String, List<Module>> byCategory = new LinkedHashMap<String, List<Module>>();
    /** The Legit page's sub-groups (see LegitGroups), rebuilt with the catalogue. */
    private final Map<String, List<Module>> legitGroups = new LinkedHashMap<String, List<Module>>();

    private String category = "";
    private String page = "Modules";
    private Module selected;
    /** The name of the module to reselect, held until the list is built. */
    private String pendingSelection = "";
    private final TextInput searchInput = new TextInput(128, false);
    private boolean searchFocused;
    /* What the search looks through (SearchIndex, 2026-10-23: modules and
       settings, names and help), built on open; the last query's results are
       kept until the query or the page changes, so the three callers a frame
       share one search. */
    private SearchIndex index = new SearchIndex();
    private String searchedFor;
    private String searchedOn;
    private SearchIndex.Results searchResults = SearchIndex.NONE;
    private static final int MAX_SETTING_RESULTS = 60;
    /** How many modules the client had when the index was built: it is built again only if that changes. */
    private int indexedModules = -1;

    private int listScroll;
    private int listMaxScroll;
    private int detailScroll;
    private int detailMaxScroll;

    /** The window's own geometry, which the user owns once they touch it. */
    private int userWidth = DEFAULT_WIDTH;
    private int userHeight = DEFAULT_HEIGHT;
    private int userX = UNSET;
    private int userY = UNSET;
    private boolean stateLoaded;

    private boolean movingWindow;
    private boolean resizingWindow;
    private int grabX;
    private int grabY;

    /**
     * The module whose key is being rebound, if any. The key is not a
     * property, so it would never appear among the settings by itself.
     */
    private Module binding;

    /** A key setting (KeyProperty, e.g. Clutch hold-key) waiting for its key. */
    private KeyProperty keyEditing;

    /* The colour setting whose picker is open. It is edited in HSB, kept
       here: a grey or black has no hue of its own, and recomputing it from
       the RGB would throw the hue away every time saturation reached zero.
       colorSynced is the RGB the HSB was taken from, so a change made
       elsewhere (a typed hex, a theme preset) is picked up. */
    private ColorProperty colorOpen;
    private final float[] colorHsb = new float[3];
    private int colorSynced = -1;
    /** What a press on the picker is dragging: "sv", "hue", or null; and that control's rectangle. */
    private String colorDrag;
    private float colorX;
    private float colorY;
    private float colorW;
    private float colorH;
    /** Height of an open picker, below the setting's own row. */
    private static final int PICKER_HEIGHT = 74;

    /* 2026-10-23 (GUI redesign M2): a setting's info row -- what it does in
       full, its default and range, Reset -- opened by the (i) after its label,
       one at a time like the picker. The description's More, and the
       module-wide Reset, which asks first (a Dialog since M7). */
    private Property<?> infoOpen;
    private Module descriptionOpen;
    /** The width of the settings card being drawn, which an info row wraps its text to. */
    private float cardWidth = 200.0F;
    private static final int INFO_LINE = 11;
    private static final float INFO_ICON = 9.0F;
    private static final int DESCRIPTION_MAX_LINES = 14;

    /* 2026-10-23 (GUI redesign M4): two rows above the categories on the
       Modules and Legit pages, lists made on the spot from this page's
       modules -- everything on, and everything that needs a look. */
    private static final String ENABLED = "Enabled";
    private static final String ATTENTION = "Attention";

    /**
     * One module's health marks: what FlagDetector named it for this session
     * (ModuleHealth), what it holds back right now (PacketHolds), and its
     * settings at a risky value (RiskRules). Read four times a second.
     */
    private static final class Health {
        ModuleHealth.Report report = ModuleHealth.Report.NONE;
        int held;
        String heldWay = "";
        final List<Property<?>> risky = new ArrayList<Property<?>>();
        /** Conflicts it is part of and the player has not dismissed (Conflicts, M5). */
        final List<atlas.management.Conflicts.Conflict> conflicts = new ArrayList<atlas.management.Conflicts.Conflict>();
    }

    private final Map<Module, Health> health = new HashMap<Module, Health>();
    private long healthAt;
    private static final long HEALTH_MS = 250L;

    /* Dragging a slider. The track's own rectangle is taken from the registry
       when the drag starts, so the value follows the pointer against the bar
       that is on screen rather than against a second guess at where it is. */
    private Property<?> dragging;
    /** The button that started a drag, so the frame loop can tell when it is let go. */
    private int dragButton;
    /** Ctrl+P: frame cost, averaged, and draws per frame. */
    private boolean perf;
    private float perfMillis;
    private float dragMin;
    private float dragMax;
    private float dragX;
    private float dragWidth;

    /**
     * The setting currently being typed into, and what has been typed. A
     * slider can only express as many values as its track has pixels, and
     * most of the latency settings in this client want an exact number.
     */
    private Property<?> editing;
    private final TextInput editInput = new TextInput(0, false);
    /** Enter was pressed on something that is not a value for the setting: the field stays open, edged red. */
    private boolean editError;

    /** The mode list currently expanded, and where to draw it. */
    private ModeProperty dropdown;
    private float dropX;
    private float dropY;
    private float dropRight;
    /* 2026-10-16: long lists (NoSlow's 34 sword modes) ran off the window.
       The list is capped at DROP_ROWS rows and scrolls with the wheel. */
    private static final int DROP_ROWS = 12;
    private int dropScroll;
    private int dropMaxScroll;
    private boolean dropScrollInit;
    private boolean dropAnchored;

    /** What the pointer is over, shown along the bottom. */
    private String hint = "";

    /* What a click did, or why it could not, shown in the menu (2026-10-23):
       these went to chat, half hidden behind the menu. closing is set while
       the menu closes, when a toast would never be seen and chat is used. */
    private final Toasts toasts = new Toasts();
    private boolean closing;

    /**
     * A question before something that cannot be taken back (2026-10-23, M7):
     * loading over unsaved changes, deleting a profile, resetting a module.
     * It takes every click and key while open; Enter is its first button,
     * Escape closes it.
     */
    private static final class Dialog {
        final String title;
        final String body;
        final String[] labels;
        /** Button styles as drawButton has them: 0 accent, 1 plain, 2 destructive. */
        final int[] styles;
        final Runnable[] actions;

        Dialog(String title, String body, String[] labels, int[] styles, Runnable[] actions) {
            this.title = title;
            this.body = body;
            this.labels = labels;
            this.styles = styles;
            this.actions = actions;
        }
    }

    private Dialog dialog;
    /* Whether the profile in use has changes its file does not (Config.differsFromFile), read once a second. */
    private boolean unsaved;
    private long unsavedAt;
    /** Set by drawField: where the caret is in what it drew. */
    private float fieldCaretX;

    /* The profiles view: a column of its own in the sidebar, under the
       categories, replacing the module list and the detail pane while open. */
    private boolean profilesView;
    /** The Appearance page, and which of its groups is open. */
    private boolean appearanceView;
    /** The Servers page (2026-10-23, M6): which profile ServerProfiles loads on which server. */
    private boolean serversView;
    private String appearanceGroup = "Colors";
    private String profileSelected;
    private final TextInput profileNameInput = new TextInput(32, false);
    private boolean profileNameFocused;
    private int profileScroll;
    private int profileMaxScroll;
    private List<AtlasProfiles.Info> profiles = new ArrayList<AtlasProfiles.Info>();
    private long profilesReadAt;

    /* Motion. Easing and springs are driven by wall time rather than ticks,
       so the feel does not change with frame rate or with the game paused. */
    private long lastFrame;
    private float openedFor;
    private final Spring open = new Spring(0.92F, 260.0F, 17.0F);
    /** Hits, fades, springs and pulses (UiContext); delta is copied into it every frame. */
    private final UiContext ui = new UiContext();
    private float delta = 0.016F;

    /* The opening animation scales the window about its centre. Everything
       is drawn through that transform, and the pointer is taken back through
       it, so a click during the pop still lands on what is under it. */
    private float pivotX;
    private float pivotY;
    private float viewScale = 1.0F;

    /** Glass drawn after the content is captured, so it can refract it. */
    private static final class Lens {
        final float x;
        final float y;
        final float x2;
        final float y2;
        final float radius;
        final Liquid.Style style;
        final float[] clip;

        Lens(float x, float y, float x2, float y2, float radius, Liquid.Style style, float[] clip) {
            this.x = x;
            this.y = y;
            this.x2 = x2;
            this.y2 = y2;
            this.radius = radius;
            this.style = style;
            this.clip = clip;
        }
    }

    private final List<Lens> lenses = new ArrayList<Lens>();

    // ---- hit registry and motion (UiContext) ------------------------------

    private void hit(String kind, Object payload, float x, float y, float x2, float y2) {
        this.ui.hit(kind, payload, x, y, x2, y2);
    }

    private UiContext.Hit hitAt(float mouseX, float mouseY) {
        return this.ui.hitAt(mouseX, mouseY);
    }

    /** Screen to layout, undoing the opening scale. */
    private float layoutX(float screenX) {
        return this.pivotX + (screenX - this.pivotX) / this.viewScale;
    }

    private float layoutY(float screenY) {
        return this.pivotY + (screenY - this.slideY - this.pivotY) / this.viewScale;
    }

    private float toward(Object key, float target, float rate) {
        return this.ui.toward(key, target, rate);
    }

    private float ease(Object key, boolean towards, float rate) {
        return this.ui.ease(key, towards, rate);
    }

    private Spring spring(String key, float target, float stiffness, float damping) {
        return this.ui.spring(key, target, stiffness, damping);
    }

    private float scrollEase(Object key, float target) {
        return this.ui.scrollEase(key, target);
    }

    private void pulse(Object key) {
        this.ui.pulse(key);
    }

    private float pulseAt(Object key, long millis) {
        return this.ui.pulseAt(key, millis);
    }

    /** Slow at both ends, which is what makes a movement look intentional. */
    private static float smooth(float t) {
        t = Math.max(0.0F, Math.min(1.0F, t));
        return t * t * (3.0F - 2.0F * t);
    }

    private static int alpha(int colour, float factor) {
        return Glass.alpha(colour, factor);
    }

    private AtlasClickGui() {
        loadState();
        build();
    }

    public static AtlasClickGui getInstance() {
        if (instance == null) {
            instance = new AtlasClickGui();
        }
        return instance;
    }

    /**
     * Categories come from the same arrays the other menus use, so a module
     * added to those appears here without this class being touched.
     */
    private void build() {
        this.categories.clear();
        this.byCategory.clear();
        for (Map.Entry<String, List<Module>> entry : AtlasCatalogue.catalogue().entrySet()) {
            if (entry.getValue().isEmpty()) {
                continue;
            }
            this.categories.add(entry.getKey());
            this.byCategory.put(entry.getKey(), entry.getValue());
        }
        this.legitGroups.clear();
        List<Module> legit = this.byCategory.get("Legit");
        if (legit != null) {
            this.legitGroups.putAll(LegitGroups.group(legit));
        }
        if (isQuick(this.category)) {
            /* Enabled and Attention are lists of the page, not categories. */
        } else if ("Legit".equals(this.page) && !this.legitGroups.isEmpty()) {
            if (!this.legitGroups.containsKey(this.category)) {
                this.category = this.legitGroups.keySet().iterator().next();
            }
        } else if (!this.categories.contains(this.category) || "Legit".equals(this.category)) {
            this.category = firstModuleCategory();
        }
        if ("Client Settings".equals(this.page) && !this.profilesView && !this.appearanceView && !this.serversView) {
            this.profilesView = true;
        }
        if (!this.categories.isEmpty() && this.category.isEmpty()) {
            this.category = this.categories.get(0);
        }
        /* The remembered selection is a name, because the module instance from
           a previous run of the game is not the one in this one. */
        if (this.selected == null && !this.pendingSelection.isEmpty()) {
            for (List<Module> list : this.byCategory.values()) {
                for (Module module : list) {
                    if (module != null && module.getName().equals(this.pendingSelection)) {
                        this.selected = module;
                    }
                }
            }
            this.pendingSelection = "";
        }
    }

    private String firstModuleCategory() {
        for (String name : this.categories) {
            if (!"Legit".equals(name)) {
                return name;
            }
        }
        return this.categories.isEmpty() ? "" : this.categories.get(0);
    }

    private List<String> sidebarCategories() {
        List<String> out = new ArrayList<String>();
        if ("Legit".equals(this.page) || "Modules".equals(this.page)) {
            out.add(ENABLED);
            out.add(ATTENTION);
        }
        if ("Legit".equals(this.page)) {
            out.addAll(this.legitGroups.keySet());
        } else if ("Modules".equals(this.page)) {
            for (String name : this.categories) {
                if (!"Legit".equals(name)) {
                    out.add(name);
                }
            }
        }
        return out;
    }

    // ---- persistence --------------------------------------------------

    /**
     * Reads back where the window was and what was open in it.
     *
     * Kept in its own file rather than in the client's config, deliberately.
     * The config is held in memory for a whole session and written once on
     * exit, so anything written into it from here would be overwritten by
     * whatever that copy held; and a menu's window position is not a setting
     * anyone wants travelling with a shared profile.
     */
    private void loadState() {
        if (this.stateLoaded) {
            return;
        }
        this.stateLoaded = true;
        BufferedReader reader = null;
        try {
            if (!STATE.exists()) {
                return;
            }
            reader = new BufferedReader(new FileReader(STATE));
            String line;
            while ((line = reader.readLine()) != null) {
                int split = line.indexOf('=');
                if (split <= 0) {
                    continue;
                }
                String key = line.substring(0, split).trim();
                String value = line.substring(split + 1).trim();
                if ("x".equals(key)) {
                    this.userX = Integer.parseInt(value);
                } else if ("y".equals(key)) {
                    this.userY = Integer.parseInt(value);
                } else if ("width".equals(key)) {
                    this.userWidth = Math.max(MIN_WIDTH, Integer.parseInt(value));
                } else if ("height".equals(key)) {
                    this.userHeight = Math.max(MIN_HEIGHT, Integer.parseInt(value));
                } else if ("category".equals(key)) {
                    this.category = value;
                } else if ("selected".equals(key)) {
                    this.pendingSelection = value;
                } else if ("view".equals(key)) {
                    this.profilesView = "profiles".equals(value);
                    this.appearanceView = "appearance".equals(value);
                    this.serversView = "servers".equals(value);
                    if (this.profilesView || this.appearanceView || this.serversView) {
                        this.page = "Client Settings";
                    }
                } else if ("page".equals(key)) {
                    if ("Modules".equals(value) || "Legit".equals(value) || "Client Settings".equals(value)) {
                        this.page = value;
                    }
                }
            }
        } catch (Exception ignored) {
            /* A missing or unreadable file means the defaults, which is what
               this menu did before it remembered anything at all. */
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (Exception ignored) {
                    // Nothing further to do.
                }
            }
        }
    }

    private void saveState() {
        PrintWriter writer = null;
        try {
            File dir = STATE.getParentFile();
            if (dir != null && !dir.exists() && !dir.mkdirs()) {
                return;
            }
            writer = new PrintWriter(STATE);
            writer.println("x=" + this.userX);
            writer.println("y=" + this.userY);
            writer.println("width=" + this.userWidth);
            writer.println("height=" + this.userHeight);
            writer.println("category=" + this.category);
            writer.println("selected=" + (this.selected == null ? "" : this.selected.getName()));
            writer.println("page=" + this.page);
            writer.println("view=" + (this.profilesView ? "profiles" : this.appearanceView ? "appearance"
                    : this.serversView ? "servers" : "modules"));
            writer.flush();
        } catch (Exception ignored) {
            // Losing a window position is not worth an error on screen.
        } finally {
            if (writer != null) {
                writer.close();
            }
        }
    }

    // ---- layout helpers ----------------------------------------------

    /** TargetOrbit, drawn beside the window while it is selected (2026-10-18). */
    private final OrbitPreview orbitPreview = new OrbitPreview();

    private int windowWidth(ScaledResolution sr) {
        return Math.max(Math.min(MIN_WIDTH, sr.getScaledWidth() - 8),
                Math.min(this.userWidth, sr.getScaledWidth() - 8));
    }

    private int windowHeight(ScaledResolution sr) {
        return Math.max(Math.min(MIN_HEIGHT, sr.getScaledHeight() - 8),
                Math.min(this.userHeight, sr.getScaledHeight() - 8));
    }

    private int left(ScaledResolution sr) {
        int width = windowWidth(sr);
        if (this.userX == UNSET) {
            return (sr.getScaledWidth() - width) / 2;
        }
        return Math.max(0, Math.min(sr.getScaledWidth() - width, this.userX));
    }

    private int top(ScaledResolution sr) {
        int height = windowHeight(sr);
        if (this.userY == UNSET) {
            return (sr.getScaledHeight() - height) / 2;
        }
        return Math.max(0, Math.min(sr.getScaledHeight() - height, this.userY));
    }

    /* The columns take a share of the window rather than a fixed number of
       pixels, so making the window bigger buys room in all three. They are
       clamped at both ends: a sidebar narrower than its longest category name
       is useless, and a detail pane much wider than three hundred pixels wraps
       descriptions into lines too long for the eye to find the start of. */
    private static int sidebarWidth(int width) {
        return Math.max(118, Math.min(160, Math.round(width * 0.19F)));
    }

    private static int detailWidth(int width) {
        return Math.max(210, Math.min(300, Math.round(width * 0.35F)));
    }

    /** Modules shown in the middle column: a search result, or one category. */
    private List<Module> visibleModules() {
        List<Module> out = new ArrayList<Module>();
        if ("Client Settings".equals(this.page)) {
            return out;
        }
        String needle = search().toLowerCase().trim();
        if (!needle.isEmpty()) {
            /* Search spans every page: this page's matches first, then the other
               page's, which the list draws as borrowed (see drawList). Modules
               found by name come before those found by their description. */
            for (SearchIndex.Entry entry : searchResults().modules) {
                out.add((Module) entry.module);
            }
            return out;
        }
        List<Module> list = modulesOf(this.category);
        if (list != null) {
            for (Module module : list) {
                if (module != null) {
                    out.add(module);
                }
            }
        }
        return out;
    }

    private boolean belongsOnPage(Module module) {
        Category category = ModuleCategories.of(module.getClass());
        if ("Legit".equals(this.page)) {
            return category == Category.LEGIT;
        }
        return category != Category.LEGIT;
    }

    private void selectPage(String next) {
        if (!"Modules".equals(next) && !"Legit".equals(next) && !"Client Settings".equals(next)) {
            return;
        }
        this.page = next;
        this.searchInput.clear();
        this.searchFocused = false;
        this.listScroll = 0;
        this.detailScroll = 0;
        this.dropdown = null;
        if ("Legit".equals(next)) {
            this.category = this.legitGroups.isEmpty() ? "Legit" : this.legitGroups.keySet().iterator().next();
            this.profilesView = false;
            this.appearanceView = false;
            this.serversView = false;
            List<Module> modules = visibleModules();
            this.selected = modules.isEmpty() ? null : modules.get(0);
        } else if ("Modules".equals(next)) {
            this.profilesView = false;
            this.appearanceView = false;
            this.serversView = false;
            if ("Legit".equals(this.category) || this.legitGroups.containsKey(this.category)) {
                this.category = firstModuleCategory();
            }
            List<Module> modules = visibleModules();
            if (this.selected == null || !belongsOnPage(this.selected)) {
                this.selected = modules.isEmpty() ? null : modules.get(0);
            }
        } else {
            this.profilesView = true;
            this.appearanceView = false;
            this.serversView = false;
            this.selected = null;
            this.profileScroll = 0;
        }
    }

    private List<Property<?>> propertiesOf(Module module) {
        List<Property<?>> out = new ArrayList<Property<?>>();
        if (module == null) {
            return out;
        }
        for (Property<?> property : Atlas.propertyManager.properties.getOrDefault(
                module.getClass(), new ArrayList<Property<?>>())) {
            if (property.isVisible()) {
                out.add(property);
            }
        }
        return out;
    }

    private int enabledCount() {
        int on = 0;
        List<Module> seen = new ArrayList<Module>();
        for (List<Module> list : this.byCategory.values()) {
            for (Module module : list) {
                if (module != null && module.isEnabled() && !seen.contains(module)) {
                    seen.add(module);
                    on++;
                }
            }
        }
        return on;
    }

    // ---- numeric settings, whatever class they are ---------------------

    /* Three unrelated classes carry a bounded number: IntProperty,
       FloatProperty and PercentProperty, which extends neither of the others.
       The menu once handled the first two and silently rendered the third as
       plain text, so a percentage could not be set with the pointer at all. */

    private static boolean isNumeric(Property<?> property) {
        return property instanceof IntProperty
                || property instanceof FloatProperty
                || property instanceof PercentProperty;
    }

    private static boolean isIntegral(Property<?> property) {
        return property instanceof IntProperty || property instanceof PercentProperty;
    }

    private static float minimumOf(Property<?> property) {
        if (property instanceof IntProperty) {
            return ((IntProperty) property).getMinimum();
        }
        if (property instanceof PercentProperty) {
            return ((PercentProperty) property).getMinimum();
        }
        return ((FloatProperty) property).getMinimum();
    }

    private static float maximumOf(Property<?> property) {
        if (property instanceof IntProperty) {
            return ((IntProperty) property).getMaximum();
        }
        if (property instanceof PercentProperty) {
            return ((PercentProperty) property).getMaximum();
        }
        return ((FloatProperty) property).getMaximum();
    }

    private static float currentOf(Property<?> property) {
        if (property instanceof IntProperty) {
            return ((IntProperty) property).getBaseValue();
        }
        if (property instanceof PercentProperty) {
            return ((PercentProperty) property).getBaseValue();
        }
        return ((FloatProperty) property).getBaseValue();
    }

    private static void assign(Property<?> property, float value) {
        value = Math.max(minimumOf(property), Math.min(maximumOf(property), value));
        if (isIntegral(property)) {
            property.setBase(Property.Source.USER, Integer.valueOf(Math.round(value)));
        } else {
            property.setBase(Property.Source.USER, Float.valueOf(Math.round(value * 100.0F) / 100.0F));
        }
    }

    // ---- type ---------------------------------------------------------

    private static LiquidFont font(float size, boolean bold) {
        return LiquidFont.of(Math.round(size * textScale * 4.0F) / 4.0F, bold);
    }

    // ---- drawing ------------------------------------------------------

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        atlas.module.ModuleDocs.setEnglish(true);
        ScaledResolution sr = new ScaledResolution(mc);
        long now = System.nanoTime();
        this.delta = this.lastFrame == 0L ? 0.016F
                : Math.min(0.05F, (now - this.lastFrame) / 1.0E9F);
        this.lastFrame = now;
        this.openedFor += this.delta;
        this.ui.delta = this.delta;
        this.hint = "";
        this.ui.beginFrame();
        this.lenses.clear();
        this.dropAnchored = false;
        AtlasInspector.beginFrame();

        long frameStart = System.nanoTime();
        applyTheme();
        paintPalette();
        followDrag(sr, mouseX, mouseY);
        /* Before anything of the menu is drawn: the copy it blurs has to be
           of the world alone. */
        long blurStart = System.nanoTime();
        Liquid.beginFrame();
        long blurNanos = System.nanoTime() - blurStart;

        int width = windowWidth(sr);
        int height = windowHeight(sr);
        int x = left(sr);
        int y = top(sr);
        int sidebar = sidebarWidth(width);
        int detail = detailWidth(width);

        /* The window arrives the way the theme says: popping from 92% on a
           spring that overshoots a little, fading, or sliding up, while it
           fades in over the first fifth of a second. */
        int opening = this.theme.animations.getValue() ? this.theme.opening.getValue() : 3;
        this.open.target = 1.0F;
        float progress = opening == 3 ? 1.0F : this.open.step(this.delta * this.springSpeed);
        this.viewScale = opening == 0 ? progress : 1.0F;
        this.slideY = opening == 2 ? (1.0F - progress) / 0.08F * 18.0F : 0.0F;
        this.pivotX = x + width / 2.0F;
        this.pivotY = y + height / 2.0F;
        float fade = opening == 3 ? 1.0F : smooth(this.openedFor / 0.18F);
        float mx = layoutX(mouseX);
        float my = layoutY(mouseY);

        /* The light the glass catches follows the pointer, a little behind. */
        boolean follow = this.theme.followCursor.getValue();
        Liquid.lightX = toward("lightX", follow ? mouseX : x + width * 0.25F, 7.0F);
        Liquid.lightY = toward("lightY", follow ? mouseY : y - 120.0F, 7.0F);

        GlStateManager.enableBlend();
        GlStateManager.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        Liquid.alpha = fade;
        LiquidFont.alpha = fade;
        GL11.glPushMatrix();
        try {
            /* The world behind is dimmed a little so the window is the
               brightest thing on screen. */
            float dim = this.theme.dim.getValue() / 100.0F;
            if (dim > 0.0F) {
                Liquid.rect(0, 0, sr.getScaledWidth(), sr.getScaledHeight(), 0.0F,
                        alpha(0xFF050810, dim * 0.87F), alpha(0xFF050810, Math.min(1.0F, dim * 1.25F)));
            }
            if (this.theme.cosmos.getValue()) {
                Cosmos.draw(sr.getScaledWidth(), sr.getScaledHeight(), accent(), this.theme.stars.getValue() / 100.0F,
                        this.theme.comets.getValue(), this.theme.planet.getValue(), fade);
            }

            GL11.glTranslatef(this.pivotX, this.pivotY + this.slideY, 0.0F);
            GL11.glScalef(this.viewScale, this.viewScale, 1.0F);
            GL11.glTranslatef(-this.pivotX, -this.pivotY, 0.0F);
            Liquid.setView(this.viewScale, this.pivotX * (1.0F - this.viewScale),
                    this.pivotY * (1.0F - this.viewScale) + this.slideY);

            float shadowSize = this.theme.shadowSize.getValue();
            if (this.theme.shadow.getValue() > 0 && shadowSize > 0) {
                Liquid.shadow(x, y, x + width, y + height, RADIUS, shadowSize,
                        alpha(0xFF000000, this.theme.shadow.getValue() / 100.0F), shadowSize * 0.4F);
            }
            Liquid.pane(x, y, x + width, y + height, RADIUS, this.theme.pane());
            /* Light caught in the thickness along the top. */
            int sheen = Math.round(this.theme.sheen.getValue() / 100.0F * 26.0F);
            if (sheen > 0) {
                Liquid.rect(x, y, x + width, y + 90, RADIUS, (sheen << 24) | 0x00FFFFFF, 0x00FFFFFF);
            }

            /* Registered first, so everything drawn into the header afterwards
               -- the search box above all -- sits on top of it and wins. */
            hit("move", null, x, y, x + width, y + HEADER - 1);

            int bodyTop = y + HEADER;
            int bodyHeight = height - HEADER - FOOTER;
            drawHeader(x, y, width, sidebar, detail, mx, my);
            Liquid.rect(x + 14, y + HEADER - 0.5F, x + width - 14, y + HEADER, 0.0F, ink(0x12));
            Liquid.rect(x + sidebar, bodyTop + 10, x + sidebar + 0.5F, bodyTop + bodyHeight - 10, 0.0F, ink(0x0F));
            Liquid.rect(x + width - detail, bodyTop + 10, x + width - detail + 0.5F,
                    bodyTop + bodyHeight - 10, 0.0F, ink(0x0F));

            drawSidebar(x, bodyTop, bodyHeight, sidebar, mx, my);
            if (this.profilesView) {
                drawProfileList(sr, x + sidebar, bodyTop, width - sidebar - detail, bodyHeight, mx, my);
                drawProfileDetail(sr, x + width - detail, bodyTop, detail, bodyHeight, mx, my);
            } else if (this.serversView) {
                drawServersList(x + sidebar, bodyTop, width - sidebar - detail, mx, my);
                drawServersDetail(x + width - detail, bodyTop, detail, mx, my);
            } else if (this.appearanceView) {
                drawAppearanceList(sr, x + sidebar, bodyTop, width - sidebar - detail, bodyHeight, mx, my);
                drawAppearanceDetail(sr, x + width - detail, bodyTop, detail, bodyHeight, mx, my);
            } else {
                drawList(sr, x + sidebar, bodyTop, width - sidebar - detail, bodyHeight, mx, my);
                drawDetail(sr, x + width - detail, bodyTop, detail, bodyHeight, mx, my);
            }
            if (this.theme.hintBar.getValue()) {
                drawHintBar(x, y + height - FOOTER, width);
            }
            drawGrip(x + width, y + height, mx, my);

            /* The lenses refract what is under them, so what is under them has
               to be finished first. */
            Liquid.captureContent(x, y, x + width, y + height);
            for (Lens lens : this.lenses) {
                if (lens.clip != null) {
                    clip(sr, lens.clip[0], lens.clip[1], lens.clip[2], lens.clip[3]);
                }
                int selectionStyle = this.theme.selection.getValue();
                if (lens.style.selection && selectionStyle == 1) {
                    Liquid.rect(lens.x, lens.y, lens.x2, lens.y2, lens.radius,
                            alpha(accent(), 0.10F + this.theme.selectionTint.getValue() / 100.0F),
                            alpha(accent(), 0.06F + this.theme.selectionTint.getValue() / 100.0F));
                } else if (lens.style.selection && selectionStyle == 2) {
                    Liquid.rim(lens.x, lens.y, lens.x2, lens.y2, lens.radius, 1.2F,
                            alpha(accent(), 0.85F), alpha(accent(), 0.45F));
                } else {
                    Liquid.lens(lens.x, lens.y, lens.x2, lens.y2, lens.radius, lens.style);
                }
                if (lens.clip != null) {
                    unclip();
                }
            }

            /* Over the content, under an open list and the report dialog. */
            drawToasts(x, y, width, height, mx, my);

            /* Last of all, and outside every scissor: an expanded mode list has
               to cover the rows below it, and a clipped dropdown is worse than
               none. */
            if (this.dropdown != null) {
                if (this.dropAnchored) {
                    drawDropdown(y, height, mx, my);
                } else {
                    /* Its row scrolled out of the pane, so there is nothing
                       left to hang the list from. */
                    this.dropdown = null;
                }
            }
            if (this.reportConfirm) {
                drawReportConfirm(x, y, width, height, mx, my);
            }
            if (this.dialog != null) {
                drawDialog(x, y, width, height, mx, my);
            }
        } finally {
            GL11.glPopMatrix();
            Liquid.setView(1.0F, 0.0F, 0.0F);
            Liquid.alpha = 1.0F;
            LiquidFont.alpha = 1.0F;
            unclip();
        }

        /* Outside the window, after it: TargetOrbit's live preview in the gap beside it. */
        this.orbitPreview.draw(!this.profilesView && !this.appearanceView && !this.serversView
                        && this.selected instanceof atlas.module.modules.TargetOrbit,
                sr.getScaledWidth(), x, y, width, height, mouseX, mouseY, this.delta,
                true, accent());

        this.theme.autosave();
        this.perfMillis += ((System.nanoTime() - frameStart) / 1.0E6F - this.perfMillis) * 0.05F;
        atlas.ui.GuiPerf.frame((System.nanoTime() - frameStart) / 1.0E6, blurNanos / 1.0E6);
        if (this.perf) {
            String readout = String.format("%.2f ms  ·  %d draws  ·  %d fps", this.perfMillis, Liquid.draws,
                    net.minecraft.client.Minecraft.getDebugFPS());
            font(8.0F, false).drawRight(readout, sr.getScaledWidth() - 6, sr.getScaledHeight() - 8, 0xFFFFD166);
        }
        GlStateManager.disableBlend();
        super.drawScreen(mouseX, mouseY, partialTicks);
        /* Last, so the capture includes everything this frame drew and the
           audit is of the same frame that was captured. */
        AtlasInspector.tick();
    }

    /* Read from the jar and uploaded as a dynamic texture, like WaterMark's
       pictures: the mod's own assets are not in the game's resource packs,
       so a plain ResourceLocation found nothing and drew the missing-texture
       checkerboard (seen 2026-10-06). */
    private static ResourceLocation logo;
    private static boolean logoTried;

    private static ResourceLocation logo() {
        if (!logoTried) {
            logoTried = true;
            try (java.io.InputStream in = AtlasClickGui.class.getResourceAsStream("/assets/atlas/assets/atlas-logo.png")) {
                if (in != null) {
                    logo = net.minecraft.client.Minecraft.getMinecraft().getTextureManager().getDynamicTextureLocation(
                            "atlas_logo",
                            new net.minecraft.client.renderer.texture.DynamicTexture(javax.imageio.ImageIO.read(in)));
                }
            } catch (Exception ignored) {
                logo = null;
            }
        }
        return logo;
    }

    /** The logo texture as a square at (x, y), faded with the rest of the menu. */
    private static void drawLogo(float x, float y, float size) {
        ResourceLocation texture = logo();
        if (texture == null) {
            return;
        }
        GlStateManager.enableTexture2D();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO);
        GlStateManager.color(1.0F, 1.0F, 1.0F, Liquid.alpha);
        net.minecraft.client.Minecraft.getMinecraft().getTextureManager().bindTexture(texture);
        /* Smoothed: the 64 px picture is drawn at 18 GUI pixels. */
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        Tessellator tessellator = Tessellator.getInstance();
        WorldRenderer buffer = tessellator.getWorldRenderer();
        buffer.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX);
        buffer.pos(x, y + size, 0.0D).tex(0.0D, 1.0D).endVertex();
        buffer.pos(x + size, y + size, 0.0D).tex(1.0D, 1.0D).endVertex();
        buffer.pos(x + size, y, 0.0D).tex(1.0D, 0.0D).endVertex();
        buffer.pos(x, y, 0.0D).tex(0.0D, 0.0D).endVertex();
        tessellator.draw();
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
    }

    /** The bug-report dialog is open: nothing is sent until Send. */
    private boolean reportConfirm;
    /* 2026-10-08: the reason picked (an index of BugReport.REASONS, -1 none
       yet) and what the player typed about it. */
    private int reportReason = -1;
    private static final int REPORT_TEXT_MAX = 500;
    private final TextInput reportInput = new TextInput(REPORT_TEXT_MAX, true);

    private void openReport() {
        this.reportConfirm = true;
        this.reportReason = -1;
        this.reportInput.clear();
    }

    private boolean reportChinese() {
        return false;
    }

    private void sendReport() {
        if (this.reportReason < 0) {
            /* A reason is required: the Send button says so. */
            pulse("btn:reportSend");
            return;
        }
        this.reportConfirm = false;
        if (Atlas.notificationManager != null) {
            Atlas.notificationManager.add("Sending bug report...", 2000L);
        }
        atlas.util.BugReport.send(atlas.util.BugReport.REASONS[this.reportReason], this.reportInput.text(),
                atlas.command.commands.ReportCommand::announce);
    }

    /** An amber warning triangle with an exclamation mark, centred on (cx, cy). */
    private void drawWarning(float cx, float cy, float size) {
        int amber = 0xFFFFC857;
        float top = cy - size;
        float bottom = cy + size * 0.85F;
        float half = size * 1.05F;
        float w = Math.max(1.1F, size * 0.26F);
        Liquid.line(cx, top, cx - half, bottom, w, amber);
        Liquid.line(cx - half, bottom, cx + half, bottom, w, amber);
        Liquid.line(cx + half, bottom, cx, top, w, amber);
        Liquid.line(cx, cy - size * 0.35F, cx, cy + size * 0.25F, w, amber);
        Liquid.dot(cx, cy + size * 0.55F, w * 0.6F, amber);
    }

    /**
     * Asks before anything leaves the machine: what goes to Discord, what is
     * taken out, and Send / Cancel. Drawn over the whole window.
     */
    private void drawReportConfirm(int x, int y, int width, int height, float mx, float my) {
        Liquid.rect(x, y, x + width, y + height, RADIUS, 0x99000000, 0xAA000000);
        hit("reportOutside", null, x, y, x + width, y + height);

        boolean zh = reportChinese();
        float w = Math.min(380.0F, width - 20.0F);
        float h = 276.0F;
        float bx = x + (width - w) / 2.0F;
        float by = y + (height - h) / 2.0F;
        Liquid.shadow(bx, by, bx + w, by + h, 16.0F, 18.0F, 0x80000000, 4.0F);
        Liquid.rect(bx, by, bx + w, by + h, 16.0F, atlas.ui.UiMode.isLight() ? 0xFAF6F8FB : 0xF2141820,
                atlas.ui.UiMode.isLight() ? 0xFAEEF1F5 : 0xF20D1016);
        Liquid.rim(bx, by, bx + w, by + h, 16.0F, 1.0F, alpha(0xFFFFC857, 0.55F), ink(0x10));
        hit("reportDialog", null, bx, by, bx + w, by + h);

        float cx = bx + w / 2.0F;
        int accent = accent();
        drawWarning(cx, by + 22.0F, 10.0F);
        font(13.0F, true).drawCentred(zh ? "回報問題" : "Send a bug report", cx, by + 44.0F, TEXT);

        /* What went wrong: one of a few reasons, required. */
        LiquidFont label = font(8.0F, true);
        label.draw(zh ? "發生什麼問題？（必選）" : "What went wrong? (pick one)", bx + 18, by + 64.0F, DIM);
        String[] reasons = zh ? atlas.util.BugReport.REASONS_ZH : atlas.util.BugReport.REASONS;
        float chipGap = 6.0F;
        float chipW = (w - 36.0F - chipGap * 2.0F) / 3.0F;
        float chipH = 18.0F;
        LiquidFont chipFont = font(8.0F, false);
        for (int i = 0; i < reasons.length; i++) {
            float chipX = bx + 18 + (i % 3) * (chipW + chipGap);
            float chipY = by + 74.0F + (i / 3) * (chipH + chipGap);
            boolean picked = i == this.reportReason;
            boolean hovered = mx >= chipX && mx <= chipX + chipW && my >= chipY && my <= chipY + chipH;
            hit("reportReason", Integer.valueOf(i), chipX, chipY, chipX + chipW, chipY + chipH);
            float lit = ease("reportReason:" + i, picked, 14.0F);
            float hover = ease("reportReasonHover:" + i, hovered, 12.0F);
            Liquid.rect(chipX, chipY, chipX + chipW, chipY + chipH, 9.0F,
                    alpha(accent, 0.10F + 0.22F * lit + 0.06F * hover), ink(0x08));
            Liquid.rim(chipX, chipY, chipX + chipW, chipY + chipH, 9.0F, 1.0F,
                    blend(ink(0x26), alpha(accent, 0.9F), lit), ink(0x0A));
            chipFont.drawCentred(chipFont.trim(reasons[i], chipW - 8.0F), chipX + chipW / 2.0F, chipY + chipH / 2.0F,
                    picked ? TEXT : DIM);
        }

        /* In the player's own words: optional, always typing here. */
        float textTop = by + 128.0F;
        label.draw(zh ? "描述一下（選填）：做了什麼、看到什麼" : "Describe it (optional): what you did, what you saw",
                bx + 18, textTop, DIM);
        float fx = bx + 18;
        float fy = textTop + 10.0F;
        float fx2 = bx + w - 18;
        float fy2 = fy + 62.0F;
        hit("reportText", null, fx, fy, fx2, fy2);
        Liquid.shadow(fx, fy, fx2, fy2, 10.0F, 6.0F, alpha(accent, 0.22F), 0.0F);
        Liquid.rect(fx, fy, fx2, fy2, 10.0F, alpha(INK, 0.08F), ink(0x0B));
        Liquid.rim(fx, fy, fx2, fy2, 10.0F, 1.0F, alpha(accent, 0.7F), ink(0x0A));
        LiquidFont body = font(8.5F, false);
        float caretX = fx + 9;
        float caretY = fy + 10;
        String typedText = this.reportInput.text();
        if (typedText.isEmpty()) {
            body.draw(zh ? "例：用 Scaffold 搭橋時一直被 Grim Simulation 抓" : "e.g. Scaffold gets flagged by Grim Simulation when bridging",
                    fx + 9, fy + 10, FAINT);
        } else {
            if (this.reportInput.allSelected()) {
                Liquid.rect(fx + 4, fy + 4, fx2 - 4, fy2 - 4, 8.0F, alpha(accent, 0.14F));
            }
            /* Four lines show: the four around the caret, which while typing
               at the end are the last four, as before. */
            List<int[]> lines = TextLayout.wrap(typedText, body::width, fx2 - fx - 20);
            int caret = this.reportInput.caret();
            int caretLine = TextLayout.lineOf(lines, caret);
            int first = Math.max(0, Math.min(lines.size() - 4, caretLine));
            for (int i = first; i < Math.min(lines.size(), first + 4); i++) {
                int[] line = lines.get(i);
                float lineY = fy + 10 + (i - first) * 13.0F;
                body.draw(typedText.substring(line[0], line[1]), fx + 9, lineY, TEXT);
                if (i == caretLine) {
                    caretX = fx + 9 + body.width(typedText.substring(line[0], Math.max(line[0], Math.min(line[1], caret))));
                    caretY = lineY;
                }
            }
        }
        if (AtlasStyle.caretOn()) {
            Liquid.rect(caretX + 1, caretY - 4.5F, caretX + 2, caretY + 4.5F, 0.0F, accent);
        }
        font(7.0F, false).drawRight(typedText.length() + " / " + REPORT_TEXT_MAX, fx2, fy2 + 7.0F, FAINT);

        LiquidFont small = font(7.5F, false);
        small.drawCentred(zh ? "會上傳到 Discord #bug-reports：log 和設定檔（分成兩個檔案）"
                        : "Uploads to Discord #bug-reports: the log and your config, as two files",
                cx, fy2 + 18.0F, DIM);
        small.drawCentred(zh ? "你的名字、其他玩家名字、token 和 IP 都會先移除"
                        : "Your name, other players' names, tokens and IPs are removed",
                cx, fy2 + 29.0F, alpha(0xFFFFC857, 0.95F));

        float buttonsY = by + h - 20.0F;
        String cancel = zh ? "取消" : "Cancel";
        String send = zh ? "送出" : "Send";
        float cancelW = font(8.5F, true).width(cancel) + 30;
        float sendW = font(8.5F, true).width(send) + 40;
        float gap = 10.0F;
        float left = cx - (cancelW + gap + sendW) / 2.0F;
        drawButton("reportCancel", null, left, buttonsY, cancelW, cancel, 1, mx, my);
        drawButton("reportSend", null, left + cancelW + gap, buttonsY, sendW, send,
                this.reportReason >= 0 ? 0 : 1, mx, my);
        if (mx >= bx && mx <= bx + w && my >= by && my <= by + h) {
            this.hint = this.reportReason < 0
                    ? (zh ? "先選一個問題類型才能送出  ·  Esc 取消" : "Pick what went wrong to send  ·  Esc cancels")
                    : (zh ? "Enter 送出  ·  Esc 取消  ·  Ctrl+V 貼上" : "Enter sends  ·  Esc cancels  ·  Ctrl+V pastes");
        }
    }

    private void drawHeader(int x, int y, int width, int sidebar, int detail, float mx, float my) {
        float cy = y + HEADER / 2.0F;
        int accent = accent();

        /* The mark: the Atlas logo (the A with the orbit) and the name.
           One word since the rename (2026-10-14); before, the old name in the
           text colour and ATLAS in lime. */
        Liquid.shadow(x + 11, cy - 9, x + 29, cy + 9, 5.0F, 5.0F, 0x55000000, 0.0F);
        drawLogo(x + 11, cy - 9, 18.0F);
        float brandEnd = x + 35 + font(12.5F, true).draw("ATLAS", x + 35, cy, TEXT);

        /* One row: the page tabs after the brand, the HUD editor and the
           dark/light switch on the right, and the search box filling the rest. */
        float tabsEnd = drawPageTabs(brandEnd + 14, cy, mx, my);

        float right = x + width - 12;
        float editorWidth = font(8.5F, true).width("HUD Editor") + 22;
        float editorX = right - editorWidth;
        drawButton("hudEditor", null, editorX, cy, editorWidth, "HUD Editor", 0, mx, my);
        if (mx >= editorX && mx <= right && my >= cy - 10 && my <= cy + 10) {
            this.hint = "Move the HUD elements on screen";
        }
        /* One-click bug report (2026-10-07): copies everything #bug-reports asks for. */
        float reportWidth = font(8.5F, true).width("Report") + 34;
        float reportX = editorX - 8 - reportWidth;
        drawButton("bugReport", null, reportX, cy, reportWidth, "    Report", 0, mx, my);
        drawWarning(reportX + 13.0F, cy, 5.0F);
        if (mx >= reportX && mx <= reportX + reportWidth && my >= cy - 10 && my <= cy + 10) {
            this.hint = "Send a bug report to the Discord #bug-reports channel  ·  asks first";
        }
        float modeLeft = drawModeSwitch(reportX - 10, cy, width >= 600, mx, my);

        /* Search covers every page; results from the other page are marked in the list. */
        float sx = tabsEnd + 12;
        float sx2 = modeLeft - 10;
        float sy = cy - 11.0F;
        float sy2 = cy + 11.0F;
        float scy = cy;
        boolean searchable = !"Client Settings".equals(this.page) && sx2 - sx >= 70.0F;
        if (searchable) {
            hit("search", null, sx, sy, sx2, sy2);
        }
        boolean hovered = searchable && mx >= sx && mx <= sx2 && my >= sy && my <= sy2;
        float focus = ease("searchFocus", this.searchFocused || !this.searchInput.isEmpty(), 12.0F);
        float lift = ease("searchHover", hovered, 12.0F);
        if (searchable && focus > 0.01F) {
            Liquid.shadow(sx, sy, sx2, sy2, 11.0F, 7.0F, alpha(accent, 0.32F * focus), 0.0F);
        }
        if (searchable) {
            Liquid.rect(sx, sy, sx2, sy2, 11.0F, alpha(INK, 0.075F + 0.03F * lift + 0.03F * focus),
                    alpha(INK, 0.045F + 0.015F * lift));
            Liquid.rim(sx, sy, sx2, sy2, 11.0F, 1.0F, blend(ink(0x33), alpha(accent, 0.8F), focus),
                    ink(0x0D));
            icon("search", sx + 12, scy, focus > 0.5F ? TEXT : FAINT);
            LiquidFont body = font(9.0F, false);
            float caret = sx + 22;
            if (this.searchInput.isEmpty()) {
                body.draw(this.searchFocused ? "" : body.trim("Search modules and settings", sx2 - sx - 34), sx + 23,
                        scy, FAINT);
                this.searchInput.drawnAt(sx + 23, 0, body::width);
            } else {
                drawField(this.searchInput, body, layoutField(this.searchInput, body, sx2 - sx - 70, false),
                        sx + 23, scy, TEXT, false);
                caret = this.fieldCaretX;
                String results = searchResults().size() + " found";
                font(8.0F, false).drawRight(results, sx2 - 10, scy, FAINT);
            }
            if (this.searchFocused && AtlasStyle.caretOn()) {
                Liquid.rect(caret + 1, scy - 4.5F, caret + 2, scy + 4.5F, 0.0F, accent);
            }
        }

        if (hovered) {
            this.hint = "Search modules and settings, names and help, on every page  ·  Enter opens the first";
        } else if (this.hint.isEmpty() && mx >= x && mx <= x + width && my >= y && my <= y + HEADER - 1) {
            this.hint = "Drag here to move the window  ·  Ctrl+R resets it";
        }
    }

    /**
     * The page tabs as one compact segmented control. The selection slides
     * between tabs on a spring and takes each tab's width on the way.
     * Returns the control's right edge.
     */
    private float drawPageTabs(float left, float cy, float mx, float my) {
        String[] names = {"Modules", "Legit", "Client Settings"};
        LiquidFont label = font(8.5F, false);
        float pad = 9.0F;
        float top = cy - 9.0F;
        float bottom = cy + 9.0F;
        float[] lefts = new float[names.length];
        float[] widths = new float[names.length];
        float cursor = left + 2.0F;
        int activeIndex = 0;
        for (int i = 0; i < names.length; i++) {
            widths[i] = label.width(names[i]) + pad * 2.0F;
            lefts[i] = cursor;
            cursor += widths[i];
            if (names[i].equals(this.page)) {
                activeIndex = i;
            }
        }
        float right = cursor + 2.0F;
        Liquid.rect(left, top, right, bottom, 9.0F, ink(0x0E), ink(0x08));
        Liquid.rim(left, top, right, bottom, 9.0F, 1.0F, ink(0x16), ink(0x08));

        /* In the control's own coordinates, so moving the window carries it along. */
        Spring slide = spring("tabSlide", lefts[activeIndex] - left, 420.0F, 30.0F);
        Spring size = spring("tabSize", widths[activeIndex], 420.0F, 30.0F);
        float px = left + slide.value;
        float px2 = px + size.value;
        int accent = accent();
        Liquid.shadow(px, top + 2, px2, bottom - 2, 7.0F, 4.0F, alpha(accent, 0.22F), 0.0F);
        Liquid.rect(px, top + 2, px2, bottom - 2, 7.0F, alpha(accent, 0.32F), alpha(accent, 0.18F));
        Liquid.rim(px, top + 2, px2, bottom - 2, 7.0F, 1.0F, alpha(accent, 0.75F), alpha(accent, 0.30F));

        for (int i = 0; i < names.length; i++) {
            String name = names[i];
            float l = lefts[i];
            float r = l + widths[i];
            boolean active = i == activeIndex;
            boolean hovered = mx >= l && mx <= r && my >= top && my <= bottom;
            hit("page", name, l, top, r, bottom);
            float glow = active ? 0.0F : ease("page:" + name, hovered, 10.0F);
            if (glow > 0.01F) {
                Liquid.rect(l + 1, top + 2, r - 1, bottom - 2, 7.0F,
                        alpha(INK, 0.08F * glow), alpha(INK, 0.035F * glow));
            }
            font(8.5F, active).drawCentred(name, l + widths[i] / 2.0F, cy, active ? TEXT : blend(DIM, TEXT, glow));
            if (hovered) {
                this.hint = "Open " + name;
            }
        }
        return right;
    }

    /**
     * The dark/light switch, right-aligned at {@code right}: a label (when there
     * is room for it) and a switch. Returns its left edge.
     */
    private float drawModeSwitch(float right, float cy, boolean withLabel, float mx, float my) {
        boolean light = this.theme.isLight();
        float switchWidth = 22.0F;
        float switchX = right - switchWidth;
        float left = switchX;
        LiquidFont small = font(8.0F, false);
        String label = light ? "Light" : "Dark";
        if (withLabel) {
            left = switchX - 6 - small.width(label);
        }
        hit("themeMode", null, left - 4, cy - 10, right + 2, cy + 10);
        boolean hovered = mx >= left - 4 && mx <= right + 2 && my >= cy - 10 && my <= cy + 10;
        if (withLabel) {
            small.draw(label, left, cy, hovered ? TEXT : DIM);
        }
        drawSwitch("themeMode", switchX, cy, switchWidth, 12.0F, light);
        if (hovered) {
            this.hint = light ? "Switch the menu and the HUD to dark" : "Switch the menu and the HUD to light";
        }
        return left - 4;
    }

    /**
     * The corner grip, drawn as the conventional diagonal. There is no other
     * affordance this small that anyone reads as "pull me".
     */
    private void drawGrip(int right, int bottom, float mx, float my) {
        boolean hovered = mx >= right - 14 && mx <= right - 2 && my >= bottom - 14 && my <= bottom - 2;
        hit("grip", null, right - 14, bottom - 14, right - 2, bottom - 2);
        int colour = hovered || this.resizingWindow ? accent() : ink(0x40);
        Liquid.line(right - 13, bottom - 5, right - 5, bottom - 13, 1.1F, colour);
        Liquid.line(right - 9, bottom - 5, right - 5, bottom - 9, 1.1F, colour);
        if (hovered) {
            this.hint = "Drag to resize  ·  currently " + this.userWidth + "x" + this.userHeight;
        }
    }

    private void drawSidebar(int x, int y, int height, int sidebar, float mx, float my) {
        List<String> shownCategories = sidebarCategories();
        String sidebarTitle = "Modules".equals(this.page) ? "MODULES"
                : "Legit".equals(this.page) ? "LEGIT" : "CLIENT SETTINGS";
        font(7.0F, true).drawTracked(sidebarTitle, x + 18, y + 16, FAINT, 0.8F);
        float top = y + 26;
        float profilesLabel = top + shownCategories.size() * CAT + 12;
        float profilesRow = profilesLabel + 10;
        float appearanceRow = profilesRow + CAT;
        float serversRow = appearanceRow + CAT;
        boolean settingsPage = "Client Settings".equals(this.page);
        boolean profile = settingsPage && this.theme.accountCard.getValue()
                && y + height - (serversRow + CAT_H) >= 46;
        float limit = y + height - (profile ? 44 : 4);
        /* Module pages: the categories scroll inside [top, listLimit], over a
           small fixed strip at the foot of the column. */
        float listLimit = settingsPage ? limit : y + height - SIDEBAR_FOOT;
        this.sidebarRight = x + sidebar;
        this.sidebarMaxScroll = settingsPage ? 0.0F
                : Math.max(0.0F, shownCategories.size() * CAT - (CAT - CAT_H) - (listLimit - top));
        this.sidebarScroll = Math.max(0.0F, Math.min(this.sidebarMaxScroll, this.sidebarScroll));
        float scrolled = settingsPage ? 0.0F : spring("sidebarScroll", this.sidebarScroll, 300.0F, 30.0F).value;
        boolean profilesFit = settingsPage && profilesRow + CAT_H <= limit;
        boolean appearanceFit = settingsPage && appearanceRow + CAT_H <= limit;
        boolean serversFit = settingsPage && serversRow + CAT_H <= limit;

        int activeIndex = this.profilesView || this.appearanceView || this.serversView ? -1
                : search().isEmpty() ? shownCategories.indexOf(this.category) : -1;
        boolean pillOnProfiles = this.profilesView && profilesFit;
        boolean pillOnAppearance = this.appearanceView && appearanceFit;
        boolean pillOnServers = this.serversView && serversFit;

        /* The selected category sits under a droplet of clear glass. It slides
           between rows on a spring and stretches along its path in proportion
           to its speed, so it reads as liquid being pulled rather than a box
           being moved. Its shadow goes down before the labels, or it would
           darken the very text the glass is showing. */
        /* Sprung in the column's own coordinates, so dragging the window
           carries the pill with it instead of leaving it to catch up. */
        Spring pill = spring("catPill", pillOnProfiles ? profilesRow - top
                        : pillOnAppearance ? appearanceRow - top
                        : pillOnServers ? serversRow - top : Math.max(0, activeIndex) * CAT - scrolled,
                380.0F, 26.0F);
        float shownPill = ease("catPillShown", activeIndex >= 0 || pillOnProfiles || pillOnAppearance || pillOnServers,
                12.0F);
        if (shownPill > 0.02F) {
            float stretch = Math.min(10.0F, Math.abs(pill.velocity) * this.jelly * 0.018F);
            float px = x + 10 + stretch * 0.25F;
            float px2 = x + sidebar - 10 - stretch * 0.25F;
            float py = top + pill.value - stretch / 2.0F;
            float py2 = top + pill.value + CAT_H + stretch / 2.0F;
            Liquid.shadow(px, py, px2, py2, 11.0F, 6.0F, alpha(0x40000000, shownPill), 2.0F);
            this.lenses.add(new Lens(px, py, px2, py2, 11.0F,
                    pillStyle(shownPill), settingsPage ? null : new float[]{x, top - 2, sidebar, listLimit - top + 2}));
        }

        ScaledResolution sr = new ScaledResolution(mc);
        if (!settingsPage) {
            clip(sr, x, top - 2, sidebar, listLimit - top + 2);
        }
        for (int i = 0; i < shownCategories.size(); i++) {
            String name = shownCategories.get(i);
            float rowY = top + i * CAT - scrolled;
            if (settingsPage && rowY + CAT_H > limit) {
                break;
            }
            if (!settingsPage && (rowY + CAT_H < top || rowY > listLimit)) {
                continue;
            }
            boolean active = i == activeIndex;
            boolean hovered = mx >= x + 10 && mx <= x + sidebar - 10 && my >= Math.max(rowY, top)
                    && my <= Math.min(rowY + CAT_H, listLimit);
            hit("category", name, x + 10, Math.max(rowY, top), x + sidebar - 10, Math.min(rowY + CAT_H, listLimit));
            float rowLit = ease("cat:" + name, hovered && !active, 10.0F);
            if (rowLit > 0.01F) {
                Liquid.rect(x + 10, rowY, x + sidebar - 10, rowY + CAT_H, 11.0F,
                        alpha(ink(0x12), rowLit), alpha(ink(0x0A), rowLit));
            }
            float cy = rowY + CAT_H / 2.0F;
            icon(sidebarIcon(name), x + 25, cy, active ? TEXT : DIM);
            font(9.5F, active).draw(font(9.5F, active).trim(name, sidebar - 60), x + 36, cy, active ? TEXT : DIM);
            List<Module> list = modulesOf(name);
            int on = 0;
            for (Module module : list) {
                if (module != null && module.isEnabled()) {
                    on++;
                }
            }
            boolean attention = ATTENTION.equals(name);
            int count = attention ? list.size() : on;
            if (count > 0 && (attention || this.theme.counts.getValue())) {
                font(8.0F, attention).drawRight(String.valueOf(count), x + sidebar - 16, cy,
                        attention ? AtlasStyle.warn : active ? alpha(TEXT, 0.8F) : FAINT);
            }
            if (hovered) {
                this.hint = ENABLED.equals(name) ? "Every module that is on, on this page"
                        : attention ? "Named in this session's flags, or on with a setting at a risky value"
                                + (flagDetectorOn() ? "" : "  ·  FlagDetector is off, so flags are not counted")
                        : on + " of " + list.size() + " enabled in " + name;
            }
            if (i == 1 && attention && shownCategories.size() > 2) {
                /* The two lists above, the categories below. */
                Liquid.rect(x + 18, rowY + CAT - 1.0F, x + sidebar - 18, rowY + CAT - 0.5F, 0.0F, ink(0x14));
            }
        }
        if (!settingsPage) {
            unclip();
            /* More above or below: a small chevron at that edge. */
            if (scrolled > 1.0F) {
                icon("chevronup", x + sidebar / 2.0F, top + 3, FAINT);
            }
            if (scrolled < this.sidebarMaxScroll - 1.0F) {
                icon("chevron", x + sidebar / 2.0F, listLimit - 3, FAINT);
            }
        }

        if (profilesFit) {
            font(7.0F, true).drawTracked("CLIENT", x + 18, profilesLabel, FAINT, 0.8F);
            boolean hovered = mx >= x + 10 && mx <= x + sidebar - 10 && my >= profilesRow
                    && my <= profilesRow + CAT_H;
            hit("profiles", null, x + 10, profilesRow, x + sidebar - 10, profilesRow + CAT_H);
            float rowLit = ease("cat:\u0000profiles", hovered && !this.profilesView, 10.0F);
            if (rowLit > 0.01F) {
                Liquid.rect(x + 10, profilesRow, x + sidebar - 10, profilesRow + CAT_H, 11.0F,
                        alpha(ink(0x12), rowLit), alpha(ink(0x0A), rowLit));
            }
            float cy = profilesRow + CAT_H / 2.0F;
            icon("folder", x + 25, cy, this.profilesView ? TEXT : DIM);
            font(9.5F, this.profilesView).draw("Profiles", x + 36, cy, this.profilesView ? TEXT : DIM);
            font(8.0F, false).drawRight(String.valueOf(profiles().size()), x + sidebar - 16, cy,
                    this.profilesView ? alpha(TEXT, 0.8F) : FAINT);
            if (hovered) {
                this.hint = "Saved profiles  ·  using " + AtlasProfiles.current();
            }
        }
        if (appearanceFit) {
            boolean hovered = mx >= x + 10 && mx <= x + sidebar - 10 && my >= appearanceRow
                    && my <= appearanceRow + CAT_H;
            hit("appearance", null, x + 10, appearanceRow, x + sidebar - 10, appearanceRow + CAT_H);
            float rowLit = ease("cat:#appearance", hovered && !this.appearanceView, 10.0F);
            if (rowLit > 0.01F) {
                Liquid.rect(x + 10, appearanceRow, x + sidebar - 10, appearanceRow + CAT_H, 11.0F,
                        alpha(ink(0x12), rowLit), alpha(ink(0x0A), rowLit));
            }
            float cy = appearanceRow + CAT_H / 2.0F;
            icon("palette", x + 25, cy, this.appearanceView ? TEXT : DIM);
            font(9.5F, this.appearanceView).draw("Appearance", x + 36, cy, this.appearanceView ? TEXT : DIM);
            if (hovered) {
                this.hint = "Colours, glass, motion, text and layout of this menu";
            }
        }
        if (serversFit) {
            boolean hovered = mx >= x + 10 && mx <= x + sidebar - 10 && my >= serversRow && my <= serversRow + CAT_H;
            hit("servers", null, x + 10, serversRow, x + sidebar - 10, serversRow + CAT_H);
            float rowLit = ease("cat:#servers", hovered && !this.serversView, 10.0F);
            if (rowLit > 0.01F) {
                Liquid.rect(x + 10, serversRow, x + sidebar - 10, serversRow + CAT_H, 11.0F,
                        alpha(ink(0x12), rowLit), alpha(ink(0x0A), rowLit));
            }
            float cy = serversRow + CAT_H / 2.0F;
            icon("world", x + 25, cy, this.serversView ? TEXT : DIM);
            font(9.5F, this.serversView).draw("Servers", x + 36, cy, this.serversView ? TEXT : DIM);
            if (hovered) {
                this.hint = "Which profile loads when you join which server";
            }
        }

        if (profile) {
            drawProfile(x, y + height - 40, sidebar);
        }
    }

    /** The profile list, re-read from disk at most twice a second. */
    private List<AtlasProfiles.Info> profiles() {
        /* Listing the folder means touching every file in it -- there are over
           a hundred logs beside the profiles -- so the sidebar's count is
           refreshed every ten seconds, and only the open page every second.
           Saving or deleting from here refreshes at once anyway. */
        long now = System.currentTimeMillis();
        if (now - this.profilesReadAt > (this.profilesView ? 1000L : 10000L)) {
            this.profiles = AtlasProfiles.list();
            this.profilesReadAt = now;
        }
        return this.profiles;
    }

    private AtlasProfiles.Info profileNamed(String name) {
        for (AtlasProfiles.Info info : profiles()) {
            if (info.name.equals(name)) {
                return info;
            }
        }
        return null;
    }

    /**
     * The middle column in the profiles view: a field to save the current
     * setup under a new name, and every saved profile below it.
     */
    private void drawProfileList(ScaledResolution sr, int x, int y, int width, int height, float mx, float my) {
        List<AtlasProfiles.Info> list = profiles();
        String current = AtlasProfiles.current();
        font(15.0F, true).draw("Profiles", x + 16, y + 17, TEXT);
        font(8.0F, false).draw(list.size() + (list.size() == 1 ? " saved" : " saved") + "  ·  using " + current,
                x + 16, y + 31, FAINT);
        int accent = accent();

        /* New profile: type a name, press Enter or Save. */
        float fy = y + 42;
        float fy2 = fy + 22;
        float fcy = fy + 11;
        float buttonX = x + width - 10 - 54;
        float focus = ease("profileNameFocus", this.profileNameFocused, 12.0F);
        hit("profileName", null, x + 10, fy, buttonX - 6, fy2);
        if (focus > 0.01F) {
            Liquid.shadow(x + 10, fy, buttonX - 6, fy2, 11.0F, 6.0F, alpha(accent, 0.30F * focus), 0.0F);
        }
        Liquid.rect(x + 10, fy, buttonX - 6, fy2, 11.0F, alpha(INK, 0.07F + 0.03F * focus), ink(0x0B));
        Liquid.rim(x + 10, fy, buttonX - 6, fy2, 11.0F, 1.0F, blend(ink(0x2E), alpha(accent, 0.8F), focus),
                ink(0x0A));
        LiquidFont body = font(9.0F, false);
        float caret = x + 20;
        if (this.profileNameInput.isEmpty()) {
            if (!this.profileNameFocused) {
                body.draw("New profile name", x + 20, fcy, FAINT);
            }
            this.profileNameInput.drawnAt(x + 20, 0, body::width);
        } else {
            drawField(this.profileNameInput, body, layoutField(this.profileNameInput, body, buttonX - x - 40, false),
                    x + 20, fcy, TEXT, false);
            caret = this.fieldCaretX;
        }
        if (this.profileNameFocused && AtlasStyle.caretOn()) {
            Liquid.rect(caret + 1, fcy - 4.5F, caret + 2, fcy + 4.5F, 0.0F, accent);
        }
        if (mx >= x + 10 && mx <= buttonX - 6 && my >= fy && my <= fy2) {
            this.hint = "Name a profile for everything as it is set now  ·  Enter saves it";
        }
        drawButton("profileCreate", null, buttonX, fcy, 54.0F, "Save", 0, mx, my);

        float top = y + 72;
        float bottom = y + height - 4;
        int maxRows = Math.max(1, (int) ((bottom - top) / ROW));
        this.profileMaxScroll = Math.max(0, list.size() - maxRows);
        if (this.profileScroll > this.profileMaxScroll) {
            this.profileScroll = this.profileMaxScroll;
        }
        if (list.isEmpty()) {
            font(9.0F, false).draw("No profiles yet", x + 16, top + 12, FAINT);
            return;
        }
        float shown = scrollEase("profileScroll", this.profileScroll * (float) ROW);
        clip(sr, x, top - 2, width, bottom - top + 2);

        int selectedIndex = -1;
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).name.equals(this.profileSelected)) {
                selectedIndex = i;
            }
        }
        if (selectedIndex >= 0) {
            Spring pill = spring("profilePill", selectedIndex * ROW, 380.0F, 26.0F);
            float stretch = Math.min(8.0F, Math.abs(pill.velocity) * this.jelly * 0.015F);
            float py = top + pill.value - shown - stretch / 2.0F;
            float py2 = top + pill.value - shown + ROW_H + stretch / 2.0F;
            float px = x + 10 - stretch * 0.3F;
            float px2 = x + width - 10 + stretch * 0.3F;
            if (py2 > top && py < bottom) {
                Liquid.shadow(px, py, px2, py2, 9.0F, 5.0F, 0x33000000, 2.0F);
                this.lenses.add(new Lens(px, py, px2, py2, 9.0F, pillStyle(1.0F),
                        new float[]{x, top - 2, width, bottom - top + 2}));
            }
        }

        for (int i = 0; i < list.size(); i++) {
            AtlasProfiles.Info info = list.get(i);
            float rowY = top + i * ROW - shown;
            if (rowY + ROW_H < top || rowY > bottom) {
                continue;
            }
            float cy = rowY + ROW_H / 2.0F;
            boolean inUse = info.name.equalsIgnoreCase(current);
            boolean hovered = mx >= x + 10 && mx <= x + width - 10 && my >= rowY && my <= rowY + ROW_H
                    && my >= top && my <= bottom;
            float clippedTop = Math.max(rowY, top);
            float clippedBottom = Math.min(rowY + ROW_H, bottom);
            if (clippedBottom - clippedTop > 4.0F) {
                hit("profile", info.name, x + 10, clippedTop, x + width - 10, clippedBottom);
            }
            float hoverLit = ease("prow:" + info.name, hovered && !info.name.equals(this.profileSelected), 9.0F);
            if (hoverLit > 0.01F) {
                Liquid.rect(x + 10, rowY, x + width - 10, rowY + ROW_H, 9.0F,
                        alpha(ink(0x10), hoverLit), alpha(ink(0x0A), hoverLit));
            }
            float saved = pulseAt("saved:" + info.name, 600L);
            if (saved >= 0.0F) {
                Liquid.rect(x + 10, rowY, x + width - 10, rowY + ROW_H, 9.0F,
                        alpha(accent, 0.30F * (1.0F - saved)));
            }
            icon("file", x + 21, cy, inUse ? accent : DIM);
            LiquidFont name = font(9.5F, inUse);
            float nameWidth = name.draw(name.trim(info.name, width - 150), x + 30, cy, inUse ? TEXT : DIM);
            if (inUse) {
                LiquidFont tag = font(7.0F, true);
                float tagX = x + 30 + nameWidth + 8;
                float tagWidth = tag.width("IN USE") + 10;
                Liquid.rect(tagX, cy - 5.5F, tagX + tagWidth, cy + 5.5F, 5.5F, alpha(accent, 0.22F));
                tag.draw("IN USE", tagX + 5, cy, accent);
                if (profileUnsaved()) {
                    float unsavedX = tagX + tagWidth + 4;
                    float unsavedWidth = tag.width("UNSAVED") + 10;
                    Liquid.rect(unsavedX, cy - 5.5F, unsavedX + unsavedWidth, cy + 5.5F, 5.5F,
                            alpha(AtlasStyle.warn, 0.22F));
                    tag.draw("UNSAVED", unsavedX + 5, cy, AtlasStyle.warn);
                }
            }
            LiquidFont small = font(8.0F, false);
            small.drawRight(info.enabled.size() + " on  ·  " + AtlasProfiles.age(info.modified),
                    x + width - 18, cy, FAINT);
            if (hovered) {
                this.hint = info.name + "  ·  " + info.enabled.size() + " modules on  ·  click for details";
            }
        }
        unclip();
    }

    /**
     * The right column in the profiles view: what the selected profile turns
     * on, and what can be done with it.
     */
    private void drawProfileDetail(ScaledResolution sr, int x, int y, int width, int height, float mx, float my) {
        AtlasProfiles.Info info = this.profileSelected == null ? null : profileNamed(this.profileSelected);
        LiquidFont body = font(8.5F, false);
        if (info == null) {
            font(14.0F, true).draw("Select a profile", x + 16, y + 17, TEXT);
            float cursor = y + 34;
            for (String line : body.wrap("Profiles are the same files .config save writes, in config/Atlas. "
                    + "Load one to switch every module and setting at once.", width - 32)) {
                body.draw(line, x + 16, cursor, DIM);
                cursor += 11;
            }
            cursor += 8;
            drawButton("profileFolder", null, x + 16, cursor, 0.0F, "Open folder", 1, mx, my);
            return;
        }
        float arrived = toward("profileArrive", 1.0F, 11.0F);
        float lift = (1.0F - arrived) * 6.0F;
        boolean inUse = info.name.equalsIgnoreCase(AtlasProfiles.current());
        LiquidFont title = font(14.0F, true);
        title.draw(title.trim(info.name, width - 32), x + 16, y + 17 + lift, alpha(TEXT, arrived));
        float cursor = y + 33 + lift;
        String saved = "Saved " + AtlasProfiles.age(info.modified) + "  ·  "
                + new java.text.SimpleDateFormat("MMM d, HH:mm").format(new java.util.Date(info.modified));
        font(8.0F, false).draw(saved, x + 16, cursor, alpha(FAINT, arrived));
        cursor += 12;
        if (inUse) {
            /* 2026-10-23: this said changes stay in memory until saved here; the
               game saves them to the profile in use when it quits. */
            for (String line : body.wrap("In use. Your changes are saved to it when you quit, "
                    + "or now with Save here.", width - 32)) {
                body.draw(line, x + 16, cursor, alpha(DIM, arrived));
                cursor += 11;
            }
            if (profileUnsaved()) {
                for (String line : body.wrap("Changed since it was last saved.", width - 32)) {
                    body.draw(line, x + 16, cursor, alpha(AtlasStyle.warn, arrived));
                    cursor += 11;
                }
            }
        }
        cursor += 12;

        /* Load, save over it, delete. Delete asks first; Load asks when there are unsaved changes. */
        float bx = x + 16;
        bx += drawButton("profileLoad", info.name, bx, cursor, 0.0F, inUse ? "Reload" : "Load", 0, mx, my) + 6;
        bx += drawButton("profileSave", info.name, bx, cursor, 0.0F, "Save here", 1, mx, my) + 6;
        if (!"default".equalsIgnoreCase(info.name)) {
            drawButton("profileDelete", info.name, bx, cursor, 0.0F, "Delete", 2, mx, my);
        }
        cursor += 22;

        font(7.0F, true).drawTracked("ENABLED MODULES  ·  " + info.enabled.size(), x + 18, cursor, FAINT, 0.8F);
        cursor += 8;
        float cardX = x + 12;
        float cardX2 = x + width - 12;
        float cardBottom = y + height - 32;
        if (cardBottom - cursor < 24) {
            return;
        }
        StringBuilder names = new StringBuilder();
        for (String module : info.enabled) {
            if (names.length() > 0) {
                names.append("  ·  ");
            }
            names.append(module);
        }
        List<String> lines = body.wrap(names.length() == 0 ? "Nothing enabled" : names.toString(), cardX2 - cardX - 20);
        float needed = lines.size() * 11 + 12;
        cardBottom = Math.min(cardBottom, cursor + needed);
        Liquid.rect(cardX, cursor, cardX2, cardBottom, 12.0F, ink(0x0E), ink(0x09));
        Liquid.rim(cardX, cursor, cardX2, cardBottom, 12.0F, 1.0F, ink(0x24), ink(0x0A));
        clip(sr, cardX, cursor + 1, cardX2 - cardX, cardBottom - cursor - 2);
        float lineY = cursor + 11;
        for (String line : lines) {
            body.draw(line, cardX + 10, lineY, names.length() == 0 ? FAINT : DIM);
            lineY += 11;
        }
        unclip();
        drawButton("profileFolder", null, x + 16, y + height - 16, 0.0F, "Open folder", 1, mx, my);
    }

    // ---- servers page ----------------------------------------------------

    private static atlas.module.modules.ServerProfiles serverProfiles() {
        return (atlas.module.modules.ServerProfiles) Atlas.moduleManager.modules.get(
                atlas.module.modules.ServerProfiles.class);
    }

    /** The server's host: the address without its port, lower case; null in single player. */
    private static String currentHost() {
        String address = atlas.module.modules.ServerProfiles.currentAddress();
        if (address == null) {
            return null;
        }
        int colon = address.indexOf(':');
        return colon > 0 ? address.substring(0, colon) : address;
    }

    /**
     * The middle column of the Servers page: ServerProfiles on or off, and its
     * rules, first match wins, as the module reads them.
     */
    private void drawServersList(int x, int y, int width, float mx, float my) {
        atlas.module.modules.ServerProfiles profiles = serverProfiles();
        font(15.0F, true).draw("Servers", x + 16, y + 17, TEXT);
        font(8.0F, false).draw("Load a profile when you join a server  ·  first match wins", x + 16, y + 31, FAINT);
        if (profiles == null) {
            return;
        }
        float cy = y + 54;
        boolean on = profiles.isEnabled();
        hit("autoConfig", null, x + 10, cy - 11, x + width - 10, cy + 11);
        boolean hovered = mx >= x + 10 && mx <= x + width - 10 && my >= cy - 11 && my <= cy + 11;
        float lit = ease("autoConfigHover", hovered, 12.0F);
        Liquid.rect(x + 10, cy - 11, x + width - 10, cy + 11, 9.0F, alpha(ink(0x10), 0.6F + 0.4F * lit));
        font(9.5F, false).draw("Auto load config", x + 20, cy, TEXT);
        drawSwitch("autoConfig", x + width - 18 - 22, cy, 22.0F, 12.0F, on);
        if (hovered) {
            this.hint = on ? "ServerProfiles is on: joining a server loads its profile  ·  click to turn it off"
                    : "ServerProfiles is off: nothing is loaded on join  ·  click to turn it on";
        }

        List<String[]> rules = atlas.module.modules.ServerProfiles.parse(profiles.rules.getValue());
        float top = y + 80;
        font(7.0F, true).drawTracked("RULES  ·  " + rules.size(), x + 18, top, FAINT, 0.8F);
        top += 10;
        if (rules.isEmpty()) {
            font(9.0F, false).draw("No rules yet: join a server and use the button on the right", x + 16, top + 12,
                    FAINT);
            return;
        }
        LiquidFont name = font(9.5F, false);
        LiquidFont small = font(8.0F, false);
        float limit = y + 400;
        for (int i = 0; i < rules.size(); i++) {
            String[] rule = rules.get(i);
            float rowY = top + i * ROW;
            float rowCy = rowY + ROW_H / 2.0F;
            boolean exists = new java.io.File(AtlasProfiles.DIR, rule[1] + ".json").exists();
            boolean rowHovered = mx >= x + 10 && mx <= x + width - 10 && my >= rowY && my <= rowY + ROW_H;
            if (ease("rule:" + i, rowHovered, 9.0F) > 0.01F) {
                Liquid.rect(x + 10, rowY, x + width - 10, rowY + ROW_H, 9.0F, ink(0x10));
            }
            float drawn = name.draw(name.trim(rule[0], width * 0.45F), x + 20, rowCy, TEXT);
            String target = "loads " + rule[1] + (exists ? "" : "  (no such profile)");
            small.draw(small.trim(target, width - drawn - 80), x + 28 + drawn, rowCy, exists ? DIM : AtlasStyle.warn);
            float bx = x + width - 18 - 10;
            boolean overDelete = mx >= bx - 7 && mx <= bx + 7 && my >= rowCy - 7 && my <= rowCy + 7;
            hit("ruleDelete", Integer.valueOf(i), bx - 7, rowCy - 7, bx + 7, rowCy + 7);
            int c = overDelete ? AtlasStyle.danger : FAINT;
            Liquid.line(bx - 3, rowCy - 3, bx + 3, rowCy + 3, 1.1F, c);
            Liquid.line(bx - 3, rowCy + 3, bx + 3, rowCy - 3, 1.1F, c);
            if (overDelete) {
                this.hint = "Remove the rule " + rule[0] + " = " + rule[1];
            } else if (rowHovered) {
                this.hint = "An address containing \"" + rule[0] + "\" loads " + rule[1]
                        + (exists ? "" : "  ·  no profile has that name, so it is skipped");
            }
            if (rowY + ROW > limit) {
                break;
            }
        }
    }

    /** The right column: the server played on now, the profile in use, and a rule for this server. */
    private void drawServersDetail(int x, int y, int width, float mx, float my) {
        String host = currentHost();
        font(14.0F, true).draw("This server", x + 16, y + 17, TEXT);
        LiquidFont body = font(8.5F, false);
        float cursor = y + 34;
        body.draw(body.trim(host == null ? "Single player" : host, width - 32), x + 16, cursor, DIM);
        cursor += 13;
        String current = AtlasProfiles.current();
        body.draw(body.trim("Profile in use: " + current, width - 32), x + 16, cursor, DIM);
        cursor += 13;
        Module fingerprint = Atlas.moduleManager.modules.get(atlas.module.modules.ServerFingerprint.class);
        if (fingerprint != null && fingerprint.isEnabled() && host != null) {
            String[] suffix = fingerprint.getSuffix();
            String verdict = suffix == null || suffix.length == 0 ? "" : suffix[0];
            body.draw(body.trim("ServerFingerprint: " + verdict.replaceAll("[&§][0-9a-fklmnor]", ""), width - 32),
                    x + 16, cursor, DIM);
            cursor += 13;
        }
        cursor += 8;
        if (host == null) {
            for (String line : body.wrap("Join a server to give it a profile.", width - 32)) {
                body.draw(line, x + 16, cursor, FAINT);
                cursor += 11;
            }
            return;
        }
        atlas.module.modules.ServerProfiles profiles = serverProfiles();
        List<String[]> rules = profiles == null ? new ArrayList<String[]>()
                : atlas.module.modules.ServerProfiles.parse(profiles.rules.getValue());
        String matched = atlas.module.modules.ServerProfiles.match(rules,
                atlas.module.modules.ServerProfiles.currentAddress());
        if (matched != null) {
            for (String line : body.wrap("A rule loads " + matched + " here.", width - 32)) {
                body.draw(line, x + 16, cursor, DIM);
                cursor += 11;
            }
            cursor += 6;
        }
        cursor += 4;
        drawButton("ruleHere", null, x + 16, cursor, 0.0F,
                font(8.5F, true).trim("Use " + current + " here", width - 60), 0, mx, my);
        if (mx >= x + 16 && mx <= x + width - 16 && my >= cursor - 10 && my <= cursor + 10) {
            this.hint = "Load " + current + " whenever you join " + host;
        }
        cursor += 22;
        for (String line : body.wrap("Before a rule switches profile, your changes are saved to the profile in use.",
                width - 32)) {
            body.draw(line, x + 16, cursor, FAINT);
            cursor += 11;
        }
    }

    /** A rule for this server's host to the profile in use, replacing one for the same host. */
    private void useProfileHere() {
        atlas.module.modules.ServerProfiles profiles = serverProfiles();
        String host = currentHost();
        if (profiles == null || host == null) {
            return;
        }
        String current = AtlasProfiles.current();
        List<String[]> rules = atlas.module.modules.ServerProfiles.parse(profiles.rules.getValue());
        boolean replaced = false;
        for (String[] rule : rules) {
            if (rule[0].equals(host)) {
                rule[1] = current;
                replaced = true;
            }
        }
        if (!replaced) {
            rules.add(new String[]{host, current});
        }
        profiles.rules.setValue(atlas.module.modules.ServerProfiles.format(rules));
        pulse("btn:ruleHere");
        tell(Toasts.Kind.OK, host + " now loads " + current
                + (profiles.isEnabled() ? "" : "  ·  turn on Auto load config to use it"));
    }

    /**
     * Pushes the theme into everything that reads it, once a frame and before
     * any of it is drawn: the blur, the typeface, the springs and the layout.
     */
    private void applyTheme() {
        Liquid.blurLevels = this.theme.blurLevels();
        Liquid.blurOffset = this.theme.blurOffset();
        LiquidFont.faceFile = this.theme.fontFile();
        boolean animated = this.theme.animations.getValue();
        Spring.dampingScale = animated ? this.theme.damping() : 1.0F;
        this.springSpeed = animated ? this.theme.springSpeed() : 1.0F;
        this.ui.springSpeed = this.springSpeed;
        this.ui.animations = animated;
        this.ui.smoothScroll = this.theme.smoothScroll.getValue();
        this.jelly = animated ? this.theme.jelly() : 0.0F;
        /* In steps of five percent, and the old sheets dropped on a change:
           every distinct size is a set of glyph sheets, and dragging the
           slider would otherwise leave hundreds of them behind. */
        float scale = Math.round(this.theme.textSize.getValue() / 5.0F) * 0.05F;
        if (scale != textScale) {
            textScale = scale;
            LiquidFont.clear();
        }
        boolean compact = this.theme.compact();
        this.ROW_H = compact ? 20 : 24;
        this.ROW = compact ? 22 : 26;
        this.CAT_H = compact ? 22 : 26;
        this.CAT = compact ? 24 : 28;
        this.RADIUS = this.theme.radius.getValue();
        this.FOOTER = this.theme.hintBar.getValue() ? 20 : 8;
    }

    /**
     * The middle column of the Appearance page: presets, then the groups of
     * settings, each with a one-line summary of where it stands.
     */
    private void drawAppearanceList(ScaledResolution sr, int x, int y, int width, int height, float mx, float my) {
        font(15.0F, true).draw("Appearance", x + 16, y + 17, TEXT);
        font(8.0F, false).draw("Applies as you change it  ·  saved automatically", x + 16, y + 31, FAINT);

        float cursor = y + 50;
        font(7.0F, true).drawTracked("PRESETS", x + 18, cursor, FAINT, 0.8F);
        cursor += 15;
        LiquidFont buttonFont = font(8.5F, true);
        float bx = x + 16;
        for (String preset : AtlasTheme.PRESETS) {
            float w = buttonFont.width(preset) + 22;
            if (bx + w > x + width - 12) {
                bx = x + 16;
                cursor += 24;
            }
            drawButton("preset", preset, bx, cursor, 0.0F, preset, 1, mx, my);
            bx += w + 6;
        }
        float resetWidth = buttonFont.width("Reset") + 22;
        if (bx + resetWidth > x + width - 12) {
            bx = x + 16;
            cursor += 24;
        }
        drawButton("themeReset", null, bx, cursor, 0.0F, "Reset", 2, mx, my);
        cursor += 24;

        font(7.0F, true).drawTracked("SETTINGS", x + 18, cursor, FAINT, 0.8F);
        float top = cursor + 8;
        float bottom = y + height - 4;
        List<String> groups = new ArrayList<String>(this.theme.groups.keySet());
        int selectedIndex = groups.indexOf(this.appearanceGroup);
        this.appearanceMaxScroll = Math.max(0.0F, groups.size() * ROW - (ROW - ROW_H) - (bottom - top));
        this.appearanceScroll = Math.max(0.0F, Math.min(this.appearanceMaxScroll, this.appearanceScroll));
        float scrolled = spring("appearanceScroll", this.appearanceScroll, 300.0F, 30.0F).value;
        clip(sr, x, top - 2, width, bottom - top + 2);
        if (selectedIndex >= 0) {
            Spring pill = spring("groupPill", selectedIndex * ROW - scrolled, 380.0F, 26.0F);
            float stretch = Math.min(8.0F, Math.abs(pill.velocity) * this.jelly * 0.015F);
            float py = top + pill.value - stretch / 2.0F;
            float py2 = top + pill.value + ROW_H + stretch / 2.0F;
            Liquid.shadow(x + 10, py, x + width - 10, py2, 9.0F, 5.0F, 0x33000000, 2.0F);
            this.lenses.add(new Lens(x + 10 - stretch * 0.3F, py, x + width - 10 + stretch * 0.3F, py2, 9.0F,
                    pillStyle(1.0F), new float[]{x, top - 2, width, bottom - top + 2}));
        }
        for (int i = 0; i < groups.size(); i++) {
            String group = groups.get(i);
            float rowY = top + i * ROW - scrolled;
            if (rowY > bottom) {
                break;
            }
            if (rowY + ROW_H < top) {
                continue;
            }
            float cy = rowY + ROW_H / 2.0F;
            boolean active = group.equals(this.appearanceGroup);
            boolean hovered = mx >= x + 10 && mx <= x + width - 10 && my >= Math.max(rowY, top)
                    && my <= rowY + ROW_H && my <= bottom;
            hit("group", group, x + 10, Math.max(rowY, top), x + width - 10, Math.min(rowY + ROW_H, bottom));
            float hoverLit = ease("grp:" + group, hovered && !active, 9.0F);
            if (hoverLit > 0.01F) {
                Liquid.rect(x + 10, rowY, x + width - 10, rowY + ROW_H, 9.0F,
                        alpha(ink(0x10), hoverLit), alpha(ink(0x0A), hoverLit));
            }
            icon(group, x + 22, cy, active ? TEXT : DIM);
            font(9.5F, active).draw(group, x + 34, cy, active ? TEXT : DIM);
            LiquidFont small = font(8.0F, false);
            small.drawRight(small.trim(this.theme.summary(group), width - 120), x + width - 18, cy, FAINT);
            if (hovered) {
                this.hint = this.theme.descriptions.get(group);
            }
        }
        unclip();
        if (scrolled > 1.0F) {
            icon("chevronup", x + width / 2.0F, top + 3, FAINT);
        }
        if (scrolled < this.appearanceMaxScroll - 1.0F) {
            icon("chevron", x + width / 2.0F, bottom - 3, FAINT);
        }
    }

    /** The right column of the Appearance page: the open group's settings. */
    private void drawAppearanceDetail(ScaledResolution sr, int x, int y, int width, int height, float mx, float my) {
        List<Property<?>> all = this.theme.groups.get(this.appearanceGroup);
        if (all == null) {
            this.appearanceGroup = "Colors";
            all = this.theme.groups.get(this.appearanceGroup);
        }
        float arrived = toward("groupArrive", 1.0F, 11.0F);
        float lift = (1.0F - arrived) * 6.0F;
        font(14.0F, true).draw(this.appearanceGroup, x + 16, y + 17 + lift, alpha(TEXT, arrived));
        float cursor = y + 34 + lift;
        LiquidFont body = font(8.5F, false);
        List<String> lines = body.wrap(this.theme.descriptions.get(this.appearanceGroup), width - 32);
        for (int i = 0; i < Math.min(4, lines.size()); i++) {
            body.draw(lines.get(i), x + 16, cursor, alpha(DIM, arrived));
            cursor += 11;
        }
        cursor += 8;
        List<Property<?>> visible = new ArrayList<Property<?>>();
        for (Property<?> property : all) {
            if (property.isVisible()) {
                visible.add(property);
            }
        }
        drawSettingsCard(sr, "theme", visible, x + 12, x + width - 12, cursor, y + height - 6, mx, my);
    }

    /**
     * A capsule button. Style 0 is the accent, 1 plain glass, 2 destructive.
     * A width of 0 fits the label. Returns the width drawn.
     */
    private float drawButton(String kind, Object payload, float x, float cy, float width, String label,
                             int style, float mx, float my) {
        LiquidFont bold = font(8.5F, true);
        float w = width > 0.0F ? width : bold.width(label) + 22;
        float y = cy - 10;
        float y2 = cy + 10;
        hit(kind, payload, x, y, x + w, y2);
        boolean hovered = mx >= x && mx <= x + w && my >= y && my <= y2;
        float hover = ease("btn:" + kind, hovered, 12.0F);
        float press = pulseAt("btn:" + kind, 260L);
        float squash = press >= 0.0F ? (float) Math.sin(press * Math.PI) * 1.2F : 0.0F;
        int colour = style == 0 ? accent() : style == 2 ? 0xFFFF5A5F : 0xFFFFFFFF;
        float fill = style == 1 ? 0.08F + 0.05F * hover : 0.30F + 0.12F * hover;
        if (style != 1) {
            Liquid.shadow(x, y, x + w, y2, 10.0F, 5.0F, alpha(colour, 0.25F + 0.15F * hover), 0.0F);
        }
        Liquid.rect(x + squash, y + squash * 0.5F, x + w - squash, y2 - squash * 0.5F, 10.0F,
                alpha(colour, fill), alpha(colour, fill * 0.7F));
        Liquid.rim(x + squash, y + squash * 0.5F, x + w - squash, y2 - squash * 0.5F, 10.0F, 1.0F,
                alpha(style == 1 ? 0xFFFFFFFF : colour, style == 1 ? 0.2F : 0.65F), ink(0x0A));
        bold.drawCentred(label, x + w / 2.0F, cy, TEXT);
        if (hovered) {
            if ("profileLoad".equals(kind)) {
                this.hint = "Switch every module and setting to " + payload;
            } else if ("profileSave".equals(kind)) {
                this.hint = "Overwrite " + payload + " with everything as it is set now";
            } else if ("profileDelete".equals(kind)) {
                this.hint = "Delete " + payload + ".json  ·  asks first";
            } else if ("profileFolder".equals(kind)) {
                this.hint = "Open " + AtlasProfiles.DIR.getPath();
            } else if ("profileCreate".equals(kind)) {
                this.hint = "Save everything as it is set now under the typed name";
            } else if ("preset".equals(kind)) {
                this.hint = "Start from the " + payload + " look  ·  every Appearance setting is replaced";
            } else if ("themeReset".equals(kind)) {
                this.hint = "Put every Appearance setting back to how the menu was designed";
            } else if ("infoReset".equals(kind) && payload instanceof Property<?>) {
                Property<?> property = (Property<?>) payload;
                this.hint = "Put " + property.getLabel() + " back to " + SettingFacts.defaultText(property);
            }
        }
        return w;
    }

    /** Who is playing, and where: the account, the server and the ping. */
    private void drawProfile(int x, float y, int sidebar) {
        Liquid.rect(x + 10, y, x + sidebar - 10, y + 28, 11.0F, ink(0x0D), ink(0x08));
        Liquid.rim(x + 10, y, x + sidebar - 10, y + 28, 11.0F, 1.0F, ink(0x1F), ink(0x08));
        String name = mc.getSession() == null ? "Player" : mc.getSession().getUsername();
        float cy = y + 14;
        Liquid.rect(x + 16, cy - 7, x + 30, cy + 7, 7.0F, 0xFF8E7CFF, accent());
        font(8.0F, true).drawCentred(name.isEmpty() ? "?" : name.substring(0, 1).toUpperCase(),
                x + 23, cy, TEXT);
        LiquidFont bold = font(8.5F, true);
        bold.draw(bold.trim(name, sidebar - 50), x + 35, cy - 4.5F, TEXT);
        String where = "Singleplayer";
        if (mc.getCurrentServerData() != null && mc.getCurrentServerData().serverIP != null) {
            where = mc.getCurrentServerData().serverIP;
            int colon = where.indexOf(':');
            if (colon > 0) {
                where = where.substring(0, colon);
            }
            if (where.startsWith("play.")) {
                where = where.substring(5);
            }
        }
        try {
            if (mc.getNetHandler() != null && mc.thePlayer != null) {
                NetworkPlayerInfo info = mc.getNetHandler().getPlayerInfo(mc.thePlayer.getUniqueID());
                if (info != null && info.getResponseTime() > 0) {
                    where = where + "  ·  " + info.getResponseTime() + " ms";
                }
            }
        } catch (Exception ignored) {
            // Mid-transfer; the server name alone will do.
        }
        LiquidFont small = font(7.0F, false);
        small.draw(small.trim(where, sidebar - 50), x + 35, cy + 4.5F, FAINT);
    }

    private void drawList(ScaledResolution sr, int x, int y, int width, int height, float mx, float my) {
        List<Module> modules = visibleModules();
        boolean searching = !search().trim().isEmpty();
        int on = 0;
        for (Module module : modules) {
            if (module.isEnabled()) {
                on++;
            }
        }
        String title = searching ? "Search" : this.category;
        font(15.0F, true).draw(title, x + 16, y + 17, TEXT);
        int elsewhere = 0;
        if (searching) {
            for (Module module : modules) {
                if (!belongsOnPage(module)) {
                    elsewhere++;
                }
            }
        }
        /* Settings the search found follow the modules, under a heading row
           of their own, in the same scroll. */
        List<SearchIndex.Entry> settings = searching ? searchResults().settings
                : java.util.Collections.<SearchIndex.Entry>emptyList();
        int rows = modules.size() + (settings.isEmpty() ? 0 : settings.size() + 1);
        String subtitle = searching
                ? modules.size() + (modules.size() == 1 ? " module" : " modules")
                        + "  ·  " + settings.size() + (settings.size() == 1 ? " setting" : " settings")
                        + (elsewhere > 0 ? "  ·  " + elsewhere + " from another page" : "")
                        + "  ·  " + on + " enabled"
                : ATTENTION.equals(this.category)
                        ? modules.size() + (modules.size() == 1 ? " module" : " modules")
                                + (flagDetectorOn() ? "  ·  flags counted since you joined"
                                        : "  ·  FlagDetector is off: flags are not counted")
                        : modules.size() + " modules  ·  " + on + " enabled";
        font(8.0F, false).draw(subtitle, x + 16, y + 31, FAINT);

        float top = y + 42;
        float bottom = y + height - 4;
        int maxRows = Math.max(1, (int) ((bottom - top) / ROW));
        this.listMaxScroll = Math.max(0, rows - maxRows);
        if (this.listScroll > this.listMaxScroll) {
            this.listScroll = this.listMaxScroll;
        }
        if (rows == 0) {
            font(9.0F, false).draw(searching ? "No module or setting matches \"" + search() + "\""
                    : ATTENTION.equals(this.category) ? "Nothing needs attention"
                    : ENABLED.equals(this.category) ? "Nothing is on" : "No modules here", x + 16, top + 12, FAINT);
            return;
        }

        /* Scrolling moves a pixel offset rather than an index, so a row can be
           part-way past the edge. Clipping is what lets that be true. */
        float shown = scrollEase("listScroll", this.listScroll * (float) ROW);
        clip(sr, x, top - 2, width, bottom - top + 2);

        /* The selected module sits under the same glass as the category, its
           shadow laid down first for the same reason. */
        int selectedIndex = modules.indexOf(this.selected);
        if (selectedIndex >= 0) {
            /* In list coordinates for the same reason, and before the scroll
               offset, so scrolling moves the pill exactly with its row and
               only a change of selection makes it slide. */
            Spring pill = spring("rowPill", selectedIndex * ROW, 380.0F, 26.0F);
            float stretch = Math.min(8.0F, Math.abs(pill.velocity) * this.jelly * 0.015F);
            float px = x + 10 - stretch * 0.3F;
            float px2 = x + width - 10 + stretch * 0.3F;
            float py = top + pill.value - shown - stretch / 2.0F;
            float py2 = top + pill.value - shown + ROW_H + stretch / 2.0F;
            if (py2 > top && py < bottom) {
                Liquid.shadow(px, py, px2, py2, 9.0F, 5.0F, 0x33000000, 2.0F);
                this.lenses.add(new Lens(px, py, px2, py2, 9.0F, pillStyle(1.0F),
                        new float[]{x, top - 2, width, bottom - top + 2}));
            }
        }

        int first = Math.max(0, (int) (shown / ROW) - 1);
        for (int i = 0; i < modules.size(); i++) {
            Module module = modules.get(i);
            if (i < first) {
                continue;
            }
            float rowY = top + i * ROW - shown;
            if (rowY > bottom) {
                continue;
            }
            AtlasInspector.note("list", module.getName(), x + 10, rowY, x + width - 10, rowY + ROW_H);
            boolean hovered = mx >= x + 10 && mx <= x + width - 10 && my >= rowY && my <= rowY + ROW_H
                    && my >= top && my <= bottom;
            /* Only the part of a row inside the clip can be clicked, because
               only that part can be seen. */
            float clippedTop = Math.max(rowY, top);
            float clippedBottom = Math.min(rowY + ROW_H, bottom);
            if (clippedBottom - clippedTop > 4.0F) {
                hit("module", module, x + 10, clippedTop, x + width - 10, clippedBottom);
            }
            boolean isSelected = module == this.selected;
            float hoverLit = ease("row:" + module.getName(), hovered && !isSelected, 9.0F);
            if (hoverLit > 0.01F) {
                Liquid.rect(x + 10, rowY, x + width - 10, rowY + ROW_H, 9.0F,
                        alpha(ink(0x10), hoverLit), alpha(ink(0x0A), hoverLit));
            }
            /* A result from the other page looks borrowed: its own tint, a dashed
               edge, and a tag naming the page it lives on. */
            boolean foreign = searching && !belongsOnPage(module);
            if (foreign) {
                Liquid.rect(x + 10, rowY, x + width - 10, rowY + ROW_H, 9.0F,
                        alpha(foreignColour(), 0.10F), alpha(foreignColour(), 0.05F));
                dashedRim(x + 10, rowY, x + width - 10, rowY + ROW_H, 9.0F, alpha(foreignColour(), 0.75F));
            }
            float cy = rowY + ROW_H / 2.0F;
            int accent = accent();

            /* The state bead is the leftmost thing on the row and the only
               accent-coloured dot there, so "what is on" is answerable at a
               glance. A ring leaves it once on the click that changed it. */
            float onLit = ease("on:" + module.getName(), module.isEnabled(), 12.0F);
            float ring = pulseAt("tog:" + module.getName(), 420L);
            boolean dots = this.theme.stateDots.getValue();
            float nameX = dots ? x + 30 : x + 20;
            if (!dots) {
                ring = -1.0F;
                onLit = 0.0F;
            }
            if (ring >= 0.0F) {
                float expand = smooth(ring) * 7.0F;
                Liquid.ring(x + 20.5F, cy, 2.5F + expand, 1.0F, alpha(accent, 0.6F * (1.0F - ring)));
            }
            if (onLit > 0.01F) {
                Liquid.shadow(x + 18, cy - 2.5F, x + 23, cy + 2.5F, 2.5F, 4.0F, alpha(accent, 0.9F * onLit), 0.0F);
            }
            if (dots) {
                Liquid.dot(x + 20.5F, cy, 2.5F, blend(ink(0x2E), accent, onLit));
            }

            LiquidFont name = font(9.5F, false);
            float nameWidth = name.draw(module.getName(), nameX, cy, module.isEnabled() ? TEXT : DIM);
            if (module.isHidden()) {
                icon("eyeOff", nameX + nameWidth + 10, cy, FAINT);
            }
            float badgeX = nameX + nameWidth + (module.isHidden() ? 20 : 6);
            float badgeEnd = drawHealthBadge(health(module), badgeX, cy);
            float afterName = badgeEnd > badgeX ? badgeEnd + 6
                    : nameX + nameWidth + (module.isHidden() ? 22 : 10);

            float switchX = x + width - 18 - 22;
            /* Only the switch turns a module on or off; the rest of the row
               selects it. Registered after the row, so it wins where they
               overlap, and a little larger than drawn so it is easy to hit. */
            if (clippedBottom - clippedTop > 4.0F) {
                hit("rowSwitch", module, switchX - 4, Math.max(cy - 9, top), switchX + 26, Math.min(cy + 9, bottom));
            }
            drawSwitch("row:" + module.getName(), switchX, cy, 22.0F, 12.0F, module.isEnabled());
            if (mx >= switchX - 4 && mx <= switchX + 26 && my >= cy - 9 && my <= cy + 9) {
                this.hint = (module.isEnabled() ? "Turn off " : "Turn on ") + module.getName();
            }
            float tagRight = switchX - 8;
            if (foreign) {
                String source = pageOf(module);
                LiquidFont tag = font(7.0F, true);
                float tagLeft = tagRight - tag.width(source) - 10;
                Liquid.rect(tagLeft, cy - 5.5F, tagRight, cy + 5.5F, 5.5F,
                        alpha(foreignColour(), 0.28F), alpha(foreignColour(), 0.18F));
                tag.drawCentred(source, (tagLeft + tagRight) / 2.0F, cy, foreignText());
                tagRight = tagLeft - 6;
            }
            String suffix = this.theme.moduleStatus.getValue() ? suffixOf(module) : "";
            if (!suffix.isEmpty()) {
                LiquidFont small = font(8.0F, false);
                float room = tagRight - afterName;
                if (room > 20) {
                    small.drawRight(small.trim(suffix, room), tagRight, cy, FAINT);
                }
            }
            if (hovered && foreign && this.hint.isEmpty()) {
                this.hint = "From the " + pageOf(module) + " page  ·  its settings open here";
            }
            if (hovered && this.hint.isEmpty()) {
                this.hint = module.getDescription() == null || module.getDescription().isEmpty()
                        ? "Click to show the settings  ·  the switch turns it on or off"
                        : module.getDescription();
            }
        }
        if (!settings.isEmpty()) {
            float headingY = top + modules.size() * ROW - shown;
            if (headingY + ROW_H >= top && headingY <= bottom) {
                font(7.0F, true).drawTracked("SETTINGS  ·  " + settings.size(), x + 18, headingY + ROW_H / 2.0F + 2,
                        FAINT, 0.8F);
            }
            for (int s = 0; s < settings.size(); s++) {
                int i = modules.size() + 1 + s;
                if (i < first) {
                    continue;
                }
                float rowY = top + i * ROW - shown;
                if (rowY > bottom) {
                    break;
                }
                drawSettingResult(settings.get(s), x, rowY, width, top, bottom, mx, my);
            }
        }
        unclip();

        /* A thin capsule on the right edge, only while there is more. */
        if (this.listMaxScroll > 0) {
            float track = bottom - top;
            float thumb = Math.max(18.0F, track * maxRows / (float) rows);
            float along = (track - thumb) * (shown / (this.listMaxScroll * (float) ROW));
            float barX = x + width - 5;
            Liquid.rect(barX, top + along, barX + 2.5F, top + along + thumb, 1.25F, ink(0x40));
        }
    }

    /**
     * One setting the search found: its name and the module it is in, its
     * value on the right. A click opens the module at it (openSetting). A
     * setting the module hides right now -- another mode's -- says so in place
     * of its value.
     */
    private void drawSettingResult(SearchIndex.Entry entry, int x, float rowY, int width, float top, float bottom,
                                   float mx, float my) {
        Module module = (Module) entry.module;
        Property<?> property = (Property<?>) entry.setting;
        boolean shownNow = property.isVisible();
        boolean hovered = mx >= x + 10 && mx <= x + width - 10 && my >= rowY && my <= rowY + ROW_H
                && my >= top && my <= bottom;
        float clippedTop = Math.max(rowY, top);
        float clippedBottom = Math.min(rowY + ROW_H, bottom);
        if (clippedBottom - clippedTop > 4.0F) {
            hit("settingResult", entry, x + 10, clippedTop, x + width - 10, clippedBottom);
        }
        float hoverLit = ease("res:" + entry.owner + ":" + property.getName(), hovered, 9.0F);
        if (hoverLit > 0.01F) {
            Liquid.rect(x + 10, rowY, x + width - 10, rowY + ROW_H, 9.0F,
                    alpha(ink(0x10), hoverLit), alpha(ink(0x0A), hoverLit));
        }
        boolean foreign = !belongsOnPage(module);
        if (foreign) {
            Liquid.rect(x + 10, rowY, x + width - 10, rowY + ROW_H, 9.0F,
                    alpha(foreignColour(), 0.10F), alpha(foreignColour(), 0.05F));
            dashedRim(x + 10, rowY, x + width - 10, rowY + ROW_H, 9.0F, alpha(foreignColour(), 0.75F));
        }
        float cy = rowY + ROW_H / 2.0F;
        /* A hollow ring where a module row has its on/off bead: a setting is
           not on or off. */
        boolean dots = this.theme.stateDots.getValue();
        float nameX = dots ? x + 30 : x + 20;
        if (dots) {
            Liquid.ring(x + 20.5F, cy, 2.3F, 0.9F, FAINT);
        }

        float tagRight = x + width - 18;
        if (foreign) {
            String source = pageOf(module);
            LiquidFont tag = font(7.0F, true);
            float tagLeft = tagRight - tag.width(source) - 10;
            Liquid.rect(tagLeft, cy - 5.5F, tagRight, cy + 5.5F, 5.5F,
                    alpha(foreignColour(), 0.28F), alpha(foreignColour(), 0.18F));
            tag.drawCentred(source, (tagLeft + tagRight) / 2.0F, cy, foreignText());
            tagRight = tagLeft - 6;
        }
        LiquidFont small = font(8.0F, false);
        String value = shownNow ? SettingFacts.format(property, property.getValue()) : "not shown now";
        value = small.trim(value, Math.max(30.0F, (width - 40) * 0.35F));
        float valueLeft = tagRight - small.width(value);
        small.draw(value, valueLeft, cy, shownNow ? accentText() : FAINT);

        LiquidFont name = font(9.5F, false);
        String owner = "in " + module.getName();
        float ownerWidth = small.width(owner);
        float room = valueLeft - 10 - nameX;
        boolean withOwner = room - ownerWidth - 6 >= 40;
        float nameWidth = name.draw(name.trim(entry.title, withOwner ? room - ownerWidth - 6 : room), nameX, cy,
                shownNow ? TEXT : DIM);
        if (withOwner) {
            small.draw(owner, nameX + nameWidth + 6, cy, FAINT);
        }
        if (hovered && this.hint.isEmpty()) {
            String help = property.getHelp();
            this.hint = entry.title + " in " + module.getName()
                    + (shownNow ? "" : "  ·  not shown with its current settings")
                    + (help == null || help.isEmpty() ? "  ·  click to open it" : "  ·  " + help);
        }
    }

    /** The search's results for what is typed, on this page; searched again only when either changes. */
    private SearchIndex.Results searchResults() {
        String query = search();
        if (!query.equals(this.searchedFor) || !this.page.equals(this.searchedOn)) {
            this.searchedFor = query;
            this.searchedOn = this.page;
            this.searchResults = this.index.search(query, module -> belongsOnPage((Module) module),
                    MAX_SETTING_RESULTS);
        }
        return this.searchResults;
    }

    /**
     * Every module in the menu, in its order, and every setting of each, with
     * the descriptions and help ModuleDocs gave them at start-up.
     */
    private void buildIndex() {
        SearchIndex built = new SearchIndex();
        java.util.Set<Module> seen = new java.util.HashSet<Module>();
        for (List<Module> list : this.byCategory.values()) {
            for (Module module : list) {
                if (module == null || !seen.add(module)) {
                    continue;
                }
                built.addModule(module, module.getName(), module.getDescription());
                List<Property<?>> properties = Atlas.propertyManager.properties.get(module.getClass());
                if (properties == null) {
                    continue;
                }
                for (Property<?> property : properties) {
                    built.addSetting(module, property, module.getName(), property.getLabel(), property.getName(),
                            property.getHelp());
                }
            }
        }
        this.index = built;
        this.searchedFor = null;
    }

    /**
     * Opens a setting found by the search: selects its module, scrolls the
     * settings card to it, opens its info row and makes it glow for a moment.
     * A setting the module hides right now (another mode's, or behind a
     * switch) is not in the card to scroll to, so that is said instead.
     */
    private void openSetting(SearchIndex.Entry entry) {
        openSetting((Module) entry.module, (Property<?>) entry.setting);
    }

    private void openSetting(Module module, Property<?> property) {
        if (this.selected != module) {
            this.ui.set("detailArrive", Float.valueOf(0.0F));
            this.descriptionOpen = null;
        }
        this.selected = module;
        List<Property<?>> shown = propertiesOf(module);
        int index = shown.indexOf(property);
        if (index < 0) {
            this.infoOpen = null;
            this.detailScroll = 0;
            this.ui.set("detailScroll", Float.valueOf(0.0F));
            tell(Toasts.Kind.INFO, property.getLabel() + " is not shown with the current settings of "
                    + module.getName() + ": another mode or switch uses it");
            return;
        }
        this.infoOpen = property;
        /* The same arithmetic as drawSettingsCard: the headings and rows above
           it. The card clamps the scroll to its content when it is drawn. */
        int offset = 0;
        for (int i = 0; i < index; i++) {
            offset += (headingBefore(shown, i) != null ? HEADING_HEIGHT : 0) + rowHeightOf(shown.get(i));
        }
        this.detailScroll = offset;
        this.ui.set("detailScroll", Float.valueOf(offset));
        pulse("found:" + module.getName() + ":" + property.getName());
    }

    /** Enter in the search box: the first result, a module before a setting. */
    private void openFirstResult() {
        if (search().trim().isEmpty()) {
            return;
        }
        SearchIndex.Results results = searchResults();
        if (!results.modules.isEmpty()) {
            Module module = (Module) results.modules.get(0).module;
            if (this.selected != module) {
                this.ui.set("detailArrive", Float.valueOf(0.0F));
                this.detailScroll = 0;
                this.ui.set("detailScroll", Float.valueOf(0.0F));
                this.infoOpen = null;
                this.descriptionOpen = null;
            }
            this.selected = module;
        } else if (!results.settings.isEmpty()) {
            openSetting(results.settings.get(0));
        }
    }

    /** The selection droplet, as the theme has it; {@code shown} fades it with its pill. */
    private Liquid.Style pillStyle(float shown) {
        Liquid.Style style = this.theme.pill(accent(), shown);
        style.selection = true;
        return style;
    }

    /**
     * An on/off switch whose knob travels on a spring and stretches with its
     * speed, like a drop of something thick being pushed.
     */
    private void drawSwitch(String key, float x, float cy, float w, float h, boolean on) {
        Spring s = spring("sw:" + key, on ? 1.0F : 0.0F, 520.0F, 24.0F);
        float t = Math.max(-0.08F, Math.min(1.08F, s.value));
        float lit = Math.max(0.0F, Math.min(1.0F, t));
        int accent = accent();
        float y = cy - h / 2.0F;
        Liquid.rect(x, y, x + w, y + h, h / 2.0F, alpha(ink(0x1A), 1.0F - lit));
        if (lit > 0.01F) {
            Liquid.rect(x, y, x + w, y + h, h / 2.0F, alpha(accent, 0.95F * lit), alpha(accent, 0.80F * lit));
        }
        Liquid.rim(x, y, x + w, y + h, h / 2.0F, 1.0F, ink(0x38), ink(0x0F));
        float pad = 1.5F;
        float knob = h - pad * 2.0F;
        float stretch = Math.min(knob * 0.45F, Math.abs(s.velocity) * this.jelly * 0.018F * knob);
        float kx = x + pad + (w - pad * 2.0F - knob) * t;
        Liquid.shadow(kx - stretch / 2.0F, y + pad, kx + knob + stretch / 2.0F, y + pad + knob,
                knob / 2.0F, 2.5F, 0x59000000, 0.8F);
        Liquid.rect(kx - stretch / 2.0F, y + pad, kx + knob + stretch / 2.0F, y + pad + knob,
                knob / 2.0F, 0xFFFFFFFF, 0xFFE9EEF6);
    }

    private static String suffixOf(Module module) {
        try {
            String[] parts = module.getSuffix();
            if (parts == null || parts.length == 0) {
                return "";
            }
            StringBuilder sb = new StringBuilder();
            for (String part : parts) {
                if (part == null || part.isEmpty()) {
                    continue;
                }
                if (sb.length() > 0) {
                    sb.append(' ');
                }
                sb.append(part.replaceAll("[&§][0-9a-fklmnor]", ""));
            }
            return sb.toString();
        } catch (Exception ignored) {
            return "";
        }
    }

    private void drawDetail(ScaledResolution sr, int x, int y, int width, int height, float mx, float my) {
        if (this.selected == null) {
            font(14.0F, true).draw("Select a module", x + 16, y + 17, TEXT);
            float cursor = y + 34;
            LiquidFont body = font(8.5F, false);
            for (String line : body.wrap("Its description, its key, whether the ArrayList shows it, "
                    + "and every setting appear here.", width - 32)) {
                body.draw(line, x + 16, cursor, DIM);
                cursor += 11;
            }
            return;
        }

        /* A new selection arrives rather than replacing the old one in place:
           the pane fades up and settles a few pixels. */
        float arrived = toward("detailArrive", 1.0F, 11.0F);
        float lift = (1.0F - arrived) * 6.0F;
        int accent = accent();

        float titleY = y + 17 + lift;
        LiquidFont title = font(14.0F, true);
        title.draw(title.trim(this.selected.getName(), width - 80), x + 16, titleY,
                alpha(TEXT, arrived));
        float powerX = x + width - 16 - 30;
        hit("power", this.selected, powerX - 3, titleY - 11, powerX + 33, titleY + 11);
        drawSwitch("power:" + this.selected.getName(), powerX, titleY, 30.0F, 16.0F, this.selected.isEnabled());
        if (mx >= powerX - 3 && mx <= powerX + 33 && my >= titleY - 11 && my <= titleY + 11) {
            this.hint = (this.selected.isEnabled() ? "Turn off " : "Turn on ") + this.selected.getName();
        }

        float cursor = y + 34 + lift;
        String description = this.selected.getDescription();
        LiquidFont body = font(8.5F, false);
        if (description != null && !description.isEmpty()) {
            List<String> lines = body.wrap(description, width - 32);
            /* Five lines, as before; a longer one gets More, which shows the
               rest (2026-10-23: the rest used to be cut off for good). */
            boolean expanded = this.descriptionOpen == this.selected;
            int shownLines = Math.min(lines.size(), expanded ? DESCRIPTION_MAX_LINES : 5);
            for (int i = 0; i < shownLines; i++) {
                String line = lines.get(i);
                if (i == shownLines - 1 && lines.size() > shownLines) {
                    line = body.trim(line + " " + lines.get(i + 1), width - 32);
                }
                body.draw(line, x + 16, cursor, alpha(DIM, arrived));
                cursor += 11;
            }
            if (lines.size() > 5) {
                LiquidFont link = font(8.0F, true);
                String label = expanded ? "Less" : "More";
                float linkWidth = link.width(label);
                boolean hovered = mx >= x + 14 && mx <= x + 18 + linkWidth && my >= cursor - 6 && my <= cursor + 6;
                hit("descMore", this.selected, x + 14, cursor - 6, x + 18 + linkWidth, cursor + 6);
                link.draw(label, x + 16, cursor, alpha(hovered ? accent : accentText(), arrived));
                if (hovered) {
                    this.hint = expanded ? "Show the first five lines of the description"
                            : "Show the whole description";
                }
                cursor += 12;
            }
            cursor += 10;
        } else {
            cursor += 4;
        }
        cursor = drawHealthStrip(this.selected, x, width, cursor, mx, my, arrived);

        /* The two things about a module that are not properties: its key and
           whether the array list shows it. Both live on Module itself, which
           is why neither appeared among the settings. */
        boolean rebinding = this.binding == this.selected;
        String keyName = rebinding ? "Press a key"
                : (this.selected.getKey() == 0 ? "None"
                        : atlas.util.KeyBindUtil.getKeyName(this.selected.getKey()));
        float chipX = x + 16;
        float bindWidth = drawChip("bind", this.selected, chipX, cursor, "key", "Bind", keyName,
                rebinding, mx, my);
        boolean hidden = this.selected.isHidden();
        drawChip("hide", this.selected, chipX + bindWidth + 6, cursor, hidden ? "eyeOff" : "eye",
                "ArrayList", hidden ? "Hidden" : "Shown", !hidden, mx, my);
        cursor += 22;

        font(7.0F, true).drawTracked("SETTINGS", x + 18, cursor, FAINT, 0.8F);
        drawResetModule(x, width, cursor, mx, my);
        cursor += 8;

        drawSettingsCard(sr, this.selected.getName(), propertiesOf(this.selected),
                x + 12, x + width - 12, cursor, y + height - 6, mx, my);
    }

    /**
     * "N changed · Reset" on the SETTINGS line, when any of the module's
     * settings differs from its default -- counted over all of them, those of
     * other modes too, since all of them are reset. A click asks first (Dialog).
     */
    private void drawResetModule(int x, int width, float cy, float mx, float my) {
        List<Property<?>> all = Atlas.propertyManager.properties.get(this.selected.getClass());
        int changed = all == null ? 0 : SettingFacts.countChanged(all);
        if (changed == 0) {
            return;
        }
        String text = changed + " changed  ·  Reset";
        LiquidFont small = font(8.0F, false);
        float right = x + width - 18;
        float left = right - small.width(text);
        boolean hovered = mx >= left - 4 && mx <= right + 4 && my >= cy - 7 && my <= cy + 7;
        hit("resetModule", this.selected, left - 4, cy - 7, right + 4, cy + 7);
        small.draw(text, left, cy, hovered ? accent() : accentText());
        if (hovered) {
            this.hint = changed + (changed == 1 ? " setting differs" : " settings differ")
                    + " from the default  ·  click to reset them all (it asks first)";
        }
    }

    /**
     * A rounded card of settings rows that scrolls inside itself: a module's
     * settings, or one group of the Appearance page. {@code owner} keeps the
     * animations of two owners' same-named settings apart.
     */
    private void drawSettingsCard(ScaledResolution sr, String owner, List<Property<?>> properties,
                                  float cardX, float cardX2, float cardTop, float cardBottom, float mx, float my) {
        LiquidFont body = font(8.5F, false);
        if (cardBottom - cardTop < 24) {
            return;
        }
        this.cardWidth = cardX2 - cardX;
        if (properties.isEmpty()) {
            Liquid.rect(cardX, cardTop, cardX2, cardTop + 28, 12.0F, ink(0x0E), ink(0x09));
            Liquid.rim(cardX, cardTop, cardX2, cardTop + 28, 12.0F, 1.0F, ink(0x24), ink(0x0A));
            body.draw("No settings", cardX + 10, cardTop + 14, FAINT);
            return;
        }
        int content = 4;
        for (int i = 0; i < properties.size(); i++) {
            content += headingBefore(properties, i) != null ? HEADING_HEIGHT : 0;
            content += rowHeightOf(properties.get(i));
        }
        float visible = cardBottom - cardTop;
        cardBottom = Math.min(cardBottom, cardTop + content);
        this.detailMaxScroll = Math.max(0, Math.round(content - visible));
        if (this.detailScroll > this.detailMaxScroll) {
            this.detailScroll = this.detailMaxScroll;
        }
        Liquid.rect(cardX, cardTop, cardX2, cardBottom, 12.0F, ink(0x0E), ink(0x09));
        Liquid.rim(cardX, cardTop, cardX2, cardBottom, 12.0F, 1.0F, ink(0x24), ink(0x0A));

        float scroll = scrollEase("detailScroll", this.detailScroll);
        float rowY = cardTop + 2 - scroll;
        float clipTop = cardTop + 1;
        float clipBottom = cardBottom - 1;
        clip(sr, cardX, clipTop, cardX2 - cardX, clipBottom - clipTop);
        boolean firstRow = true;
        for (int index = 0; index < properties.size(); index++) {
            Property<?> property = properties.get(index);
            String heading = headingBefore(properties, index);
            if (heading != null) {
                if (rowY > clipBottom) {
                    break;
                }
                if (rowY + HEADING_HEIGHT > clipTop) {
                    font(7.0F, true).drawTracked(atlas.module.ModuleDocs.heading(heading).toUpperCase(java.util.Locale.ROOT), cardX + 12,
                            rowY + HEADING_HEIGHT / 2.0F + 1, accentText(), 0.6F);
                    Liquid.rect(cardX + 10, rowY + HEADING_HEIGHT - 1, cardX2 - 10, rowY + HEADING_HEIGHT - 0.5F,
                            0.0F, ink(0x1A));
                }
                rowY += HEADING_HEIGHT;
                firstRow = true;
            }
            int rowHeight = rowHeightOf(property);
            if (rowY > clipBottom) {
                break;
            }
            if (rowY + rowHeight > clipTop) {
                if (!firstRow) {
                    Liquid.rect(cardX + 10, rowY, cardX2 - 10, rowY + 0.5F, 0.0F, ink(0x12));
                }
                AtlasInspector.note("settings", property.getName(),
                        cardX + 10, rowY, cardX2 - 10, rowY + rowHeight);
                drawProperty(owner, property, cardX, rowY, cardX2 - cardX, mx, my, clipTop, clipBottom);
            }
            firstRow = false;
            rowY += rowHeight;
        }
        unclip();
        if (this.detailMaxScroll > 0) {
            float track = clipBottom - clipTop - 8;
            float thumb = Math.max(16.0F, track * visible / content);
            float along = (track - thumb) * (scroll / this.detailMaxScroll);
            Liquid.rect(cardX2 - 5, clipTop + 4 + along, cardX2 - 2.5F, clipTop + 4 + along + thumb,
                    1.25F, ink(0x40));
        }
    }

    /**
     * A capsule with an icon, a faint label and a bright value. Returns its
     * width so the next one can follow it.
     */
    private float drawChip(String kind, Module module, float x, float cy, String iconName,
                           String label, String value, boolean active, float mx, float my) {
        LiquidFont small = font(8.0F, false);
        LiquidFont bold = font(8.0F, true);
        float width = 20 + small.width(label) + 5 + bold.width(value) + 10;
        float y = cy - 9;
        float y2 = cy + 9;
        int accent = accent();
        boolean hovered = mx >= x && mx <= x + width && my >= y && my <= y2;
        hit(kind, module, x, y, x + width, y2);
        float hover = ease("chip:" + kind, hovered, 12.0F);
        float on = ease("chipOn:" + kind, active, 12.0F);
        float press = pulseAt("chip:" + kind, 260L);
        /* A tap squashes the chip and lets it spring back. */
        float squash = press >= 0.0F ? (float) Math.sin(press * Math.PI) * 1.2F : 0.0F;
        float x1 = x + squash;
        float x3 = x + width - squash;
        if (on > 0.01F) {
            Liquid.shadow(x1, y, x3, y2, 9.0F, 5.0F, alpha(accent, 0.30F * on), 0.0F);
        }
        Liquid.rect(x1, y + squash * 0.5F, x3, y2 - squash * 0.5F, 9.0F,
                blend(alpha(INK, 0.07F + 0.04F * hover), alpha(accent, 0.24F), on),
                blend(alpha(INK, 0.04F + 0.02F * hover), alpha(accent, 0.14F), on));
        Liquid.rim(x1, y + squash * 0.5F, x3, y2 - squash * 0.5F, 9.0F, 1.0F,
                blend(ink(0x2E), alpha(accent, 0.6F), on), ink(0x0A));
        icon(iconName, x + 11, cy, blend(DIM, accent, on));
        float labelWidth = small.draw(label, x + 20, cy, FAINT);
        bold.draw(value, x + 20 + labelWidth + 5, cy, TEXT);
        if (hovered) {
            if ("bind".equals(kind)) {
                this.hint = this.binding == module
                        ? "Press any key to bind, Escape to clear"
                        : "Click to set a key for " + module.getName();
            } else {
                this.hint = module.isHidden()
                        ? module.getName() + " is hidden from the ArrayList  ·  click to show it"
                        : module.getName() + " is shown in the ArrayList  ·  click to hide it";
            }
        }
        return width;
    }

    /**
     * How tall a settings row is. A slider row carries its name and value on
     * one line and the track under them.
     */
    /** Height of a settings heading row (ModuleDocs groups). */
    private static final int HEADING_HEIGHT = 16;

    /**
     * The heading to draw before the setting at index: its group, when it
     * differs from the setting before it. Null for none -- and none at all in
     * a list whose settings share one group or have none.
     */
    private static String headingBefore(List<Property<?>> properties, int index) {
        String group = properties.get(index).getGroup();
        if (group == null) {
            return null;
        }
        String previous = index == 0 ? null : properties.get(index - 1).getGroup();
        if (group.equals(previous)) {
            return null;
        }
        if (index == 0) {
            /* A single heading over everything says nothing. */
            boolean other = false;
            for (Property<?> property : properties) {
                if (!group.equals(property.getGroup())) {
                    other = true;
                    break;
                }
            }
            if (!other) {
                return null;
            }
        }
        return group;
    }

    /** A heading's colour: the accent, toned down so it reads as a label rather than a control. */
    private int accentText() {
        return (0xCC << 24) | (accent() & 0x00FFFFFF);
    }

    /**
     * The hint line's start for a setting: its name, its default when it was
     * changed, and what it does when that is written down.
     */
    private static String hintName(Property<?> property) {
        String name = SettingFacts.changed(property)
                ? property.getLabel() + " (default " + SettingFacts.defaultText(property) + ")"
                : property.getLabel();
        return property.getHelp() == null ? name : name + "  ·  " + property.getHelp();
    }

    /** How tall a setting's whole block is: its row, an open colour picker and an open info row. */
    private int rowHeightOf(Property<?> property) {
        int height = baseHeightOf(property);
        if (property == this.infoOpen) {
            height += infoHeight(property, this.cardWidth);
        }
        return height;
    }

    /** The row itself, with an open colour picker: the part that is hovered and clicked as the setting. */
    private int baseHeightOf(Property<?> property) {
        if (isNumeric(property)) {
            return 32;
        }
        return property == this.colorOpen ? 22 + PICKER_HEIGHT : 22;
    }

    /** What a setting does, wrapped for its info row; a plain note when nothing is written down yet. */
    private static List<String> infoHelp(Property<?> property, LiquidFont font, float width) {
        String help = property.getHelp();
        return font.wrap(help == null || help.isEmpty() ? "No description for this setting yet." : help, width);
    }

    /** Its default and range, and who is overriding it, wrapped for its info row. */
    private static List<String> infoFacts(Property<?> property, LiquidFont font, float width) {
        StringBuilder facts = new StringBuilder();
        if (SettingFacts.resettable(property)) {
            facts.append("Default ").append(SettingFacts.defaultText(property));
        }
        String range = SettingFacts.range(property);
        if (!range.isEmpty()) {
            if (facts.length() > 0) {
                facts.append("  ·  ");
            }
            facts.append("Range ").append(range);
        }
        List<String> lines = new ArrayList<String>();
        if (facts.length() > 0) {
            lines.addAll(font.wrap(facts.toString(), width));
        }
        if (property.isOverridden()) {
            lines.addAll(font.wrap("In effect now: " + SettingFacts.format(property, property.getValue())
                    + ", from " + property.getSourceOwner() + ". Reset changes your own value.", width));
        }
        return lines;
    }

    /** Why the value is risky, wrapped for the info row; nothing when it is not. */
    private List<String> infoRisk(Property<?> property, LiquidFont font, float width) {
        String risk = riskOf(property);
        return risk == null ? java.util.Collections.<String>emptyList() : font.wrap(risk, width);
    }

    private int infoHeight(Property<?> property, float width) {
        LiquidFont body = font(8.0F, false);
        int height = 4 + infoHelp(property, body, width - 32).size() * INFO_LINE;
        int risk = infoRisk(property, body, width - 32).size();
        if (risk > 0) {
            height += risk * INFO_LINE + 3;
        }
        int facts = infoFacts(property, body, width - 32).size();
        if (facts > 0) {
            height += 3 + facts * INFO_LINE;
        }
        if (SettingFacts.resettable(property)) {
            height += 4 + 20;
        }
        return height + 8;
    }

    /**
     * A setting's info row, under its row: what it does in full, its default
     * and range, and Reset when it differs from the default. The hint bar only
     * ever had room for the first line of this.
     */
    private void drawSettingInfo(Property<?> property, float x, float top, float width, float mx, float my,
                                 float paneTop, float paneBottom) {
        LiquidFont body = font(8.0F, false);
        float left = x + 16;
        float textWidth = width - 32;
        int height = infoHeight(property, width);
        Liquid.rect(x + 8, top, x + width - 8, top + height - 4, 8.0F, ink(0x0A));
        float lineTop = top + 4;
        List<String> risk = infoRisk(property, body, textWidth);
        if (!risk.isEmpty()) {
            for (String line : risk) {
                body.draw(line, left, lineTop + INFO_LINE / 2.0F, AtlasStyle.warn);
                lineTop += INFO_LINE;
            }
            lineTop += 3;
        }
        for (String line : infoHelp(property, body, textWidth)) {
            body.draw(line, left, lineTop + INFO_LINE / 2.0F, DIM);
            lineTop += INFO_LINE;
        }
        List<String> facts = infoFacts(property, body, textWidth);
        if (!facts.isEmpty()) {
            lineTop += 3;
            for (String line : facts) {
                body.draw(line, left, lineTop + INFO_LINE / 2.0F, DIM);
                lineTop += INFO_LINE;
            }
        }
        if (!SettingFacts.resettable(property)) {
            return;
        }
        float buttonCy = lineTop + 4 + 10;
        if (!SettingFacts.changed(property)) {
            body.draw("At its default", left, buttonCy, FAINT);
            return;
        }
        /* Only a button wholly in view takes clicks, like every row. */
        if (buttonCy - 10 >= paneTop && buttonCy + 10 <= paneBottom) {
            String fallback = SettingFacts.defaultText(property);
            drawButton("infoReset", property, left - 2, buttonCy, 0.0F,
                    fallback.length() <= 16 ? "Reset to " + fallback : "Reset to default", 1, mx, my);
        }
    }

    /**
     * A setting's label, with the mark of a changed value before it and the
     * (i) that opens its info row after it. maxWidth is the room the row
     * leaves for the label (Float.MAX_VALUE: never trimmed); the button takes
     * its share of it. Returns where the button ends.
     */
    private float drawLabel(Property<?> property, String text, float labelX, float cy, float maxWidth,
                            boolean rowHovered, float mx, float my, boolean clickable) {
        LiquidFont label = font(8.5F, false);
        if (SettingFacts.changed(property)) {
            Liquid.dot(labelX - 5.0F, cy, 1.5F, accent());
        }
        String risk = riskOf(property);
        float marks = INFO_ICON + 4.0F + (risk != null ? 12.0F : 0.0F);
        float room = maxWidth >= Float.MAX_VALUE ? maxWidth : Math.max(12.0F, maxWidth - marks);
        float drawn = label.draw(room >= Float.MAX_VALUE ? text : label.trim(text, room), labelX, cy, DIM);
        float ix = labelX + drawn + 4.0F + INFO_ICON / 2.0F;
        boolean open = this.infoOpen == property;
        boolean hovered = clickable && mx >= ix - 6 && mx <= ix + 6 && my >= cy - 7 && my <= cy + 7;
        int colour = open || hovered ? accent() : rowHovered ? DIM : FAINT;
        Liquid.ring(ix, cy, INFO_ICON / 2.0F, 0.9F, colour);
        Liquid.dot(ix, cy - 1.9F, 0.7F, colour);
        Liquid.line(ix, cy - 0.5F, ix, cy + 2.3F, 1.0F, colour);
        if (clickable) {
            hit("info", property, ix - 6, cy - 7, ix + 6, cy + 7);
        }
        if (hovered) {
            this.hint = (open ? "Hide" : "Show") + " what " + property.getLabel() + " does, its default and range";
        }
        float end = ix + INFO_ICON / 2.0F;
        if (risk != null) {
            drawWarning(end + 7.0F, cy, 3.6F);
            if (clickable && mx >= end + 2.0F && mx <= end + 12.0F && my >= cy - 6.0F && my <= cy + 6.0F) {
                this.hint = property.getLabel() + ": " + risk;
            }
            end += 12.0F;
        }
        return end;
    }

    /**
     * Draws one setting and registers every part of it that can be clicked.
     *
     * The registration is the point. Each control's hit box is the rectangle
     * this method just drew it at, written once, so there is no second piece
     * of arithmetic to fall out of step with the first.
     */
    private void drawProperty(String owner, Property<?> property, float x, float top, float width,
                              float mx, float my, float paneTop, float paneBottom) {
        drawPropertyRow(owner, property, x, top, width, mx, my, paneTop, paneBottom);
        if (property == this.infoOpen) {
            drawSettingInfo(property, x, top + baseHeightOf(property), width, mx, my, paneTop, paneBottom);
        }
    }

    /** The setting's own row (and an open colour picker), without its info row. */
    private void drawPropertyRow(String owner, Property<?> property, float x, float top, float width,
                                 float mx, float my, float paneTop, float paneBottom) {
        float labelX = x + 10;
        float right = x + width - 10;
        float cy = top + 11;
        int rowHeight = baseHeightOf(property);
        int accent = accent();
        boolean hovered = mx >= x && mx <= x + width && my >= top && my <= top + rowHeight
                && my >= paneTop && my <= paneBottom;
        /* A row straddling the edge of the clip is half drawn, so only a row
           wholly inside it takes a hit box at all. */
        boolean clickable = top >= paneTop - 2 && top + rowHeight <= paneBottom + 2;
        /* Opened from a search result: the row glows for a moment, so the eye
           finds it in the card. */
        float found = pulseAt("found:" + owner + ":" + property.getName(), 1600L);
        if (found >= 0.0F) {
            Liquid.rect(x + 4, top + 1, x + width - 4, top + rowHeight - 1, 8.0F,
                    alpha(accent, 0.30F * (1.0F - smooth(found))));
        }
        if (hovered) {
            /* The range is the part people actually need and the part most
               menus hide: a slider with no numbers cannot be set deliberately. */
            this.hint = hintName(property) + "  ·  " + property.getValuePrompt()
                    + (property instanceof ModeProperty ? "  ·  click for the list, right click to cycle"
                            : isNumeric(property)
                                    ? "  ·  drag the bar, or click the number to type one"
                                    : "");
            String risk = riskOf(property);
            if (risk != null) {
                this.hint = property.getLabel() + " is at a risky value  ·  " + risk;
            }
        }

        LiquidFont valueFont = font(8.5F, true);
        String value = property.formatValue().replaceAll("[&§][0-9a-fklmnor]", "");
        boolean beingEdited = this.editing == property;
        String id = owner + ":" + property.getName();

        if (property instanceof BooleanProperty) {
            if (clickable) {
                hit("toggle", property, x + 2, top, x + width - 2, top + rowHeight);
            }
            /* After the row's hit, so the (i) wins where they overlap. */
            drawLabel(property, property.getLabel(), labelX, cy, width - 50, hovered, mx, my, clickable);
            drawSwitch(id, right - 22, cy, 22.0F, 12.0F, ((BooleanProperty) property).getValue());
            return;
        }

        if (isNumeric(property)) {
            /* The bar is the player's value (the base); the number is the one in
               effect. While a tuner holds an override they differ, and the
               number alone looked like a slider that did nothing (BackTrack
               adaptive-delay under an AutoTune trial, 2026-10-02). Say whose. */
            String overrider = property.isOverridden() ? property.getSourceOwner() : null;
            if (hovered && overrider != null) {
                this.hint = hintName(property) + "  ·  " + property.describe() + "  ·  the bar is your value";
            }
            String shownValue = beingEdited ? this.editInput.text()
                    : overrider != null ? value + " (" + overrider + ")" : value;
            float shownWidth = valueFont.width(shownValue);
            float valueLeft = right - Math.max(14.0F, shownWidth) - 3;
            drawLabel(property, property.getLabel(), labelX, cy, Math.min(width - 70, valueLeft - labelX - 8),
                    hovered, mx, my, clickable);
            if (beingEdited) {
                Liquid.rect(valueLeft - 3, cy - 6.5F, right + 3, cy + 6.5F, 5.0F, ink(0x1F));
                Liquid.rim(valueLeft - 3, cy - 6.5F, right + 3, cy + 6.5F, 5.0F, 1.0F,
                        alpha(editEdge(accent), 0.7F), ink(0x14));
            }
            float commit = pulseAt("edit:" + property.getName(), 360L);
            if (commit >= 0.0F) {
                Liquid.rect(valueLeft - 3, cy - 6.5F, right + 3, cy + 6.5F, 5.0F,
                        alpha(accent, 0.35F * (1.0F - commit)));
            }
            if (beingEdited) {
                drawEditedText(valueFont, Float.MAX_VALUE, false, right, cy, accent);
            } else {
                valueFont.draw(shownValue, right - shownWidth, cy, TEXT);
            }
            if (clickable) {
                hit("type", property, valueLeft - 4, cy - 7, right + 4, cy + 6);
            }

            float barY = top + 23;
            float barX = labelX;
            float barX2 = right;
            /* The grab area is the whole strip the track sits in, not the
               three pixels it is painted in, and it starts below the number's
               box so the two never share a pixel. */
            if (clickable) {
                hit("slide", property, barX - 3, barY - 5, barX2 + 3, barY + 7);
            }
            float min = minimumOf(property);
            float max = maximumOf(property);
            float fraction = max <= min ? 0.0F : (currentOf(property) - min) / (max - min);
            /* On a spring even while dragging: the handle trailing the pointer
               very slightly is what gives the control weight, and the value
               itself is already exact. */
            Spring knob = spring("sl:" + id, fraction, 520.0F, 30.0F);
            float f = Math.max(0.0F, Math.min(1.0F, knob.value));
            float fillX = barX + (barX2 - barX) * f;
            boolean hue = property.getName().endsWith("-hue");
            int knobColour = 0xFFFFFFFF;
            if (hue) {
                /* A hue is chosen by looking, so the track is the spectrum. */
                float segment = (barX2 - barX) / 6.0F;
                for (int i = 0; i < 6; i++) {
                    Liquid.rectH(barX + segment * i, barY - 2.0F, barX + segment * (i + 1) + 0.5F, barY + 2.0F,
                            0.0F, AtlasTheme.hsb(i * 60, 0.75F, 1.0F), AtlasTheme.hsb((i + 1) * 60, 0.75F, 1.0F));
                }
                knobColour = AtlasTheme.hsb(currentOf(property), 0.75F, 1.0F);
            } else {
                Liquid.rect(barX, barY - 1.5F, barX2, barY + 1.5F, 1.5F, ink(0x1F));
                if (fillX > barX + 0.5F) {
                    Liquid.shadow(barX, barY - 1.5F, fillX, barY + 1.5F, 1.5F, 4.0F, alpha(accent, 0.55F), 0.0F);
                    Liquid.rectH(barX, barY - 1.5F, fillX, barY + 1.5F, 1.5F, alpha(accent, 0.75F), accent);
                }
            }
            /* The knob swells while held and turns into a drop of clear glass
               that magnifies the track under it. */
            boolean held = this.dragging == property;
            Spring grow = spring("grab:" + id, held ? 1.0F : 0.0F, 420.0F, 20.0F);
            float r = 5.0F + 2.6F * Math.max(0.0F, grow.value);
            Liquid.shadow(fillX - r, barY - r, fillX + r, barY + r, r, 3.0F, 0x66000000, 1.0F);
            if (grow.value > 0.05F && Liquid.glass()) {
                Liquid.Style drop = new Liquid.Style(r * 0.8F, 3.0F, 0.15F, 1.1F, 1.15F,
                        ink(0x14), 0.85F, 0.0F, 0.0F);
                drop.magnify = 1.0F + 0.45F * Math.min(1.0F, grow.value);
                this.lenses.add(new Lens(fillX - r, barY - r, fillX + r, barY + r, r, drop,
                        new float[]{x, paneTop, width, paneBottom - paneTop}));
                Liquid.rect(fillX - r, barY - r, fillX + r, barY + r, r,
                        alpha(0xFFFFFFFF, 1.0F - Math.min(1.0F, grow.value)),
                        alpha(0xFFE6ECF5, 1.0F - Math.min(1.0F, grow.value)));
            } else {
                Liquid.rect(fillX - r, barY - r, fillX + r, barY + r, r, 0xFFFFFFFF, 0xFFE6ECF5);
            }
            if (hue) {
                Liquid.dot(fillX, barY, r - 1.8F, knobColour);
            }
            return;
        }

        if (property instanceof ModeProperty) {
            ModeProperty mode = (ModeProperty) property;
            boolean open = this.dropdown == mode;
            LiquidFont modeFont = font(8.0F, true);
            float textWidth = modeFont.width(value);
            float capsuleLeft = right - textWidth - 22;
            drawLabel(property, property.getLabel(), labelX, cy, capsuleLeft - labelX - 6, hovered, mx, my, clickable);
            if (clickable) {
                hit("mode", mode, capsuleLeft - 2, cy - 8, right + 2, cy + 8);
            }
            float hover = ease("mode:" + id, hovered || open, 12.0F);
            float commit = pulseAt("edit:" + property.getName(), 360L);
            Liquid.rect(capsuleLeft, cy - 7, right, cy + 7, 7.0F,
                    alpha(accent, 0.16F + 0.08F * hover + (commit >= 0.0F ? 0.3F * (1.0F - commit) : 0.0F)));
            if (hover > 0.01F) {
                Liquid.rim(capsuleLeft, cy - 7, right, cy + 7, 7.0F, 1.0F,
                        alpha(accent, 0.5F * hover), alpha(accent, 0.15F * hover));
            }
            modeFont.draw(value, capsuleLeft + 7, cy, accent);
            icon(open ? "chevronUp" : "chevron", right - 8, cy, accent);
            if (open) {
                /* Where the list hangs from, recorded here because this is the
                   only place that knows where the row ended up after scrolling. */
                this.dropX = capsuleLeft;
                this.dropY = cy + 8;
                this.dropRight = right;
                this.dropAnchored = clickable;
            }
            return;
        }

        if (property instanceof TextProperty) {
            float labelEnd = drawLabel(property, property.getLabel(), labelX, cy, Float.MAX_VALUE, hovered, mx, my,
                    clickable);
            float available = right - labelEnd - 10;
            /* The tail rather than the head: the end of a comma separated
               list is the part being typed. */
            FieldLayout field = beingEdited ? layoutField(this.editInput, valueFont, available, true) : null;
            String clipped = value;
            if (!beingEdited && valueFont.width(clipped) > available) {
                while (clipped.length() > 1 && valueFont.width("..." + clipped) > available) {
                    clipped = clipped.substring(1);
                }
                clipped = "..." + clipped;
            }
            float clippedWidth = beingEdited ? field.width : valueFont.width(clipped);
            if (clickable) {
                hit("type", property, right - clippedWidth - 6, cy - 7, right + 4, cy + 7);
            }
            if (beingEdited) {
                Liquid.rect(right - clippedWidth - 5, cy - 6.5F, right + 3, cy + 6.5F, 5.0F, ink(0x1F));
                Liquid.rim(right - clippedWidth - 5, cy - 6.5F, right + 3, cy + 6.5F, 5.0F, 1.0F,
                        alpha(editEdge(accent), 0.7F), ink(0x14));
                drawField(this.editInput, valueFont, field, right, cy, accent, true);
                drawEditCaret(cy, accent);
            } else {
                valueFont.draw(clipped, right - clippedWidth, cy, TEXT);
            }
            return;
        }

        if (property instanceof KeyProperty) {
            /* A key of its own, such as Clutch's hold-key: click, then press it.
               It used to fall through to the read-only row below, so a hold
               trigger could be chosen but never given a key (2026-10-02). */
            KeyProperty key = (KeyProperty) property;
            boolean listening = this.keyEditing == key;
            String shown = listening ? "Press a key" : key.getKeyName();
            LiquidFont keyFont = font(8.0F, true);
            float capsuleLeft = right - keyFont.width(shown) - 14;
            if (hovered) {
                this.hint = hintName(property) + "  ·  click, then press a key  ·  Esc clears it";
            }
            drawLabel(property, property.getLabel(), labelX, cy, capsuleLeft - labelX - 6, hovered, mx, my, clickable);
            if (clickable) {
                hit("keyProp", key, capsuleLeft - 2, cy - 8, right + 2, cy + 8);
            }
            Liquid.rect(capsuleLeft, cy - 7, right, cy + 7, 7.0F, alpha(accent, listening ? 0.38F : 0.16F));
            if (listening) {
                Liquid.rim(capsuleLeft, cy - 7, right, cy + 7, 7.0F, 1.0F, alpha(accent, 0.8F), alpha(accent, 0.3F));
            }
            keyFont.draw(shown, capsuleLeft + 7, cy, listening ? TEXT : accent);
            return;
        }

        if (property instanceof ColorProperty) {
            drawColour((ColorProperty) property, x, top, width, cy, labelX, right, hovered, mx, my, paneTop,
                    paneBottom, valueFont, beingEdited, accent);
            return;
        }

        /* Anything else -- item lists -- is shown but not editable here,
           because there is no honest control for it yet. Printing it is
           still better than hiding it. */
        float labelEnd = drawLabel(property, property.getLabel(), labelX, cy, Float.MAX_VALUE, hovered, mx, my,
                clickable);
        String shown = valueFont.trim(value, right - labelEnd - 10);
        valueFont.drawRight(shown, right, cy, TEXT);
    }

    /**
     * A colour setting: a swatch and its hex on the row; a click on the row
     * opens a picker under it -- saturation/brightness field, hue strip --
     * and a click on the hex types one. Theme's colour-1..3, HUD's custom
     * colours and the rest used to be read-only here (2026-10-02).
     *
     * Each part takes clicks only while it is wholly inside the pane, like
     * every other row, but measured per part: the open picker is tall, and
     * a row half scrolled out of view should still close from its header.
     */
    private void drawColour(ColorProperty colour, float x, float top, float width, float cy, float labelX,
                            float right, boolean hovered, float mx, float my, float paneTop, float paneBottom,
                            LiquidFont valueFont, boolean beingEdited, int accent) {
        int rgb = colour.getValue() & 0xFFFFFF;
        boolean open = this.colorOpen == colour;
        String hex = beingEdited ? this.editInput.text() : String.format("#%06X", rgb);
        float swatchLeft = right - 22;
        float hexRight = swatchLeft - 6;
        float hexLeft = hexRight - valueFont.width(hex);
        boolean rowClickable = top >= paneTop - 2 && top + 22 <= paneBottom + 2;
        if (rowClickable) {
            hit("colorOpen", colour, x + 2, top, x + width - 2, top + 22);
            /* Registered after the row, so it is found first. */
            hit("type", colour, hexLeft - 4, cy - 7, hexRight + 3, cy + 7);
        }
        if (hovered && my <= top + 22) {
            this.hint = colour.getLabel() + "  ·  click to " + (open ? "close" : "open") + " the picker"
                    + "  ·  click the hex to type one";
        }
        /* After the row's hits, so the (i) wins where they overlap. The label,
           not the raw key (colour rows alone showed "line-color"). */
        drawLabel(colour, colour.getLabel(), labelX, cy, hexLeft - labelX - 8, hovered && my <= top + 22, mx, my,
                rowClickable);
        if (beingEdited) {
            Liquid.rect(hexLeft - 3, cy - 6.5F, hexRight + 3, cy + 6.5F, 5.0F, ink(0x1F));
            Liquid.rim(hexLeft - 3, cy - 6.5F, hexRight + 3, cy + 6.5F, 5.0F, 1.0F, alpha(editEdge(accent), 0.7F),
                    ink(0x14));
            drawEditedText(valueFont, Float.MAX_VALUE, false, hexRight, cy, accent);
        } else {
            valueFont.draw(hex, hexLeft, cy, TEXT);
        }
        Liquid.rect(swatchLeft, cy - 6, right, cy + 6, 4.0F, 0xFF000000 | rgb);
        Liquid.rim(swatchLeft, cy - 6, right, cy + 6, 4.0F, 1.0F, ink(0x40), ink(0x20));
        if (!open) {
            return;
        }

        if (rgb != this.colorSynced) {
            java.awt.Color.RGBtoHSB((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF, this.colorHsb);
            this.colorSynced = rgb;
        }
        int pure = 0xFF000000 | (java.awt.Color.HSBtoRGB(this.colorHsb[0], 1.0F, 1.0F) & 0xFFFFFF);

        /* Saturation left to right, brightness top to bottom: white to the
           pure hue across, then a veil from clear to black down. */
        float boxX = labelX;
        float boxX2 = right;
        float boxY = top + 24;
        float boxY2 = boxY + 50;
        Liquid.rectH(boxX, boxY, boxX2, boxY2, 4.0F, 0xFFFFFFFF, pure);
        Liquid.rect(boxX, boxY, boxX2, boxY2, 4.0F, 0x00000000, 0xFF000000);
        Liquid.rim(boxX, boxY, boxX2, boxY2, 4.0F, 1.0F, ink(0x30), ink(0x18));
        float px = boxX + (boxX2 - boxX) * this.colorHsb[1];
        float py = boxY + (boxY2 - boxY) * (1.0F - this.colorHsb[2]);
        Liquid.ring(px, py, 3.5F, 1.5F, 0xFFFFFFFF);
        if (boxY >= paneTop - 2 && boxY2 <= paneBottom + 2) {
            hit("colorSV", colour, boxX, boxY, boxX2, boxY2);
        }

        float hueY = boxY2 + 12;
        float segment = (boxX2 - boxX) / 6.0F;
        for (int i = 0; i < 6; i++) {
            Liquid.rectH(boxX + segment * i, hueY - 3.0F, boxX + segment * (i + 1) + 0.5F, hueY + 3.0F, 0.0F,
                    AtlasTheme.hsb(i * 60, 1.0F, 1.0F), AtlasTheme.hsb((i + 1) * 60, 1.0F, 1.0F));
        }
        float hx = boxX + (boxX2 - boxX) * this.colorHsb[0];
        Liquid.dot(hx, hueY, 5.0F, 0xFFFFFFFF);
        Liquid.dot(hx, hueY, 3.5F, pure);
        if (hueY - 6 >= paneTop - 2 && hueY + 6 <= paneBottom + 2) {
            hit("colorHue", colour, boxX - 3, hueY - 6, boxX2 + 3, hueY + 6);
        }
        if (hovered && my > top + 22) {
            this.hint = colour.getLabel() + "  ·  drag in the field for shade, along the strip for hue";
        }
    }

    /** Moves the open picker's dragged control to the pointer and writes the colour. */
    private void colourDragTo(float mouseX, float mouseY) {
        if (this.colorDrag == null || this.colorOpen == null || this.colorW <= 0.0F) {
            return;
        }
        float fx = Math.max(0.0F, Math.min(1.0F, (mouseX - this.colorX) / this.colorW));
        if ("sv".equals(this.colorDrag)) {
            float fy = this.colorH <= 0.0F ? 0.0F : Math.max(0.0F, Math.min(1.0F, (mouseY - this.colorY) / this.colorH));
            this.colorHsb[1] = fx;
            this.colorHsb[2] = 1.0F - fy;
        } else {
            /* Hue 1.0 is hue 0.0: stop short so the knob stays at the right end. */
            this.colorHsb[0] = Math.min(fx, 0.9999F);
        }
        int rgb = java.awt.Color.HSBtoRGB(this.colorHsb[0], this.colorHsb[1], this.colorHsb[2]) & 0xFFFFFF;
        this.colorSynced = rgb;
        this.colorOpen.setValue(Integer.valueOf(rgb));
    }

    /**
     * The expanded list of a mode setting, as a glass popover that springs
     * out of the value it belongs to.
     *
     * Cycling on click is fine for two options and hostile for eight: finding
     * the one wanted means clicking past the others and reading each in turn.
     */
    private void drawDropdown(int windowTop, int windowHeight, float mx, float my) {
        /* ModeProperty's own list (2026-10-23): the names used to be split back
           out of the chat prompt at its commas. */
        String[] modes = this.dropdown.visibleModes();
        if (modes.length == 0) {
            this.dropdown = null;
            return;
        }
        LiquidFont item = font(8.5F, false);
        LiquidFont itemBold = font(8.5F, true);
        float widest = 0.0F;
        for (String mode : modes) {
            widest = Math.max(widest, itemBold.width(mode));
        }
        int rowHeight = 16;
        int fit = Math.max(3, (int) ((windowHeight - 16) / (float) rowHeight));
        int rows = Math.min(modes.length, Math.min(DROP_ROWS, fit));
        this.dropMaxScroll = modes.length - rows;
        float height = rows * rowHeight + 8;
        float width = Math.max(this.dropRight - this.dropX, widest + 34);
        float right = this.dropRight;
        float left = right - width;
        float top = this.dropY + 4;
        boolean above = false;
        /* Flipped above the row when there is no room below it. */
        if (top + height > windowTop + windowHeight - 4) {
            top = this.dropY - 20 - height;
            above = true;
        }
        if (top < windowTop + 4) {
            top = windowTop + 4;
        }
        Spring grow = spring("drop:" + this.dropdown.getName(), 1.0F, 420.0F, 22.0F);
        float g = Math.max(0.0F, grow.value);
        float anchorY = above ? top + height : top;
        float y1 = anchorY + (top - anchorY) * g;
        float y2 = anchorY + (top + height - anchorY) * g;
        float squeeze = (1.0F - Math.min(1.0F, g)) * width * 0.08F;

        Liquid.shadow(left + squeeze, y1, right - squeeze, y2, 12.0F, 14.0F, 0x73000000, 6.0F);
        Liquid.Style popover = new Liquid.Style(8.0F, 6.0F, 0.10F, 1.2F, 0.85F, 0xB80D1119, 0.6F, 0.0F, 0.0F);
        popover.frost = 5.0F;
        Liquid.lens(left + squeeze, y1, right - squeeze, y2, 12.0F, popover);
        if (!Liquid.glass()) {
            Liquid.rect(left + squeeze, y1, right - squeeze, y2, 12.0F, 0xF0101420, 0xF00B0E14);
            Liquid.rim(left + squeeze, y1, right - squeeze, y2, 12.0F, 1.0F, ink(0x40), ink(0x0F));
        }
        if (g < 0.6F) {
            return;
        }
        float content = Math.min(1.0F, (g - 0.6F) / 0.4F);
        /* Menu rows are the visible modes (ModeProperty.hide): the current
           mode's row, not its index. */
        int current = -1;
        int[] visible = this.dropdown.visibleIndices();
        for (int v = 0; v < visible.length; v++) {
            if (visible[v] == this.dropdown.getValue()) {
                current = v;
            }
        }
        if (this.dropScrollInit) {
            /* Opened with the current mode in view, a little below the top. */
            this.dropScrollInit = false;
            this.dropScroll = current < 0 ? 0 : current - rows / 3;
        }
        this.dropScroll = Math.max(0, Math.min(this.dropMaxScroll, this.dropScroll));
        int accent = accent();
        if (this.dropMaxScroll > 0) {
            /* Where the visible rows sit in the whole list. */
            float trackTop = top + 6;
            float trackBottom = top + height - 6;
            float thumb = Math.max(10.0F, (trackBottom - trackTop) * rows / modes.length);
            float thumbTop = trackTop + (trackBottom - trackTop - thumb) * this.dropScroll / this.dropMaxScroll;
            Liquid.rect(right - 5, trackTop, right - 3, trackBottom, 1.0F, alpha(ink(0x14), content));
            Liquid.rect(right - 5, thumbTop, right - 3, thumbTop + thumb, 1.0F, alpha(ink(0x55), content));
        }
        for (int r = 0; r < rows; r++) {
            int i = r + this.dropScroll;
            float rowY = top + 4 + r * rowHeight;
            float cy = rowY + rowHeight / 2.0F;
            boolean hovered = mx >= left && mx <= right && my >= rowY && my <= rowY + rowHeight;
            hit("pick", Integer.valueOf(i), left, rowY, right, rowY + rowHeight);
            /* Colour choices show their colour. */
            int swatch = this.theme.swatch(this.dropdown, i < visible.length ? visible[i] : i, clickGuiAccent());
            if (swatch != 0) {
                Liquid.dot(left + 12, cy, 3.5F, alpha(swatch, content));
                Liquid.ring(left + 12, cy, 3.5F, 0.8F, alpha(ink(0x40), content));
            }
            if (i == current) {
                Liquid.rect(left + 4, rowY + 1, right - 8, rowY + rowHeight - 1, 7.0F,
                        alpha(accent, 0.24F * content));
                if (swatch == 0) {
                    icon("check", left + 12, cy, alpha(accent, content));
                } else {
                    Liquid.ring(left + 12, cy, 5.2F, 1.0F, alpha(TEXT, content));
                }
            } else if (hovered) {
                Liquid.rect(left + 4, rowY + 1, right - 8, rowY + rowHeight - 1, 7.0F,
                        alpha(ink(0x1A), content));
            }
            (i == current ? itemBold : item).draw(modes[i], left + 22, cy,
                    alpha(i == current ? TEXT : (hovered ? TEXT : DIM), content));
        }
        if (mx >= left && mx <= right && my >= top && my <= top + height) {
            this.hint = this.dropdown.getName() + "  ·  " + modes.length + " options";
        }
    }

    /**
     * The hint bar. Always present, always at the same place, showing whatever
     * the pointer is over -- so an explanation never moves, never covers what
     * it describes, and never has to be chased.
     */
    private void drawHintBar(int x, int y, int width) {
        float shownHint = ease("hint", !this.hint.isEmpty(), 12.0F);
        String text = this.hint.isEmpty()
                ? "Click a module for its settings  ·  its switch turns it on or off  ·  type to search  ·  drag the header to move"
                : this.hint;
        LiquidFont small = font(7.5F, false);
        text = small.trim(text, width - 50);
        small.draw(text, x + 16, y + FOOTER / 2.0F - 1, blend(FAINT, DIM, shownHint));
    }

    /**
     * The toasts, centred along the foot of the window above the hint bar,
     * the newest lowest. Each is a hit of its own, so a click on one dismisses
     * it and reaches nothing drawn under it.
     */
    private void drawToasts(int x, int y, int width, int height, float mx, float my) {
        long now = System.currentTimeMillis();
        List<Toasts.Toast> shown = this.toasts.visible(now);
        if (shown.isEmpty()) {
            return;
        }
        LiquidFont text = font(AtlasStyle.BODY, false);
        float bottom = y + height - FOOTER - 8;
        for (int i = shown.size() - 1; i >= 0; i--) {
            Toasts.Toast toast = shown.get(i);
            long age = now - toast.at;
            float fade = Math.max(0.0F, Math.min(1.0F, age / 120.0F) * Math.min(1.0F, (toast.life - age) / 300.0F));
            float rise = (1.0F - Math.min(1.0F, age / 160.0F)) * 4.0F;
            String line = text.trim(toast.text, width - 80.0F);
            float w = text.width(line) + 30.0F;
            float tx = x + (width - w) / 2.0F;
            float ty2 = bottom + rise;
            float ty = ty2 - 20.0F;
            float cy = (ty + ty2) / 2.0F;
            int kind = toast.kind == Toasts.Kind.ERROR ? AtlasStyle.danger
                    : toast.kind == Toasts.Kind.WARN ? AtlasStyle.warn
                    : toast.kind == Toasts.Kind.OK ? AtlasStyle.ok : accent();
            Liquid.shadow(tx, ty, tx + w, ty2, AtlasStyle.R_CONTROL, 10.0F, alpha(0x59000000, fade), 2.0F);
            Liquid.rect(tx, ty, tx + w, ty2, AtlasStyle.R_CONTROL, alpha(AtlasStyle.toast, fade));
            Liquid.rim(tx, ty, tx + w, ty2, AtlasStyle.R_CONTROL, 1.0F, alpha(kind, 0.55F * fade),
                    alpha(ink(0x0A), fade));
            Liquid.dot(tx + 11, cy, 2.5F, alpha(kind, fade));
            text.draw(line, tx + 19, cy, alpha(TEXT, fade));
            hit("toast", toast, tx, ty, tx + w, ty2);
            if (mx >= tx && mx <= tx + w && my >= ty && my <= ty2) {
                this.hint = toast.action != null ? toast.text : toast.text + "  ·  click to dismiss";
            }
            bottom = ty - 6.0F;
        }
    }

    /** A result or a problem, as a toast; in chat while the menu closes, when a toast would never be seen. */
    private void tell(Toasts.Kind kind, String message) {
        if (this.closing || this.mc.currentScreen != this) {
            say(message);
            return;
        }
        this.toasts.add(message, kind, System.currentTimeMillis());
    }

    /** A module just switched on in the menu: what it now works against, once, as toasts. */
    private void warnConflicts(Module module) {
        if (!module.isEnabled()) {
            return;
        }
        this.healthAt = 0L;
        for (atlas.management.Conflicts.Conflict conflict : atlas.management.Conflicts.newFor(module.getName())) {
            tell(Toasts.Kind.WARN, conflict.message);
        }
    }

    /** Says what a key now does, and what else answers to the same key. */
    private void reportKey(String what, String self, int key) {
        if (key == 0) {
            tell(Toasts.Kind.OK, what + " has no key now");
            return;
        }
        String name = atlas.util.KeyBindUtil.getKeyName(key);
        List<String> others = KeyConflicts.find(key, self, KeyConflicts.bindings());
        if (others.isEmpty()) {
            tell(Toasts.Kind.OK, what + " bound to " + name);
        } else {
            tell(Toasts.Kind.WARN, what + " bound to " + name + "  ·  " + name + " is also bound to "
                    + KeyConflicts.join(others));
        }
    }

    // ---- text fields ----------------------------------------------------

    /** A field's text laid out to a width: what shows, from which character, and how wide it is. */
    private static final class FieldLayout {
        final int start;
        final String head;
        final String shown;
        final String tail;
        final float width;

        FieldLayout(int start, String head, String shown, String tail, float width) {
            this.start = start;
            this.head = head;
            this.shown = shown;
            this.tail = tail;
            this.width = width;
        }
    }

    /**
     * A field's text in maxWidth: all of it when it fits, otherwise the part
     * around the caret with "..." where it is cut. preferTail shows the end of a
     * long text rather than its start.
     */
    private static FieldLayout layoutField(TextInput input, LiquidFont font, float maxWidth, boolean preferTail) {
        String text = input.text();
        int start = 0;
        int end = text.length();
        if (font.width(text) > maxWidth) {
            float dots = font.width("...");
            int[] window = TextLayout.window(text, input.caret(), font::width,
                    Math.max(0.0F, maxWidth - dots * 2.0F), preferTail);
            start = window[0];
            end = window[1];
        }
        String head = start > 0 ? "..." : "";
        String tail = end < text.length() ? "..." : "";
        String shown = text.substring(start, end);
        return new FieldLayout(start, head, shown, tail, font.width(head + shown + tail));
    }

    /**
     * Draws a laid-out field from x, or ending at x when alignRight, with a
     * Ctrl+A selection behind it. Records where the text went, so a click can
     * place the caret, and leaves the caret's x in fieldCaretX.
     */
    private void drawField(TextInput input, LiquidFont font, FieldLayout field, float x, float cy, int colour,
                           boolean alignRight) {
        float left = alignRight ? x - field.width : x;
        float textX = left + font.width(field.head);
        if (input.allSelected()) {
            Liquid.rect(textX - 1, cy - 5.5F, textX + font.width(field.shown) + 1, cy + 5.5F, 2.0F,
                    alpha(accent(), 0.35F));
        }
        font.draw(field.head + field.shown + field.tail, left, cy, colour);
        input.drawnAt(textX, field.start, font::width);
        int caret = Math.max(field.start, Math.min(field.start + field.shown.length(), input.caret()));
        this.fieldCaretX = textX + font.width(field.shown.substring(0, caret - field.start));
    }

    /** The setting being typed into, ending at {@code right}, and its caret. */
    private void drawEditedText(LiquidFont font, float maxWidth, boolean preferTail, float right, float cy, int accent) {
        drawField(this.editInput, font, layoutField(this.editInput, font, maxWidth, preferTail), right, cy, accent, true);
        drawEditCaret(cy, accent);
    }

    private void drawEditCaret(float cy, int accent) {
        if (AtlasStyle.caretOn()) {
            Liquid.rect(this.fieldCaretX + 1, cy - 4.5F, this.fieldCaretX + 2, cy + 4.5F, 0.0F, accent);
        }
    }

    /** The edge of the field being typed into: red after Enter on something that is not a value for it. */
    private int editEdge(int accent) {
        return this.editError ? AtlasStyle.danger : accent;
    }

    // ---- icons --------------------------------------------------------

    /**
     * Icons, drawn from primitives so they are sharp at any scale. Category
     * icons are chosen by name; anything unknown gets the grid.
     */
    private static void icon(String name, float x, float y, int c) {
        String key = name.toLowerCase();
        if (key.equals("combat")) {
            Liquid.ring(x, y, 4.2F, 1.1F, c);
            Liquid.dot(x, y, 1.3F, c);
            Liquid.line(x, y - 6.0F, x, y - 4.6F, 1.1F, c);
            Liquid.line(x, y + 4.6F, x, y + 6.0F, 1.1F, c);
            Liquid.line(x - 6.0F, y, x - 4.6F, y, 1.1F, c);
            Liquid.line(x + 4.6F, y, x + 6.0F, y, 1.1F, c);
        } else if (key.equals("theme")) {
            /* A palette: the board and three wells of paint. */
            Liquid.ring(x, y, 5.0F, 1.1F, c);
            Liquid.dot(x - 2.2F, y - 1.6F, 1.1F, c);
            Liquid.dot(x + 1.6F, y - 2.2F, 1.1F, c);
            Liquid.dot(x + 2.4F, y + 1.4F, 1.1F, c);
        } else if (key.equals("movement")) {
            Liquid.line(x - 4.5F, y - 3.5F, x - 1.0F, y, 1.3F, c);
            Liquid.line(x - 1.0F, y, x - 4.5F, y + 3.5F, 1.3F, c);
            Liquid.line(x + 0.5F, y - 3.5F, x + 4.0F, y, 1.3F, c);
            Liquid.line(x + 4.0F, y, x + 0.5F, y + 3.5F, 1.3F, c);
        } else if (key.equals("render") || key.equals("eye")) {
            Liquid.rim(x - 5.5F, y - 3.3F, x + 5.5F, y + 3.3F, 3.3F, 1.1F, c, c);
            Liquid.dot(x, y, 1.7F, c);
        } else if (key.equals("eyeoff")) {
            Liquid.rim(x - 5.5F, y - 3.3F, x + 5.5F, y + 3.3F, 3.3F, 1.1F, c, c);
            Liquid.dot(x, y, 1.7F, c);
            Liquid.line(x - 5.0F, y + 4.0F, x + 5.0F, y - 4.0F, 1.2F, c);
        } else if (key.equals("player")) {
            Liquid.dot(x, y - 2.6F, 2.2F, c);
            Liquid.rect(x - 4.0F, y + 0.9F, x + 4.0F, y + 5.2F, 2.6F, c);
        } else if (key.equals("key")) {
            Liquid.rim(x - 5.0F, y - 3.5F, x + 5.0F, y + 3.5F, 1.8F, 1.0F, c, c);
            Liquid.dot(x - 2.4F, y - 1.1F, 0.6F, c);
            Liquid.dot(x, y - 1.1F, 0.6F, c);
            Liquid.dot(x + 2.4F, y - 1.1F, 0.6F, c);
            Liquid.line(x - 2.2F, y + 1.4F, x + 2.2F, y + 1.4F, 1.0F, c);
        } else if (key.equals("chevron")) {
            Liquid.line(x - 2.6F, y - 1.2F, x, y + 1.3F, 1.2F, c);
            Liquid.line(x, y + 1.3F, x + 2.6F, y - 1.2F, 1.2F, c);
        } else if (key.equals("chevronup")) {
            Liquid.line(x - 2.6F, y + 1.2F, x, y - 1.3F, 1.2F, c);
            Liquid.line(x, y - 1.3F, x + 2.6F, y + 1.2F, 1.2F, c);
        } else if (key.equals("check")) {
            Liquid.line(x - 3.0F, y, x - 1.0F, y + 2.2F, 1.3F, c);
            Liquid.line(x - 1.0F, y + 2.2F, x + 3.2F, y - 2.4F, 1.3F, c);
        } else if (key.equals("palette")) {
            Liquid.ring(x, y, 4.8F, 1.1F, c);
            Liquid.dot(x - 1.8F, y - 1.9F, 1.0F, c);
            Liquid.dot(x + 1.9F, y - 1.2F, 1.0F, c);
            Liquid.dot(x - 0.6F, y + 2.2F, 1.0F, c);
        } else if (key.equals("colors")) {
            Liquid.dot(x - 2.3F, y - 1.2F, 2.6F, alpha(c, 0.9F));
            Liquid.dot(x + 2.3F, y - 1.2F, 2.6F, alpha(c, 0.6F));
            Liquid.dot(x, y + 2.3F, 2.6F, alpha(c, 0.4F));
        } else if (key.equals("glass")) {
            Liquid.rim(x - 5.0F, y - 5.0F, x + 5.0F, y + 5.0F, 2.5F, 1.1F, c, c);
            Liquid.line(x - 2.0F, y + 1.5F, x + 1.5F, y - 2.0F, 1.1F, c);
        } else if (key.equals("depth")) {
            Liquid.rim(x - 5.0F, y - 2.0F, x + 3.0F, y + 5.0F, 1.8F, 1.0F, alpha(c, 0.5F), alpha(c, 0.5F));
            Liquid.rim(x - 3.0F, y - 5.0F, x + 5.0F, y + 2.0F, 1.8F, 1.1F, c, c);
        } else if (key.equals("selection")) {
            Liquid.rim(x - 6.0F, y - 3.2F, x + 6.0F, y + 3.2F, 3.2F, 1.1F, c, c);
            Liquid.dot(x - 2.6F, y, 1.2F, c);
        } else if (key.equals("motion")) {
            Liquid.line(x - 5.0F, y + 1.5F, x - 2.5F, y - 2.0F, 1.2F, c);
            Liquid.line(x - 2.5F, y - 2.0F, x, y + 2.0F, 1.2F, c);
            Liquid.line(x, y + 2.0F, x + 2.5F, y - 2.0F, 1.2F, c);
            Liquid.line(x + 2.5F, y - 2.0F, x + 5.0F, y + 1.5F, 1.2F, c);
        } else if (key.equals("text")) {
            Liquid.line(x - 4.5F, y - 4.0F, x + 4.5F, y - 4.0F, 1.3F, c);
            Liquid.line(x, y - 4.0F, x, y + 4.5F, 1.3F, c);
        } else if (key.equals("folder")) {
            Liquid.rim(x - 5.5F, y - 3.2F, x + 5.5F, y + 4.2F, 1.8F, 1.1F, c, c);
            Liquid.rect(x - 5.5F, y - 4.8F, x - 0.8F, y - 2.4F, 1.1F, c);
        } else if (key.equals("file")) {
            Liquid.rim(x - 3.8F, y - 5.0F, x + 3.8F, y + 5.0F, 1.6F, 1.0F, c, c);
            Liquid.line(x - 1.8F, y - 1.2F, x + 1.8F, y - 1.2F, 0.9F, c);
            Liquid.line(x - 1.8F, y + 1.3F, x + 1.8F, y + 1.3F, 0.9F, c);
        /* 2026-10-11: the categories and Appearance sections that fell back
           to the four squares. */
        } else if (key.equals("world")) {
            /* A globe: outline, equator, a meridian and two latitudes. */
            Liquid.ring(x, y, 5.0F, 1.1F, c);
            Liquid.line(x - 5.0F, y, x + 5.0F, y, 1.0F, c);
            Liquid.line(x, y - 5.0F, x, y + 5.0F, 1.0F, c);
            Liquid.line(x - 4.0F, y - 2.6F, x + 4.0F, y - 2.6F, 0.9F, c);
            Liquid.line(x - 4.0F, y + 2.6F, x + 4.0F, y + 2.6F, 0.9F, c);
        } else if (key.equals("misc")) {
            /* "More": three dots. */
            Liquid.dot(x - 4.0F, y, 1.4F, c);
            Liquid.dot(x, y, 1.4F, c);
            Liquid.dot(x + 4.0F, y, 1.4F, c);
        } else if (key.equals("exploit")) {
            /* A lightning bolt. */
            Liquid.line(x + 1.8F, y - 5.5F, x - 2.6F, y + 0.4F, 1.3F, c);
            Liquid.line(x - 2.6F, y + 0.4F, x + 2.6F, y - 0.4F, 1.3F, c);
            Liquid.line(x + 2.6F, y - 0.4F, x - 1.8F, y + 5.5F, 1.3F, c);
        } else if (key.equals("client")) {
            /* A gear: hub, rim and six teeth. */
            Liquid.ring(x, y, 3.4F, 1.2F, c);
            Liquid.dot(x, y, 1.1F, c);
            for (int i = 0; i < 6; i++) {
                double a = Math.PI / 3.0 * i;
                float cx = (float) Math.cos(a);
                float cy = (float) Math.sin(a);
                Liquid.line(x + cx * 3.9F, y + cy * 3.9F, x + cx * 5.6F, y + cy * 5.6F, 1.5F, c);
            }
        } else if (key.equals("language")) {
            /* A speech bubble with two lines of text. */
            Liquid.line(x - 5.0F, y - 4.2F, x + 5.0F, y - 4.2F, 1.0F, c);
            Liquid.line(x + 5.0F, y - 4.2F, x + 5.0F, y + 2.4F, 1.0F, c);
            Liquid.line(x + 5.0F, y + 2.4F, x - 1.0F, y + 2.4F, 1.0F, c);
            Liquid.line(x - 5.0F, y + 2.4F, x - 5.0F, y - 4.2F, 1.0F, c);
            Liquid.line(x - 1.0F, y + 2.4F, x - 3.4F, y + 5.2F, 1.0F, c);
            Liquid.line(x - 2.8F, y - 1.9F, x + 2.8F, y - 1.9F, 0.9F, c);
            Liquid.line(x - 2.8F, y + 0.2F, x + 1.2F, y + 0.2F, 0.9F, c);
        } else if (key.equals("cosmos")) {
            /* A ringed planet. */
            Liquid.ring(x, y, 3.2F, 1.1F, c);
            Liquid.line(x - 6.0F, y + 2.2F, x + 6.0F, y - 2.2F, 1.0F, c);
            Liquid.dot(x + 4.6F, y - 4.6F, 0.8F, c);
        } else if (key.equals("layout")) {
            /* A page: a header bar over two columns. */
            Liquid.rect(x - 5.0F, y - 5.0F, x + 5.0F, y - 2.4F, 1.0F, c);
            Liquid.rect(x - 5.0F, y - 1.2F, x - 0.8F, y + 5.0F, 1.0F, c);
            Liquid.rect(x + 0.6F, y - 1.2F, x + 5.0F, y + 5.0F, 1.0F, c);
        } else if (key.equals("privacy")) {
            /* A padlock: shackle over a body with a keyhole. */
            Liquid.ring(x, y - 2.2F, 2.8F, 1.1F, c);
            Liquid.rect(x - 4.2F, y - 1.0F, x + 4.2F, y + 5.0F, 1.2F, c);
        } else if (key.equals("attention")) {
            /* A warning triangle and its mark. */
            Liquid.line(x, y - 4.6F, x - 4.8F, y + 3.8F, 1.1F, c);
            Liquid.line(x - 4.8F, y + 3.8F, x + 4.8F, y + 3.8F, 1.1F, c);
            Liquid.line(x + 4.8F, y + 3.8F, x, y - 4.6F, 1.1F, c);
            Liquid.line(x, y - 1.6F, x, y + 1.0F, 1.1F, c);
            Liquid.dot(x, y + 2.4F, 0.6F, c);
        } else if (key.equals("search")) {
            Liquid.ring(x - 0.8F, y - 0.8F, 3.3F, 1.2F, c);
            Liquid.line(x + 1.6F, y + 1.6F, x + 3.8F, y + 3.8F, 1.3F, c);
        } else {
            float q = 1.9F;
            float[][] cells = {{-2.3F, -2.3F}, {2.3F, -2.3F}, {-2.3F, 2.3F}, {2.3F, 2.3F}};
            for (float[] cell : cells) {
                Liquid.rect(x + cell[0] - q, y + cell[1] - q, x + cell[0] + q, y + cell[1] + q, 1.1F, c);
            }
        }
    }

    // ---- colour -------------------------------------------------------

    private static int blend(int a, int b, float t) {
        t = Math.max(0.0F, Math.min(1.0F, t));
        int aa = (a >>> 24) & 0xFF;
        int ba = (b >>> 24) & 0xFF;
        int ar = (a >> 16) & 0xFF;
        int br = (b >> 16) & 0xFF;
        int ag = (a >> 8) & 0xFF;
        int bg = (b >> 8) & 0xFF;
        int ab = a & 0xFF;
        int bb = b & 0xFF;
        return ((int) (aa + (ba - aa) * t) << 24)
                | ((int) (ar + (br - ar) * t) << 16)
                | ((int) (ag + (bg - ag) * t) << 8)
                | (int) (ab + (bb - ab) * t);
    }

    private static int darker(int colour) {
        int r = (int) (((colour >> 16) & 0xFF) * 0.55F);
        int g = (int) (((colour >> 8) & 0xFF) * 0.55F);
        int b = (int) ((colour & 0xFF) * 0.7F);
        return (colour & 0xFF000000) | (r << 16) | (g << 8) | b;
    }

    /** The accent, as the theme has it: its own colour, or the ClickGUI module's. */
    private int accent() {
        return this.theme.accent(clickGuiAccent());
    }

    /** A neutral of the given alpha: white in dark mode, ink in light mode. */
    private static int ink(int alpha) {
        return (alpha << 24) | (INK & 0x00FFFFFF);
    }

    /** Sets the text and neutral colours for the current mode, and tells the HUD. */
    private void paintPalette() {
        boolean light = this.theme.isLight();
        atlas.ui.UiMode.setLight(light);
        AtlasStyle.paint(light);
        TEXT = AtlasStyle.text;
        DIM = AtlasStyle.dim;
        FAINT = AtlasStyle.faint;
        INK = AtlasStyle.ink;
    }

    /** The modules behind a sidebar row: a Legit sub-group on the Legit page, a category elsewhere. */
    private List<Module> modulesOf(String name) {
        if (isQuick(name)) {
            boolean enabled = ENABLED.equals(name);
            List<Module> out = new ArrayList<Module>();
            for (Module module : pageModules()) {
                if (enabled ? module.isEnabled() : needsAttention(module)) {
                    out.add(module);
                }
            }
            return out;
        }
        List<Module> list = "Legit".equals(this.page) ? this.legitGroups.get(name) : this.byCategory.get(name);
        return list == null ? new ArrayList<Module>() : list;
    }

    private static boolean isQuick(String name) {
        return ENABLED.equals(name) || ATTENTION.equals(name);
    }

    /** Every module of this page once, in its categories' order. */
    private List<Module> pageModules() {
        List<Module> out = new ArrayList<Module>();
        java.util.Set<Module> seen = new java.util.HashSet<Module>();
        if ("Legit".equals(this.page)) {
            for (List<Module> list : this.legitGroups.values()) {
                for (Module module : list) {
                    if (module != null && seen.add(module)) {
                        out.add(module);
                    }
                }
            }
            return out;
        }
        for (Map.Entry<String, List<Module>> entry : this.byCategory.entrySet()) {
            if ("Legit".equals(entry.getKey())) {
                continue;
            }
            for (Module module : entry.getValue()) {
                if (module != null && seen.add(module)) {
                    out.add(module);
                }
            }
        }
        return out;
    }

    private String sidebarIcon(String name) {
        if (ENABLED.equals(name)) {
            return "check";
        }
        if (ATTENTION.equals(name)) {
            return "attention";
        }
        return "Legit".equals(this.page) ? LegitGroups.icon(name) : name;
    }

    // ---- health marks ---------------------------------------------------

    /** The module's health marks, or null when it has none. */
    private Health health(Module module) {
        refreshHealth();
        return this.health.get(module);
    }

    /** Named in this session's corrections, or switched on with a setting at a risky value. */
    private boolean needsAttention(Module module) {
        Health h = health(module);
        return h != null && (h.report.named > 0
                || module.isEnabled() && (!h.risky.isEmpty() || !h.conflicts.isEmpty()));
    }

    private static boolean flagDetectorOn() {
        Module detector = Atlas.moduleManager.modules.get(atlas.module.modules.FlagDetector.class);
        return detector != null && detector.isEnabled();
    }

    /**
     * Reads every module's marks again, at most four times a second: the
     * counts are locked against the network thread, and the holds ask seven
     * holders, which is not worth doing every frame.
     */
    private void refreshHealth() {
        long now = System.currentTimeMillis();
        if (now - this.healthAt < HEALTH_MS) {
            return;
        }
        this.healthAt = now;
        this.health.clear();
        Map<String, Module> byName = new HashMap<String, Module>();
        java.util.Set<Module> seen = new java.util.HashSet<Module>();
        for (List<Module> list : this.byCategory.values()) {
            for (Module module : list) {
                if (module == null || !seen.add(module)) {
                    continue;
                }
                String type = module.getClass().getSimpleName();
                byName.put(module.getName(), module);
                byName.put(type, module);
                ModuleHealth.Report report = ModuleHealth.of(now, module.getName(), type);
                if (report.any()) {
                    healthOf(module).report = report;
                }
                if (RiskRules.covers(module.getClass())) {
                    List<Property<?>> properties = Atlas.propertyManager.properties.get(module.getClass());
                    if (properties != null) {
                        for (Property<?> property : properties) {
                            if (RiskRules.warning(module.getClass(), property) != null) {
                                healthOf(module).risky.add(property);
                            }
                        }
                    }
                }
            }
        }
        for (atlas.management.Conflicts.Conflict conflict : atlas.management.Conflicts.current()) {
            if (atlas.management.Conflicts.isIgnored(conflict.key)) {
                continue;
            }
            for (String name : conflict.names) {
                int open = name.indexOf(" (");
                Module module = byName.get(open > 0 ? name.substring(0, open) : name);
                if (module != null && !healthOf(module).conflicts.contains(conflict)) {
                    healthOf(module).conflicts.add(conflict);
                }
            }
        }
        /* Holders name themselves "FakeLag", "Backtrack", "Blink/NoSlow",
           "DelayManager/BedNuker": the part after a slash is the module. */
        for (PacketHolds.Hold hold : PacketHolds.snapshot()) {
            String name = hold.holder == null ? "" : hold.holder;
            int slash = name.lastIndexOf('/');
            Module module = byName.get(slash >= 0 ? name.substring(slash + 1) : name);
            if (module == null) {
                continue;
            }
            Health h = healthOf(module);
            h.held += hold.count;
            String way = hold.direction == PacketHolds.Direction.OUT ? "outgoing" : "incoming";
            h.heldWay = h.heldWay.isEmpty() || h.heldWay.equals(way) ? way : "both ways";
        }
    }

    private Health healthOf(Module module) {
        Health h = this.health.get(module);
        if (h == null) {
            h = new Health();
            this.health.put(module, h);
        }
        return h;
    }

    /** Why this setting of the selected module is at a risky value, or null. */
    private String riskOf(Property<?> property) {
        if (this.selected == null || this.profilesView || this.appearanceView || this.serversView) {
            return null;
        }
        return RiskRules.warning(this.selected.getClass(), property);
    }

    /**
     * A module row's mark after its name: the number of corrections it was
     * named for this session (amber; red when three came in five minutes), or
     * a small warning for a setting at a risky value. Returns where it ends.
     */
    private float drawHealthBadge(Health h, float x, float cy) {
        if (h == null) {
            return x;
        }
        if (h.report.named > 0) {
            LiquidFont bold = font(7.0F, true);
            String count = String.valueOf(h.report.named);
            float w = Math.max(11.0F, bold.width(count) + 8.0F);
            int colour = h.report.recent >= 3 ? AtlasStyle.danger : AtlasStyle.warn;
            Liquid.rect(x, cy - 5.5F, x + w, cy + 5.5F, 5.5F, alpha(colour, 0.9F));
            bold.drawCentred(count, x + w / 2.0F, cy, 0xFF14161C);
            return x + w;
        }
        if (!h.risky.isEmpty() || !h.conflicts.isEmpty()) {
            drawWarning(x + 4.0F, cy, 3.6F);
            return x + 8.0F;
        }
        return x;
    }

    /**
     * The detail pane's health lines, under the description, only when there
     * is something to say. A risky setting's line opens that setting.
     */
    private float drawHealthStrip(Module module, int x, int width, float cursor, float mx, float my, float arrived) {
        Health h = health(module);
        if (h == null) {
            return cursor;
        }
        LiquidFont small = font(8.0F, false);
        float textWidth = width - 44;
        float lineY = cursor;
        ModuleHealth.Report report = h.report;
        if (report.named > 0) {
            String text = "Named as the likely cause of " + report.named
                    + (report.named == 1 ? " correction" : " corrections") + " this session  ·  last "
                    + AtlasProfiles.age(report.lastAt);
            healthLine(small, text, x, lineY, textWidth, report.recent >= 3 ? AtlasStyle.danger : AtlasStyle.warn,
                    arrived);
            lineY += 11;
        }
        if (report.involved > 0) {
            String text = (report.named > 0 ? "Also among the possible causes of " : "Among the possible causes of ")
                    + report.involved + (report.involved == 1 ? " correction" : " corrections");
            healthLine(small, text, x, lineY, textWidth, FAINT, arrived);
            lineY += 11;
        }
        if (h.held > 0) {
            healthLine(small, "Holding " + h.held + (h.held == 1 ? " packet" : " packets") + " now (" + h.heldWay + ")",
                    x, lineY, textWidth, accent(), arrived);
            lineY += 11;
        }
        for (Property<?> property : h.risky) {
            String text = property.getLabel() + " is at a risky value  ·  click to see why";
            boolean hovered = mx >= x + 16 && mx <= x + width - 16 && my >= lineY - 5.5F && my <= lineY + 5.5F;
            hit("riskOpen", property, x + 16, lineY - 5.5F, x + width - 16, lineY + 5.5F);
            healthLine(small, text, x, lineY, textWidth, AtlasStyle.warn, arrived);
            if (hovered) {
                this.hint = property.getLabel() + ": " + RiskRules.warning(module.getClass(), property);
            }
            lineY += 11;
        }
        for (atlas.management.Conflicts.Conflict conflict : h.conflicts) {
            boolean hovered = mx >= x + 16 && mx <= x + width - 16 && my >= lineY - 5.5F && my <= lineY + 5.5F;
            hit("conflictIgnore", conflict, x + 16, lineY - 5.5F, x + width - 16, lineY + 5.5F);
            healthLine(small, conflict.message, x, lineY, textWidth, AtlasStyle.warn, arrived);
            if (hovered) {
                this.hint = conflict.message + "  ·  click to stop warning about this";
            }
            lineY += 11;
        }
        return lineY == cursor ? cursor : lineY + 4;
    }

    private void healthLine(LiquidFont font, String text, int x, float cy, float width, int colour, float arrived) {
        Liquid.dot(x + 20, cy, 2.2F, alpha(colour, arrived));
        font.draw(font.trim(text, width), x + 28, cy, alpha(DIM, arrived));
    }

    /** The top-level page a module lives on, as its tab is labelled. */
    private static String pageOf(Module module) {
        return ModuleCategories.of(module.getClass()) == Category.LEGIT ? "Legit" : "Modules";
    }

    /** The colour that marks a search result from the other page. */
    private static int foreignColour() {
        return AtlasStyle.foreign;
    }

    private static int foreignText() {
        return AtlasStyle.foreignText;
    }

    /** A dashed outline along the straight parts of a rounded rectangle. */
    private static void dashedRim(float x, float y, float x2, float y2, float radius, int colour) {
        float dash = 4.0F;
        float gap = 3.0F;
        float t = 1.0F;
        for (float px = x + radius; px < x2 - radius; px += dash + gap) {
            float end = Math.min(px + dash, x2 - radius);
            Liquid.rect(px, y, end, y + t, 0.0F, colour);
            Liquid.rect(px, y2 - t, end, y2, 0.0F, colour);
        }
        float inset = radius * 0.6F;
        for (float py = y + inset; py < y2 - inset; py += dash + gap) {
            float end = Math.min(py + dash, y2 - inset);
            Liquid.rect(x, py, x + t, end, 0.0F, colour);
            Liquid.rect(x2 - t, py, x2, end, 0.0F, colour);
        }
    }

    private int clickGuiAccent() {
        try {
            atlas.module.modules.ClickGUIModule gui =
                    (atlas.module.modules.ClickGUIModule) Atlas.moduleManager.modules
                            .get(atlas.module.modules.ClickGUIModule.class);
            return gui == null ? 0xFF4FC3F7 : gui.getAccentColor().getRGB();
        } catch (Exception ignored) {
            return 0xFF4FC3F7;
        }
    }

    // ---- clipping -----------------------------------------------------

    /**
     * Restricts drawing to a rectangle given in layout pixels.
     *
     * Scissor works in real framebuffer pixels with the origin at the bottom
     * left, and knows nothing of the opening scale, so the rectangle is taken
     * through both rather than passed through.
     */
    private void clip(ScaledResolution sr, float x, float y, float width, float height) {
        float sx = this.pivotX + (x - this.pivotX) * this.viewScale;
        float sy = this.pivotY + (y - this.pivotY) * this.viewScale + this.slideY;
        float sw = width * this.viewScale;
        float sh = height * this.viewScale;
        int factor = sr.getScaleFactor();
        int left = (int) Math.floor(sx * factor);
        int bottom = (int) Math.floor((sr.getScaledHeight() - (sy + sh)) * factor);
        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        GL11.glScissor(left, bottom, Math.max(0, (int) Math.ceil(sw * factor)),
                Math.max(0, (int) Math.ceil(sh * factor)));
    }

    private static void unclip() {
        GL11.glDisable(GL11.GL_SCISSOR_TEST);
    }

    // ---- input --------------------------------------------------------

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int button) {
        ScaledResolution sr = new ScaledResolution(mc);
        float mx = layoutX(mouseX);
        float my = layoutY(mouseY);
        UiContext.Hit target = hitAt(mx, my);

        if (this.dialog != null) {
            Dialog open = this.dialog;
            if (target != null && target.kind.startsWith("dialog") && target.payload instanceof Integer) {
                this.dialog = null;
                Runnable action = open.actions[((Integer) target.payload).intValue()];
                if (action != null) {
                    action.run();
                }
            } else if (target == null || "dialogOutside".equals(target.kind)) {
                this.dialog = null;
            }
            return;
        }
        /* The report dialog takes every click while it is open: Send sends,
           anything outside the dialog box closes it (2026-10-07). */
        if (this.reportConfirm) {
            if (target != null && "reportSend".equals(target.kind)) {
                sendReport();
            } else if (target != null && "reportReason".equals(target.kind)) {
                this.reportReason = ((Integer) target.payload).intValue();
            } else if (target != null && "reportCancel".equals(target.kind)) {
                this.reportConfirm = false;
            } else if (target == null || "reportOutside".equals(target.kind)) {
                this.reportConfirm = false;
            }
            return;
        }

        /* An open list takes the next click wherever it lands, and that click
           does nothing else. Letting a dismissing click also toggle whatever
           was underneath turns closing a menu into changing a setting. */
        if (this.dropdown != null) {
            ModeProperty open = this.dropdown;
            this.ui.removeSpring("drop:" + open.getName());
            this.dropdown = null;
            if (target != null && "pick".equals(target.kind)) {
                open.setVisible(((Integer) target.payload).intValue());
                pulse("edit:" + open.getName());
            }
            return;
        }
        if (this.orbitPreview.mouseClicked(mouseX, mouseY, button)) {
            return;
        }
        if (target != null && "toast".equals(target.kind)) {
            Toasts.Toast toast = (Toasts.Toast) target.payload;
            this.toasts.dismiss(toast);
            if (toast.action != null) {
                toast.action.run();
            }
            return;
        }

        /* Typing into a setting ends when the pointer goes anywhere else, and
           what was typed is kept rather than thrown away. */
        if (this.editing != null && (target == null || target.payload != this.editing)) {
            commitEdit();
        }
        /* A key setting waiting for its key stops waiting on a click elsewhere. */
        if (this.keyEditing != null && (target == null || target.payload != this.keyEditing)) {
            this.keyEditing = null;
        }

        this.searchFocused = target != null && "search".equals(target.kind);
        this.profileNameFocused = target != null && "profileName".equals(target.kind);
        if (this.searchFocused) {
            this.searchInput.placeAt(mx);
        } else if (this.profileNameFocused) {
            this.profileNameInput.placeAt(mx);
        }
        if (target == null || "search".equals(target.kind) || "profileName".equals(target.kind)) {
            return;
        }
        if (target.kind.startsWith("profile") && handleProfileClick(target)) {
            return;
        }

        this.dragButton = button;
        if ("move".equals(target.kind)) {
            anchorWindow(sr);
            this.movingWindow = true;
            this.grabX = mouseX - this.userX;
            this.grabY = mouseY - this.userY;
            return;
        }
        if ("grip".equals(target.kind)) {
            anchorWindow(sr);
            this.resizingWindow = true;
            this.grabX = this.userWidth - mouseX;
            this.grabY = this.userHeight - mouseY;
            return;
        }
        if ("themeMode".equals(target.kind)) {
            this.theme.toggleMode();
            paintPalette();
            return;
        }
        if ("bugReport".equals(target.kind)) {
            pulse("btn:bugReport");
            openReport();
            return;
        }
        if ("hudEditor".equals(target.kind)) {
            pulse("btn:hudEditor");
            this.mc.displayGuiScreen(new atlas.ui.hud.HudEditorScreen(this));
            return;
        }
        if ("page".equals(target.kind)) {
            selectPage((String) target.payload);
            return;
        }
        if ("appearance".equals(target.kind)) {
            this.appearanceView = true;
            this.profilesView = false;
            this.serversView = false;
            this.page = "Client Settings";
            this.searchInput.clear();
            this.detailScroll = 0;
            return;
        }
        if ("servers".equals(target.kind)) {
            this.serversView = true;
            this.profilesView = false;
            this.appearanceView = false;
            this.page = "Client Settings";
            this.searchInput.clear();
            return;
        }
        if ("autoConfig".equals(target.kind)) {
            atlas.module.modules.ServerProfiles profiles = serverProfiles();
            if (profiles != null) {
                profiles.setEnabled(!profiles.isEnabled());
            }
            return;
        }
        if ("ruleDelete".equals(target.kind)) {
            atlas.module.modules.ServerProfiles profiles = serverProfiles();
            int index = ((Integer) target.payload).intValue();
            if (profiles != null) {
                List<String[]> rules = atlas.module.modules.ServerProfiles.parse(profiles.rules.getValue());
                if (index >= 0 && index < rules.size()) {
                    String[] gone = rules.remove(index);
                    profiles.rules.setValue(atlas.module.modules.ServerProfiles.format(rules));
                    tell(Toasts.Kind.OK, "Removed " + gone[0] + " = " + gone[1]);
                }
            }
            return;
        }
        if ("ruleHere".equals(target.kind)) {
            useProfileHere();
            return;
        }
        if ("group".equals(target.kind)) {
            String group = (String) target.payload;
            if (!group.equals(this.appearanceGroup)) {
                this.infoOpen = null;
                this.ui.set("groupArrive", Float.valueOf(0.0F));
                this.detailScroll = 0;
                this.ui.set("detailScroll", Float.valueOf(0.0F));
            }
            this.appearanceGroup = group;
            return;
        }
        if ("preset".equals(target.kind)) {
            pulse("btn:preset:" + target.payload);
            final java.util.Map<Property<?>, Object> before = this.theme.values();
            this.theme.apply((String) target.payload);
            this.toasts.add("Appearance set to " + target.payload + "  ·  click to undo", Toasts.Kind.OK,
                    System.currentTimeMillis(), () -> this.theme.restore(before));
            return;
        }
        if ("themeReset".equals(target.kind)) {
            pulse("btn:themeReset");
            final java.util.Map<Property<?>, Object> before = this.theme.values();
            this.theme.reset();
            this.toasts.add("Appearance reset  ·  click to undo", Toasts.Kind.OK, System.currentTimeMillis(),
                    () -> this.theme.restore(before));
            return;
        }
        if ("category".equals(target.kind)) {
            this.appearanceView = false;
            this.serversView = false;
            this.profilesView = false;
            this.category = (String) target.payload;
            this.searchInput.clear();
            this.listScroll = 0;
            List<Module> modules = visibleModules();
            this.selected = modules.isEmpty() ? null : modules.get(0);
            this.infoOpen = null;
            this.descriptionOpen = null;
            return;
        }
        if ("rowSwitch".equals(target.kind)) {
            Module module = (Module) target.payload;
            module.setEnabled(!module.isEnabled());
            pulse("tog:" + module.getName());
            warnConflicts(module);
            return;
        }
        if ("conflictIgnore".equals(target.kind)) {
            atlas.management.Conflicts.Conflict conflict = (atlas.management.Conflicts.Conflict) target.payload;
            atlas.management.Conflicts.ignore(conflict.key);
            this.healthAt = 0L;
            tell(Toasts.Kind.OK, "Won't warn about this again");
            return;
        }
        if ("module".equals(target.kind)) {
            Module module = (Module) target.payload;
            /* A row only selects. Toggling from anywhere on the row turned
               modules on by accident while reaching for their settings. */
            if (this.selected != module) {
                this.ui.set("detailArrive", Float.valueOf(0.0F));
                this.detailScroll = 0;
                this.ui.set("detailScroll", Float.valueOf(0.0F));
                this.infoOpen = null;
                this.descriptionOpen = null;
            }
            this.selected = module;
            return;
        }
        if ("settingResult".equals(target.kind)) {
            openSetting((SearchIndex.Entry) target.payload);
            return;
        }
        if ("riskOpen".equals(target.kind) && this.selected != null) {
            openSetting(this.selected, (Property<?>) target.payload);
            return;
        }
        if ("info".equals(target.kind)) {
            Property<?> property = (Property<?>) target.payload;
            this.infoOpen = this.infoOpen == property ? null : property;
            return;
        }
        if ("infoReset".equals(target.kind)) {
            Property<?> property = (Property<?>) target.payload;
            pulse("btn:infoReset");
            if (SettingFacts.reset(property)) {
                pulse("edit:" + property.getName());
                tell(Toasts.Kind.OK, property.getLabel() + " reset to " + SettingFacts.defaultText(property));
            }
            return;
        }
        if ("resetModule".equals(target.kind)) {
            final Module module = (Module) target.payload;
            final List<Property<?>> all = Atlas.propertyManager.properties.get(module.getClass());
            int changed = all == null ? 0 : SettingFacts.countChanged(all);
            this.dialog = new Dialog("Reset " + module.getName() + "?",
                    "Puts its " + changed + (changed == 1 ? " changed setting" : " changed settings")
                            + " back to the defaults, those of its other modes too.",
                    new String[]{"Reset", "Cancel"}, new int[]{2, 1}, new Runnable[]{() -> {
                        int count = all == null ? 0 : SettingFacts.resetAll(all);
                        tell(Toasts.Kind.OK, "Reset " + count + (count == 1 ? " setting" : " settings")
                                + " of " + module.getName() + " to the defaults");
                    }, null});
            return;
        }
        if ("descMore".equals(target.kind)) {
            Module module = (Module) target.payload;
            this.descriptionOpen = this.descriptionOpen == module ? null : module;
            return;
        }
        if ("power".equals(target.kind)) {
            Module module = (Module) target.payload;
            module.setEnabled(!module.isEnabled());
            pulse("tog:" + module.getName());
            warnConflicts(module);
            return;
        }
        if ("bind".equals(target.kind)) {
            this.binding = this.binding == target.payload ? null : (Module) target.payload;
            pulse("chip:bind");
            return;
        }
        if ("hide".equals(target.kind)) {
            Module module = (Module) target.payload;
            module.setHidden(!module.isHidden());
            pulse("chip:hide");
            return;
        }
        if ("toggle".equals(target.kind)) {
            BooleanProperty property = (BooleanProperty) target.payload;
            property.setValue(Boolean.valueOf(!property.getValue()));
            return;
        }
        if ("mode".equals(target.kind)) {
            ModeProperty mode = (ModeProperty) target.payload;
            if (button == 1) {
                /* Right click still cycles, because for a two-option setting
                   opening a list of two is more work than it saves. */
                mode.nextMode();
                pulse("edit:" + mode.getName());
                return;
            }
            Spring grow = new Spring(0.0F, 420.0F, 22.0F);
            this.ui.putSpring("drop:" + mode.getName(), grow);
            this.dropdown = mode;
            this.dropScrollInit = true;
            return;
        }
        if ("type".equals(target.kind)) {
            Property<?> property = (Property<?>) target.payload;
            if (this.editing == property) {
                /* Already typing into it: the click moves the caret. Re-reading
                   the value here would replace what has been typed so far. */
                this.editInput.placeAt(mx);
                return;
            }
            this.editing = property;
            this.editError = false;
            this.editInput.set(property.formatValue()
                    .replaceAll("[&§][0-9a-fklmnor]", "").replace("%", ""));
            return;
        }
        if ("keyProp".equals(target.kind)) {
            KeyProperty key = (KeyProperty) target.payload;
            this.keyEditing = this.keyEditing == key ? null : key;
            return;
        }
        if ("colorOpen".equals(target.kind)) {
            ColorProperty colour = (ColorProperty) target.payload;
            this.colorOpen = this.colorOpen == colour ? null : colour;
            this.colorSynced = -1;
            return;
        }
        if ("colorSV".equals(target.kind) || "colorHue".equals(target.kind)) {
            boolean field = "colorSV".equals(target.kind);
            this.colorDrag = field ? "sv" : "hue";
            /* The strip's hit box has 3 px of grab margin either side. */
            this.colorX = field ? target.x : target.x + 3.0F;
            this.colorW = field ? target.x2 - target.x : (target.x2 - 3.0F) - (target.x + 3.0F);
            this.colorY = target.y;
            this.colorH = target.y2 - target.y;
            colourDragTo(mx, my);
            return;
        }
        if ("slide".equals(target.kind)) {
            Property<?> property = (Property<?>) target.payload;
            this.dragging = property;
            this.dragMin = minimumOf(property);
            this.dragMax = maximumOf(property);
            /* Straight off the rectangle the track was drawn at, less the grab
               margin either side, so the value under the pointer is the value
               on screen. */
            this.dragX = target.x + 3.0F;
            this.dragWidth = (target.x2 - 3.0F) - (target.x + 3.0F);
            dragTo(mx);
        }
    }

    /** Everything the profiles view can be clicked for. Returns whether it was one. */
    private boolean handleProfileClick(UiContext.Hit target) {
        String kind = target.kind;
        if ("profiles".equals(kind)) {
            this.profilesView = true;
            this.appearanceView = false;
            this.serversView = false;
            this.page = "Client Settings";
            this.searchInput.clear();
            return true;
        }
        if ("profile".equals(kind)) {
            String name = (String) target.payload;
            if (!name.equals(this.profileSelected)) {
                this.ui.set("profileArrive", Float.valueOf(0.0F));
            }
            this.profileSelected = name;
            return true;
        }
        if ("profileCreate".equals(kind)) {
            pulse("btn:" + kind);
            createProfile();
            return true;
        }
        if ("profileLoad".equals(kind)) {
            pulse("btn:" + kind);
            loadProfile((String) target.payload);
            return true;
        }
        if ("profileSave".equals(kind)) {
            pulse("btn:" + kind);
            String name = (String) target.payload;
            AtlasProfiles.save(name);
            pulse("saved:" + name);
            this.profilesReadAt = 0L;
            this.unsavedAt = 0L;
            return true;
        }
        if ("profileDelete".equals(kind)) {
            final String name = (String) target.payload;
            pulse("btn:" + kind);
            this.dialog = new Dialog("Delete " + name + "?", "Deletes " + name + ".json from config/Atlas."
                    + " This cannot be undone.", new String[]{"Delete", "Cancel"}, new int[]{2, 1},
                    new Runnable[]{() -> {
                        if (AtlasProfiles.delete(name)) {
                            tell(Toasts.Kind.OK, "Deleted profile " + name);
                            this.profileSelected = null;
                        } else {
                            tell(Toasts.Kind.ERROR, "Could not delete " + name);
                        }
                        this.profilesReadAt = 0L;
                    }, null});
            return true;
        }
        if ("profileFolder".equals(kind)) {
            pulse("btn:" + kind);
            AtlasProfiles.openFolder();
            return true;
        }
        return false;
    }

    /** Whether the profile in use has changes its file does not, read at most once a second. */
    private boolean profileUnsaved() {
        long now = System.currentTimeMillis();
        if (now - this.unsavedAt > 1000L) {
            this.unsavedAt = now;
            this.unsaved = atlas.config.Config.differsFromFile(AtlasProfiles.current());
        }
        return this.unsaved;
    }

    /**
     * Loads a profile; with unsaved changes in the one in use, asks first
     * (2026-10-23: loading used to drop them without a word).
     */
    private void loadProfile(final String name) {
        final String current = AtlasProfiles.current();
        boolean changes = atlas.config.Config.differsFromFile(current);
        Runnable load = () -> {
            AtlasProfiles.load(name);
            this.unsavedAt = 0L;
            this.profilesReadAt = 0L;
        };
        if (!changes) {
            load.run();
            return;
        }
        if (name.equalsIgnoreCase(current)) {
            this.dialog = new Dialog("Reload " + name + "?",
                    "Your changes since it was last saved are lost.",
                    new String[]{"Reload", "Cancel"}, new int[]{2, 1}, new Runnable[]{load, null});
            return;
        }
        this.dialog = new Dialog("Save your changes first?",
                "You changed settings in " + current + " since it was saved. Loading " + name + " replaces them.",
                new String[]{"Save and load", "Load without saving", "Cancel"}, new int[]{0, 2, 1},
                new Runnable[]{() -> {
                    AtlasProfiles.save(current);
                    load.run();
                }, load, null});
    }

    /** The open dialog, over the whole window like the report dialog. */
    private void drawDialog(int x, int y, int width, int height, float mx, float my) {
        Dialog open = this.dialog;
        Liquid.rect(x, y, x + width, y + height, RADIUS, 0x99000000, 0xAA000000);
        hit("dialogOutside", null, x, y, x + width, y + height);
        float w = Math.min(340.0F, width - 20.0F);
        LiquidFont body = font(8.5F, false);
        List<String> lines = body.wrap(open.body, w - 36);
        float h = 70.0F + lines.size() * 11.0F;
        float bx = x + (width - w) / 2.0F;
        float by = y + (height - h) / 2.0F;
        Liquid.shadow(bx, by, bx + w, by + h, 16.0F, 18.0F, 0x80000000, 4.0F);
        Liquid.rect(bx, by, bx + w, by + h, 16.0F, atlas.ui.UiMode.isLight() ? 0xFAF6F8FB : 0xF2141820,
                atlas.ui.UiMode.isLight() ? 0xFAEEF1F5 : 0xF20D1016);
        Liquid.rim(bx, by, bx + w, by + h, 16.0F, 1.0F, ink(0x24), ink(0x10));
        hit("dialogBox", null, bx, by, bx + w, by + h);
        font(12.0F, true).draw(font(12.0F, true).trim(open.title, w - 36), bx + 18, by + 22, TEXT);
        float cursor = by + 40;
        for (String line : lines) {
            body.draw(line, bx + 18, cursor, DIM);
            cursor += 11;
        }
        LiquidFont bold = font(8.5F, true);
        float total = 0.0F;
        for (String label : open.labels) {
            total += bold.width(label) + 22 + 8;
        }
        float bxx = bx + w - 18 - (total - 8);
        float buttonsY = by + h - 20;
        for (int i = 0; i < open.labels.length; i++) {
            bxx += drawButton("dialog" + i, Integer.valueOf(i), bxx, buttonsY, 0.0F, open.labels[i], open.styles[i],
                    mx, my) + 8;
        }
        if (mx >= bx && mx <= bx + w && my >= by && my <= by + h) {
            this.hint = "Enter: " + open.labels[0] + "  ·  Esc: close";
        }
    }

    /** Saves the current setup under the typed name, and selects it. */
    private void createProfile() {
        String name = AtlasProfiles.clean(this.profileNameInput.text());
        if (name.isEmpty()) {
            tell(Toasts.Kind.WARN, "Type a name for the profile first");
            this.profileNameFocused = true;
            return;
        }
        AtlasProfiles.save(name);
        this.profileNameInput.clear();
        this.profileNameFocused = false;
        this.profileSelected = name;
        this.ui.set("profileArrive", Float.valueOf(0.0F));
        this.profilesReadAt = 0L;
        pulse("saved:" + name);
    }

    /**
     * Turns the centred default into explicit coordinates. Until the window
     * is moved it is drawn in the middle of whatever screen it finds itself
     * on; the moment it is dragged it needs a real number to drag from.
     */
    private void anchorWindow(ScaledResolution sr) {
        int width = windowWidth(sr);
        int height = windowHeight(sr);
        if (this.userX == UNSET) {
            this.userX = left(sr);
        }
        if (this.userY == UNSET) {
            this.userY = top(sr);
        }
        this.userWidth = width;
        this.userHeight = height;
    }

    /**
     * Carries on whatever the pointer is dragging, every frame.
     *
     * The game hands a screen its mouse events from the tick loop, twenty
     * times a second, so a drag driven only by mouseClickMove moved the
     * window and the sliders in twenty jumps a second however fast the frame
     * rate was -- which is what dragging the menu looked like. The position
     * passed to drawScreen is read every frame, so the drag follows that
     * instead, and ends as soon as the button is seen to be up.
     */
    private void followDrag(ScaledResolution sr, int mouseX, int mouseY) {
        if (!this.movingWindow && !this.resizingWindow && this.dragging == null && this.colorDrag == null) {
            return;
        }
        if (!org.lwjgl.input.Mouse.isButtonDown(this.dragButton)) {
            this.dragging = null;
            this.colorDrag = null;
            this.movingWindow = false;
            this.resizingWindow = false;
            return;
        }
        applyDrag(sr, mouseX, mouseY);
    }

    private void applyDrag(ScaledResolution sr, int mouseX, int mouseY) {
        if (this.movingWindow) {
            this.userX = Math.max(0, Math.min(sr.getScaledWidth() - windowWidth(sr),
                    mouseX - this.grabX));
            this.userY = Math.max(0, Math.min(sr.getScaledHeight() - windowHeight(sr),
                    mouseY - this.grabY));
            return;
        }
        if (this.resizingWindow) {
            this.userWidth = Math.max(MIN_WIDTH,
                    Math.min(sr.getScaledWidth() - 8, mouseX + this.grabX));
            this.userHeight = Math.max(MIN_HEIGHT,
                    Math.min(sr.getScaledHeight() - 8, mouseY + this.grabY));
            /* A window grown larger than the room left of it would otherwise
               be pushed back by the clamp in left(), which looks like the drag
               fighting the pointer. */
            this.userX = Math.max(0, Math.min(this.userX, sr.getScaledWidth() - this.userWidth));
            this.userY = Math.max(0, Math.min(this.userY, sr.getScaledHeight() - this.userHeight));
            return;
        }
        if (this.colorDrag != null) {
            colourDragTo(layoutX(mouseX), layoutY(mouseY));
            return;
        }
        dragTo(layoutX(mouseX));
    }

    private void dragTo(float mouseX) {
        if (this.dragging == null || this.dragWidth <= 0.0F) {
            return;
        }
        float fraction = (mouseX - this.dragX) / this.dragWidth;
        fraction = Math.max(0.0F, Math.min(1.0F, fraction));
        assign(this.dragging, this.dragMin + (this.dragMax - this.dragMin) * fraction);
    }

    @Override
    protected void mouseClickMove(int mouseX, int mouseY, int button, long held) {
        /* Still applied, so a drag works even between frames; the frame loop
           (followDrag) is what makes it smooth. */
        applyDrag(new ScaledResolution(mc), mouseX, mouseY);
    }

    @Override
    protected void mouseReleased(int mouseX, int mouseY, int state) {
        this.orbitPreview.mouseReleased();
        this.dragging = null;
        this.colorDrag = null;
        this.movingWindow = false;
        this.resizingWindow = false;
    }

    @Override
    public void handleMouseInput() throws java.io.IOException {
        super.handleMouseInput();
        int wheel = org.lwjgl.input.Mouse.getEventDWheel();
        if (wheel == 0) {
            return;
        }
        int step = wheel > 0 ? -1 : 1;
        if (this.dropdown != null) {
            /* An open list takes the wheel: it scrolls its options. */
            this.dropScroll = Math.max(0, Math.min(this.dropMaxScroll, this.dropScroll + step));
            return;
        }
        ScaledResolution sr = new ScaledResolution(mc);
        float rawX = org.lwjgl.input.Mouse.getEventX() * sr.getScaledWidth() / (float) mc.displayWidth;
        float rawY = sr.getScaledHeight() - org.lwjgl.input.Mouse.getEventY() * sr.getScaledHeight() / (float) mc.displayHeight - 1;
        if (this.orbitPreview.scroll(rawX, rawY, wheel)) {
            return;
        }
        float mouseX = layoutX(rawX);
        int width = windowWidth(sr);
        if (mouseX > left(sr) + width - detailWidth(width)) {
            /* Bounded by the content, measured when it was last drawn, so the
               pane cannot be scrolled into empty space. */
            this.detailScroll = Math.max(0, Math.min(this.detailMaxScroll, this.detailScroll + step * 16));
            /* An open list is anchored to a row that has just moved. */
            this.dropdown = null;
        } else if (mouseX < this.sidebarRight && !"Client Settings".equals(this.page)) {
            /* Over the category column: scroll the categories (2026-10-11). */
            this.sidebarScroll = Math.max(0.0F, Math.min(this.sidebarMaxScroll, this.sidebarScroll + step * CAT));
        } else if (this.appearanceView) {
            this.appearanceScroll = Math.max(0.0F, Math.min(this.appearanceMaxScroll, this.appearanceScroll + step * ROW));
        } else if (this.profilesView) {
            this.profileScroll = Math.max(0, Math.min(this.profileMaxScroll, this.profileScroll + step));
        } else {
            this.listScroll = Math.max(0, Math.min(this.listMaxScroll, this.listScroll + step));
        }
    }

    /**
     * Takes what was typed into a setting and applies it. Out of range is
     * clamped rather than refused: someone who types 9000 into a field that
     * stops at 5000 wants the largest value it has.
     */
    private void commitEdit() {
        Property<?> property = this.editing;
        this.editing = null;
        this.editError = false;
        if (property != null) {
            applyEdit(property);
        }
    }

    /**
     * Applies the typed text to the setting. False, with a toast saying why,
     * when it is not a value for it: a number that does not parse, or text the
     * setting's own check turns down (parseString returns false then; that used
     * to pass as applied).
     */
    private boolean applyEdit(Property<?> property) {
        String text = this.editInput.text().trim();
        if (text.isEmpty()) {
            return true;
        }
        boolean applied;
        try {
            if (isNumeric(property)) {
                assign(property, Float.parseFloat(text.replace("%", "")));
                applied = true;
            } else {
                applied = property.parseString(text);
            }
        } catch (Exception ignored) {
            applied = false;
        }
        if (applied) {
            pulse("edit:" + property.getName());
        } else {
            tell(Toasts.Kind.ERROR, "\"" + text + "\" is not a value for " + property.getLabel()
                    + " (" + property.getValuePrompt() + ")");
        }
        return applied;
    }

    @Override
    protected void keyTyped(char typed, int key) {
        if (this.dialog != null) {
            Dialog open = this.dialog;
            if (key == Keyboard.KEY_ESCAPE) {
                this.dialog = null;
            } else if (key == Keyboard.KEY_RETURN || key == Keyboard.KEY_NUMPADENTER) {
                this.dialog = null;
                if (open.actions[0] != null) {
                    open.actions[0].run();
                }
            }
            return;
        }
        if (this.reportConfirm) {
            TextInput.Result result = this.reportInput.key(typed, key, GuiScreen.isCtrlKeyDown(),
                    GuiScreen::getClipboardString);
            if (result == TextInput.Result.SUBMIT) {
                sendReport();
            } else if (result == TextInput.Result.CANCEL) {
                this.reportConfirm = false;
            }
            return;
        }
        if (this.binding != null) {
            /* Escape clears rather than cancels: wanting to remove a key is far
               commoner than changing one's mind about setting one (kept, owner
               2026-10-23). Another module on the same key is said, not refused. */
            Module bound = this.binding;
            this.binding = null;
            int newKey = key == Keyboard.KEY_ESCAPE ? 0 : key;
            bound.setKey(newKey);
            reportKey(bound.getName(), bound.getName(), newKey);
            return;
        }
        if (this.keyEditing != null) {
            /* Escape clears, as for a module's own key. */
            KeyProperty edited = this.keyEditing;
            this.keyEditing = null;
            int newKey = key == Keyboard.KEY_ESCAPE ? Keyboard.KEY_NONE : key;
            edited.setValue(Integer.valueOf(newKey));
            pulse("edit:" + edited.getName());
            String self = this.selected == null ? edited.getLabel()
                    : KeyConflicts.nameOf(this.selected, edited);
            reportKey(edited.getLabel(), self, newKey);
            return;
        }
        if (this.editing != null) {
            TextInput.Result result = this.editInput.key(typed, key, GuiScreen.isCtrlKeyDown(),
                    GuiScreen::getClipboardString);
            if (result == TextInput.Result.SUBMIT) {
                /* Something that is not a value keeps the field open, edged
                   red, to be corrected; a click elsewhere still gives up on it. */
                if (applyEdit(this.editing)) {
                    this.editing = null;
                    this.editError = false;
                } else {
                    this.editError = true;
                }
            } else if (result == TextInput.Result.CANCEL) {
                this.editing = null;
                this.editError = false;
            } else if (result == TextInput.Result.EDITED) {
                this.editError = false;
            }
            return;
        }
        if (this.dropdown != null && key == Keyboard.KEY_ESCAPE) {
            this.dropdown = null;
            return;
        }
        if (this.profileNameFocused) {
            TextInput.Result result = this.profileNameInput.key(typed, key, GuiScreen.isCtrlKeyDown(),
                    GuiScreen::getClipboardString);
            if (result == TextInput.Result.SUBMIT) {
                createProfile();
            } else if (result == TextInput.Result.CANCEL) {
                this.profileNameFocused = false;
            }
            return;
        }
        /* Client Settings has no search to type into -- but Escape still
           closes the menu, as on every other page. It used to return here for
           every key, Escape included, so the menu could not be closed from it
           (2026-10-04). */
        if ("Client Settings".equals(this.page) && !this.searchFocused && key != Keyboard.KEY_ESCAPE) {
            return;
        }
        /* The menu's own commands live behind Control rather than on the
           function keys: F11 is the game's fullscreen toggle and is handled in
           the frame loop, F3 is the debug prefix, F5 the camera, F1 the HUD.
           Control plus a letter is untouched inside a screen, and holding
           Control keeps the character out of the search box as well. */
        if (GuiScreen.isCtrlKeyDown()) {
            if (this.searchFocused && (key == Keyboard.KEY_V || key == Keyboard.KEY_A)) {
                editSearch(typed, key, true);
            } else if (key == Keyboard.KEY_L) {
                if (AtlasInspector.isRecording()) {
                    say(AtlasInspector.report());
                } else {
                    AtlasInspector.check();
                    say("checking layout -- press Ctrl+L again");
                }
            } else if (key == Keyboard.KEY_K) {
                /* So the menu can be looked at without asking anyone to take a
                   screenshot and describe it. */
                say(AtlasInspector.capture());
            } else if (key == Keyboard.KEY_I) {
                say(AtlasInspector.toggleLive());
            } else if (key == Keyboard.KEY_R) {
                this.userWidth = DEFAULT_WIDTH;
                this.userHeight = DEFAULT_HEIGHT;
                this.userX = UNSET;
                this.userY = UNSET;
                tell(Toasts.Kind.OK, "Window reset to " + DEFAULT_WIDTH + "x" + DEFAULT_HEIGHT + ", centred");
            } else if (key == Keyboard.KEY_P) {
                this.perf = !this.perf;
            } else if (key == Keyboard.KEY_H && this.selected != null) {
                this.selected.setHidden(!this.selected.isHidden());
                pulse("chip:hide");
            }
            return;
        }
        if (key == Keyboard.KEY_ESCAPE) {
            if (!this.searchInput.isEmpty()) {
                /* Escape clears the search before it closes the menu: losing a
                   filter is a smaller surprise than losing the menu. */
                this.searchInput.clear();
                this.listScroll = 0;
                alignCategory();
                return;
            }
            mc.displayGuiScreen(null);
            return;
        }
        /* Any printable character starts a search, wherever focus nominally
           is. Having to click a box first is what stops people searching. */
        boolean printable = typed >= 32 && typed != 127;
        if (this.searchFocused || printable || key == Keyboard.KEY_BACK) {
            editSearch(typed, key, false);
        }
    }

    /**
     * A key for the search box. Without the box focused, typing and Backspace
     * work at the end of the search, as they always did; with it focused the
     * caret moves too.
     */
    private void editSearch(char typed, int key, boolean ctrl) {
        if (!this.searchFocused) {
            this.searchInput.toEnd();
        }
        String before = this.searchInput.text();
        TextInput.Result result = this.searchInput.key(typed, key, ctrl, GuiScreen::getClipboardString);
        if (result == TextInput.Result.SUBMIT) {
            openFirstResult();
            return;
        }
        if (result != TextInput.Result.EDITED || this.searchInput.text().equals(before)) {
            return;
        }
        this.listScroll = 0;
        if (this.searchInput.isEmpty()) {
            alignCategory();
        } else {
            /* A search is of modules, so it leaves the profiles and appearance views. */
            this.profilesView = false;
            this.appearanceView = false;
            this.serversView = false;
            this.searchFocused = true;
        }
    }

    /** What is typed in the search box. */
    private String search() {
        return this.searchInput.text();
    }

    private void say(String message) {
        atlas.util.ChatUtil.sendFormatted("&7[&bAtlas&7] &f" + message);
    }

    @Override
    public void initGui() {
        atlas.ui.GuiPerf.step("before-init");
        loadState();
        atlas.ui.GuiPerf.step("load");
        build();
        /* Names and help do not change in a session, so the index is built
           once, and again only if a module is added (a script). */
        if (Atlas.moduleManager.modules.size() != this.indexedModules) {
            buildIndex();
            this.indexedModules = Atlas.moduleManager.modules.size();
        }
        this.searchedFor = null;
        atlas.ui.GuiPerf.step("build");
        this.binding = null;
        this.keyEditing = null;
        this.colorDrag = null;
        this.editing = null;
        this.editError = false;
        this.closing = false;
        this.dialog = null;
        this.unsavedAt = 0L;
        this.toasts.clear();
        this.infoOpen = null;
        this.descriptionOpen = null;
        this.dropdown = null;
        this.dragging = null;
        this.movingWindow = false;
        this.resizingWindow = false;
        this.searchFocused = false;
        this.profileNameFocused = false;
        this.profilesReadAt = 0L;
        /* The filter is per-visit; the category and the selected module are
           not, which is the difference between "where I was" and "what I was
           looking for at the time". */
        this.searchInput.clear();
        this.listScroll = 0;
        this.openedFor = 0.0F;
        this.open.snap(0.92F);
        applyTheme();
        paintPalette();
        atlas.ui.GuiPerf.step("theme");
        alignCategory();
        this.lastFrame = 0L;
        this.ui.reset();
        this.lenses.clear();
        /* Every size the menu draws, built now rather than on the first frame
           that needs it, where building them would stall the opening. */
        Liquid.refreshScale();
        float[][] faces = {{7.0F, 1}, {7.0F, 0}, {7.5F, 0}, {8.0F, 0}, {8.0F, 1}, {8.5F, 0}, {8.5F, 1},
                {9.0F, 0}, {9.5F, 0}, {9.5F, 1}, {12.5F, 0}, {12.5F, 1}, {14.0F, 1}, {15.0F, 1}};
        for (float[] face : faces) {
            LiquidFont.of(face[0], face[1] > 0.5F);
        }
        atlas.ui.GuiPerf.step("fonts");
    }

    /**
     * The middle list and the detail pane show the same category (2026-10-17).
     * A module picked from search results, or the one remembered from last
     * time, could stay in the detail pane while the sidebar showed another
     * category: KeepRange's settings under "Exploit". The sidebar follows the
     * selection instead of dropping it.
     */
    private void alignCategory() {
        if (this.selected == null || !search().trim().isEmpty() || this.profilesView || this.appearanceView
                || this.serversView) {
            return;
        }
        Map<String, List<Module>> lists = "Legit".equals(this.page) ? this.legitGroups : this.byCategory;
        List<Module> here = isQuick(this.category) ? modulesOf(this.category) : lists.get(this.category);
        if (here != null && here.contains(this.selected)) {
            return;
        }
        for (Map.Entry<String, List<Module>> entry : lists.entrySet()) {
            if (entry.getValue().contains(this.selected) && !("Modules".equals(this.page) && "Legit".equals(entry.getKey()))) {
                this.category = entry.getKey();
                this.listScroll = 0;
                return;
            }
        }
    }

    @Override
    public void onGuiClosed() {
        /* Anything half-typed counts, so closing the menu is not a way to lose
           a number that was already entered. A problem with it goes to chat:
           a toast would never be seen now. */
        this.closing = true;
        commitEdit();
        this.dragging = null;
        this.movingWindow = false;
        this.resizingWindow = false;
        this.dropdown = null;
        saveState();
        this.theme.save();
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}
