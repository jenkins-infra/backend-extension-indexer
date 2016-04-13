package org.jenkinsci.extension_indexer;

import net.sf.json.JSONObject;

/**
 * Action summary to be used by {@link ExtensionPointListGenerator} to serialize action in to JSON
 *
 * @author Vivek Pandey
 */
public class ActionSummary {
    public final String action;
    public final JSONObject json;
    public boolean hasView;
    public ActionSummary(Action action) {
        this.action = action.getImplementationName();
        this.hasView = action.hasView();
        this.json = action.toJSON();
    }
}
