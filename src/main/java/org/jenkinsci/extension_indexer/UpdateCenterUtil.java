package org.jenkinsci.extension_indexer;

import net.sf.json.JSONException;
import net.sf.json.JSONObject;
import org.apache.commons.io.IOUtils;

import java.io.IOException;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;

/**
 * Loads update center metadata for use in this tool.
 *
 * @author Daniel Beck
 */
public class UpdateCenterUtil {
    private static Map<String, String> pluginToSourceUrlMap = new HashMap<>();

    static {
        try {
            URL url = new URL("http://updates.jenkins.io/update-center.actual.json");
            String json = IOUtils.toString(url.openStream());
            JSONObject o = JSONObject.fromObject(json);
            JSONObject plugins = o.getJSONObject("plugins");
            for (Object key : plugins.keySet()) {
                try {
                    String plugin = key.toString();
                    JSONObject value = plugins.getJSONObject(plugin);
                    String scm = value.getString("scm");
                    pluginToSourceUrlMap.put(plugin, scm);
                } catch (JSONException e) {
                    // no 'scm' element, expected in some cases
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static String getScmUrlForPlugin(String plugin) {
        return pluginToSourceUrlMap.get(plugin);
    }
}
