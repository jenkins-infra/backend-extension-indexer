package org.jenkinsci.extension_indexer;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;

import net.sf.json.JSONArray;
import net.sf.json.JSONObject;

/**
 * Information about the module that we scanned extensions.
 */
abstract class Module implements Comparable<Module> {
    final String gav;

    final String artifactId;
    final String version;
    final String group;

    final String url;
    final String displayName;
    /**
     * Extension point or extensions that are found inside this module.
     */
    final List<ExtensionSummary> extensions = new ArrayList<>();
    /**
     * Actions that are found inside this module.
     */
    final List<ActionSummary> actions = new ArrayList<>();

    private static final String JENKINS_CORE_URL_NAME = "jenkins-core";

    private static String repositoryOrigin = "";

    public static String getRepositoryOrigin() {
        if (repositoryOrigin.isEmpty()){
            // Retrieve the env var containing the artifact caching proxy origin
            // or use the default https://repo.jenkins-ci.org origin
            repositoryOrigin = (System.getenv("ARTIFACT_CACHING_PROXY_ORIGIN") != null) ? System.getenv("ARTIFACT_CACHING_PROXY_ORIGIN") : "https://repo.jenkins-ci.org";
        }
        return repositoryOrigin;
    }

    protected Module(String gav, String url, String displayName) {
        this.gav = gav;
        this.url = url;
        this.displayName = simplifyDisplayName(displayName);

        String[] splitGav = gav.split(":", 3);
        this.group = splitGav[0];
        this.artifactId = splitGav[1];
        this.version = splitGav[2];
    }

    protected String simplifyDisplayName(String displayName) {
        if (displayName.equals("Jenkins Core")) {
            return displayName;
        }
        displayName = StringUtils.removeStartIgnoreCase(displayName, "Jenkins ");
        displayName = StringUtils.removeStartIgnoreCase(displayName, "Hudson ");
        displayName = StringUtils.removeEndIgnoreCase(displayName, " for Jenkins");
        displayName = StringUtils.removeEndIgnoreCase(displayName, " Plugin");
        displayName = StringUtils.removeEndIgnoreCase(displayName, " Plug-In");
        displayName = displayName + " Plugin"; // standardize spelling
        return displayName;
    }

    /**
     * Returns an Asciidoc (jenkins.io flavor) formatted link to point to this module.
     */
    abstract String getFormattedLink();

    abstract String getUrlName();

    public URL getSourcesUrl() throws MalformedURLException {
        return new URL(getRepositoryOrigin() + "/releases/" + group.replaceAll("\\.", "/") + "/" + artifactId + "/" + version + "/" + artifactId + "-" + version + "-sources.jar");
    }

    public URL getResolvedPomUrl() throws MalformedURLException {
        return new URL(getRepositoryOrigin() + "/releases/" + group.replaceAll("\\.", "/") + "/" + artifactId + "/" + version + "/" + artifactId + "-" + version + ".pom");
    }

    JSONObject toJSON() {
        JSONObject o = new JSONObject();
        o.put("gav",gav);
        o.put("url",url);
        o.put("displayName",displayName);

        Set<ExtensionSummary> defs = new HashSet<>();

        JSONArray extensions = new JSONArray();
        JSONArray actions = new JSONArray();
        JSONArray extensionPoints = new JSONArray();
        int viewCount=0;
        for (ExtensionSummary es : this.extensions) {
            (es.isDefinition ? extensionPoints : extensions).add(es.json);
            defs.add(es.family.definition);

            if(es.hasView){
                viewCount++;
            }
        }

        for(ActionSummary action:this.actions){
            JSONObject jsonObject = action.json;
            actions.add(jsonObject);
            if(action.hasView){
                viewCount++;
            }
        }

        if(actions.size() > 0 || extensions.size() > 0) {
            double viewScore = (double) viewCount / (extensions.size() + actions.size());
            o.put("viewScore", Double.parseDouble(String.format("%.2f", viewScore)));
        }

        o.put("extensions",extensions);     // extensions defined in this module
        o.put("extensionPoints",extensionPoints);   // extension points defined in this module
        o.put("actions", actions); // actions implemented in this module

        JSONArray uses = new JSONArray();
        for (ExtensionSummary es : defs) {
            if (es==null)   continue;
            uses.add(es.json);
        }
        o.put("uses", uses);    // extension points that this module consumes

        return o;
    }

    @Override
    public int compareTo(Module o) {
        String self = this.getUrlName();
        String other = o.getUrlName();

        if (other.equals(JENKINS_CORE_URL_NAME) || self.equals(JENKINS_CORE_URL_NAME)) {
            return self.equals(JENKINS_CORE_URL_NAME) ? (other.equals(JENKINS_CORE_URL_NAME) ? 0 : -1 ) : 1;
        } else {
            return this.displayName.compareToIgnoreCase(o.displayName);
        }
    }


    public static class PluginModule extends Module {
        public final String scm;
        protected PluginModule(String gav, String url, String displayName, String scm) {
            super(gav, url, displayName);
            this.scm = scm;
        }

        @Override
        String getFormattedLink() {
            return "plugin:" + artifactId + "[" + displayName + "]";
        }

        @Override
        String getUrlName() {
            return artifactId;
        }
    }

    public static class CoreModule extends Module {
        public CoreModule(String version) {
            super("org.jenkins-ci.main:jenkins-core:" + version, "http://github.com/jenkinsci/jenkins/", "Jenkins Core");
        }

        @Override
        String getFormattedLink() {
            // TODO different target
            return "link:https://github.com/jenkinsci/jenkins/[Jenkins Core]";
        }

        @Override
        String getUrlName() {
            return JENKINS_CORE_URL_NAME;
        }
    }

}
