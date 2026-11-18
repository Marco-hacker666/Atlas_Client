package atlas.script;

public enum ScriptKind {
    ATLAS("Atlas", "", "atlas.script.api.AtlasScript", "atlas.script.raven.Raven17Script"),
    RAVEN16("Raven bS 16", "raven16", "atlas.script.raven.RavenScript", "atlas.script.raven.RavenScript"),
    RAVEN17("Raven bS 17", "raven17", "atlas.script.raven.Raven17Script", "atlas.script.raven.Raven17Script");

    public final String label;

    public final String folder;

    final String base;

    final String modulesOwner;

    ScriptKind(String label, String folder, String base, String modulesOwner) {
        this.label = label;
        this.folder = folder;
        this.base = base;
        this.modulesOwner = modulesOwner;
    }

    public boolean bs17() {
        return this != RAVEN16;
    }

    public boolean raven() {
        return this != ATLAS;
    }
}
