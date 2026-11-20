package atlas.mommy;

import atlas.property.properties.TextProperty;
import com.google.gson.JsonObject;

import java.io.File;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;

public final class CommandProperty extends TextProperty {
    private final IntSupplier shell;
    private int cachedShell = -1;
    private long cachedAt = -1L;
    private String cached = "";

    public CommandProperty(String name, IntSupplier shell, BooleanSupplier visible) {
        super(name, "", visible);
        this.shell = shell;
    }

    @Override
    public String getValue() {
        int s = this.shell.getAsInt();
        File f = PanicSetup.customFile(s);
        long at = f.isFile() ? f.lastModified() : 0L;
        if (s != this.cachedShell || at != this.cachedAt) {
            this.cachedShell = s;
            this.cachedAt = at;
            this.cached = at == 0L ? "" : PanicSetup.firstCommand(s);
        }
        return this.cached;
    }

    @Override
    public boolean setValue(Object object) {
        if (atlas.config.Config.loading || !(object instanceof String)) {
            return true;
        }
        try {
            PanicSetup.setFirstCommand(this.shell.getAsInt(), (String) object);
            this.cachedAt = -1L;
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public boolean read(JsonObject jsonObject) {
        return true;
    }

    @Override
    public void write(JsonObject jsonObject) {
    }

    @Override
    public String formatValue() {
        String v = this.getValue();
        return v.isEmpty() ? "&7(none)" : "&f" + v;
    }
}
