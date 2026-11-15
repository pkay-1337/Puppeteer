package uq.script;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Declarative parameter schema for UQ Shell scripts (FR-PARAM-1).
 * Completely generic and dynamic: supports primitives (boolean, number, text, choice),
 * app selection (app_singleselect, app_multiselect), and per-app dynamic maps (app_map).
 */
public class ScriptSchema {

    public static class Param {
        public final String id;
        public final String type;
        public final String title;
        public final String description;
        public final Object defaultValue;
        public final int min;
        public final int max;
        public final String[] options;
        public final List<Param> fields;

        public Param(String id, String type, String title, String description,
                     Object defaultValue, int min, int max, String[] options, List<Param> fields) {
            this.id = id;
            this.type = type;
            this.title = title;
            this.description = description;
            this.defaultValue = defaultValue;
            this.min = min;
            this.max = max;
            this.options = options;
            this.fields = fields != null ? fields : new ArrayList<Param>();
        }

        public JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("id", id);
                o.put("type", type);
                o.put("title", title);
                o.put("description", description != null ? description : "");
                if (defaultValue != null) {
                    if (defaultValue instanceof List) {
                        JSONArray a = new JSONArray();
                        for (Object item : (List<?>) defaultValue) a.put(item);
                        o.put("defaultValue", a);
                    } else {
                        o.put("defaultValue", defaultValue);
                    }
                }
                if ("number".equals(type)) {
                    o.put("min", min);
                    o.put("max", max);
                }
                if (options != null) {
                    JSONArray opts = new JSONArray();
                    for (String s : options) opts.put(s);
                    o.put("options", opts);
                }
                if (fields != null && !fields.isEmpty()) {
                    JSONArray fa = new JSONArray();
                    for (Param fp : fields) fa.put(fp.toJson());
                    o.put("fields", fa);
                }
            } catch (Throwable ignored) {}
            return o;
        }
    }

    public static class AppMapBuilder {
        private final String id;
        private final String title;
        private final String description;
        private final List<Param> fields = new ArrayList<Param>();

        public AppMapBuilder(String id, String title, String description) {
            this.id = id;
            this.title = title;
            this.description = description;
        }

        public AppMapBuilder addNumber(String fieldId, String fieldTitle, int defaultValue, int min, int max) {
            fields.add(new Param(fieldId, "number", fieldTitle, "", Integer.valueOf(defaultValue), min, max, null, null));
            return this;
        }

        public AppMapBuilder addBoolean(String fieldId, String fieldTitle, boolean defaultValue) {
            fields.add(new Param(fieldId, "boolean", fieldTitle, "", Boolean.valueOf(defaultValue), 0, 0, null, null));
            return this;
        }

        public AppMapBuilder addChoice(String fieldId, String fieldTitle, String defaultValue, String[] options) {
            fields.add(new Param(fieldId, "choice", fieldTitle, "", defaultValue, 0, 0, options, null));
            return this;
        }

        public AppMapBuilder addText(String fieldId, String fieldTitle, String defaultValue) {
            fields.add(new Param(fieldId, "text", fieldTitle, "", defaultValue != null ? defaultValue : "", 0, 0, null, null));
            return this;
        }

        public Param build() {
            return new Param(id, "app_map", title, description, new JSONObject(), 0, 0, null, fields);
        }
    }

    private final List<Param> params = new ArrayList<Param>();

    public ScriptSchema addAppMultiSelect(String id, String title, String description) {
        params.add(new Param(id, "app_multiselect", title, description, new ArrayList<String>(), 0, 0, null, null));
        return this;
    }

    public ScriptSchema addAppSingleSelect(String id, String title, String description, String defaultPkg) {
        params.add(new Param(id, "app_singleselect", title, description, defaultPkg, 0, 0, null, null));
        return this;
    }

    public ScriptSchema addBoolean(String id, String title, String description, boolean defaultValue) {
        params.add(new Param(id, "boolean", title, description, Boolean.valueOf(defaultValue), 0, 0, null, null));
        return this;
    }

    public ScriptSchema addNumber(String id, String title, String description, int defaultValue, int min, int max) {
        params.add(new Param(id, "number", title, description, Integer.valueOf(defaultValue), min, max, null, null));
        return this;
    }

    public ScriptSchema addText(String id, String title, String description, String defaultValue) {
        params.add(new Param(id, "text", title, description, defaultValue != null ? defaultValue : "", 0, 0, null, null));
        return this;
    }

    public ScriptSchema addChoice(String id, String title, String description, String defaultValue, String[] options) {
        params.add(new Param(id, "choice", title, description, defaultValue, 0, 0, options, null));
        return this;
    }

    public ScriptSchema addAppMap(AppMapBuilder builder) {
        if (builder != null) params.add(builder.build());
        return this;
    }

    public List<Param> getParams() {
        return params;
    }

    public JSONArray toJson() {
        JSONArray arr = new JSONArray();
        for (Param p : params) {
            arr.put(p.toJson());
        }
        return arr;
    }
}
