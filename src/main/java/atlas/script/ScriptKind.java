package atlas.script;

/**
 * The API a script file is written against (2026-11-03, owner: "make a bS 16 and a bS 17 separately"). Raven bS 16
 * and bS 17 share most of their API but not all of it -- bS 17 adds 71 methods, new hooks (onKey, onMouse with the
 * pointer, the model render hooks, getSuffix) and calls some hooks differently -- so a script runs as the
 * version it was written for. Atlas scripts get bS 17's API and Atlas's own.
 */
public enum ScriptKind {
    ATLAS("Atlas", "", "atlas.script.api.AtlasScript", "atlas.script.raven.Raven17Script"),
    RAVEN16("Raven bS 16", "raven16", "atlas.script.raven.RavenScript", "atlas.script.raven.RavenScript"),
    RAVEN17("Raven bS 17", "raven17", "atlas.script.raven.Raven17Script", "atlas.script.raven.Raven17Script");

    public final String label;
    /** Its folder under config/Atlas/scripts ("" for the folder itself). */
    public final String folder;
    /** The class its scripts extend. */
    final String base;
    /** The class whose nested "modules" type its scripts' modules field has. */
    final String modulesOwner;

    ScriptKind(String label, String folder, String base, String modulesOwner) {
        this.label = label;
        this.folder = folder;
        this.base = base;
        this.modulesOwner = modulesOwner;
    }

    /** bS 17's hooks and behaviour (Atlas scripts too). */
    public boolean bs17() {
        return this != RAVEN16;
    }

    public boolean raven() {
        return this != ATLAS;
    }
}
