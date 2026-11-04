package atlas.script.raven.model;

import java.util.ArrayList;
import java.util.List;

/** Raven script API: an HTTP answer (2026-11-02). */
public class Response {
    private final int responseCode;
    private final String contents;
    private final List<String[]> headers;

    public Response(int responseCode, String contents, List<String[]> headers) {
        this.responseCode = responseCode;
        this.contents = contents == null ? "" : contents;
        this.headers = headers == null ? new ArrayList<String[]>() : headers;
    }

    public Response(int responseCode, String contents) {
        this(responseCode, contents, null);
    }

    public int code() {
        return this.responseCode;
    }

    public String string() {
        return this.contents;
    }

    public Json json() {
        return Json.parse(this.contents);
    }

    public List<String[]> headers() {
        return this.headers;
    }

    @Override
    public String toString() {
        return "Response(" + this.responseCode + ")";
    }
}
