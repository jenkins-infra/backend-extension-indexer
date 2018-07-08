package org.jenkinsci.extension_indexer;

import hudson.util.VersionNumber;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.jvnet.hudson.update_center.HudsonWar;
import org.jvnet.hudson.update_center.MavenArtifact;
import org.jvnet.hudson.update_center.MavenRepository;
import org.jvnet.hudson.update_center.MavenRepositoryImpl;
import org.jvnet.hudson.update_center.Plugin;
import org.jvnet.hudson.update_center.PluginHistory;
import org.kohsuke.args4j.Argument;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Command-line tool to list up extension points and their implementations into a JSON file.
 *
 * @author Kohsuke Kawaguchi
 */
@SuppressWarnings("Since15")
public class ExtensionPointListGenerator {
    /**
     * All known {@link Family}s keyed by {@link Family#definition}'s FQCN.
     */
    private final Map<String,Family> families = new HashMap<String,Family>();
    /**
     * All the modules we scanned keyed by its {@link Module#artifact}
     */
    private final Map<MavenArtifact,Module> modules = Collections.synchronizedMap(new HashMap<MavenArtifact,Module>());

    @Option(name="-adoc",usage="Generate the extension list index and write it out to the specified directory.")
    public File asciidocOutputDir;

    @Option(name="-sorcerer",usage="Generate sorcerer reports")
    public File sorcererDir;

    @Option(name="-json",usage="Generate extension points, implementatoins, and their relationships in JSON")
    public File jsonFile;

    @Option(name="-core",usage="Core version to use. If not set, default to newest")
    public String coreVersion;
    
    @Option(name="-plugins",usage="Collect *.hpi/jpi into this directory")
    public File plugins;

    @Argument
    public List<String> args = new ArrayList<String>();

    private ExtensionPointsExtractor extractor = new ExtensionPointsExtractor();
    private SorcererGenerator sorcererGenerator = new SorcererGenerator();

    private static final String JENKINS_CORE_URL_NAME = "jenkins-core";

    private Comparator<ExtensionSummary> IMPLEMENTATION_SORTER = new Comparator<ExtensionSummary>() {
        @Override
        public int compare(ExtensionSummary o1, ExtensionSummary o2) {
            int moduleOrder = modules.get(o1.artifact).compareTo(modules.get(o2.artifact));
            if (moduleOrder != 0) {
                return moduleOrder;
            }
            if (o1.className == null || o2.className == null) {
                return o1.className == null ? (o2.className == null ? 0 : 1) : -1;
            }
            return o1.className.compareTo(o2.className);
        }
    };

    /**
     * Relationship between definition and implementations of the extension points.
     */
    public class Family implements Comparable {
        // from definition
        ExtensionSummary definition;
        private final SortedSet<ExtensionSummary> implementations = new TreeSet<>(IMPLEMENTATION_SORTER);

        public String getName() {
            return definition.extensionPoint;
        }

        public String getShortName() {
            return definition.className;
        }

        void formatAsAsciidoc(PrintWriter w) {
            w.println();
            w.println("## " + getShortName().replace(".", ".+++<wbr/>+++"));
            if ("jenkins-core".equals(definition.artifact.artifact.artifactId)) {
                w.println("`jenkinsdoc:" + definition.extensionPoint + "[]`");
            } else {
                w.println("`jenkinsdoc:" + definition.artifact.artifact.artifactId + ":" + definition.extensionPoint + "[]`");
            }
            w.println();
            w.println(definition.documentation == null || formatJavadoc(definition.documentation).trim().isEmpty() ? "_This extension point has no Javadoc documentation._" : formatJavadoc(definition.documentation));
            w.println();
            w.println("**Implementations:**");
            w.println();
            for (ExtensionSummary e : implementations) {
                w.print("* " + modules.get(e.artifact).getFormattedLink() + ": ");
                if (e.implementation == null || e.implementation.trim().isEmpty()) {
                    w.print("Anonymous class in " + (e.packageName + ".**" + e.topLevelClassName).replace(".", ".+++<wbr/>+++") + "**");
                } else {
                    w.print((e.packageName + ".**" + e.className + "**").replace(".", ".+++<wbr/>+++"));
                }
                w.println(" " + getSourceReference(e));
            }
            if (implementations.isEmpty())
                w.println("_(no known implementations)_");
            w.println();
        }

        public String getSourceReference(ExtensionSummary e) {
            String artifactId = e.artifact.artifact.artifactId;
            if (artifactId.equals("jenkins-core")) {
                return "(link:https://github.com/jenkinsci/jenkins/blob/master/core/src/main/java/" + e.packageName.replace(".", "/") + "/" + e.topLevelClassName + ".java" + "[view on GitHub])";
            } else {
                String scmUrl = UpdateCenterUtil.getScmUrlForPlugin(artifactId);
                if (scmUrl != null) {
                    if (scmUrl.contains("github.com")) { // should be limited to GitHub URLs, but best to be safe
                        return "(link:" + scmUrl + "/search?q=" + e.className + "&type=Code[view on GitHub])";
                    }
                }
            }
            return "";
        }

        private String formatJavadoc(String javadoc) {
            if (javadoc == null || javadoc.trim().isEmpty()) {
                return "";
            }
            StringBuilder formatted = new StringBuilder();

            for (String line : javadoc.split("\n")) {
                line = line.trim();
                if (line.startsWith("@author")) {
                    continue;
                }
                if (line.startsWith("@since")) {
                    continue;
                }
                formatted.append(line + "\n");
            }

            return formatted.toString();
        }

        private String getModuleLink(ExtensionSummary e) {
            final Module m = modules.get(e.artifact);
            if (m==null)
                throw new IllegalStateException("Unable to find module for "+e.artifact);
            return m.getFormattedLink();
        }

        public int compareTo(Object that) {
            return this.getShortName().compareTo(((Family)that).getShortName());
        }
    }

    /**
     * Information about the module that we scanned extensions.
     */
    abstract class Module implements Comparable<Module> {
        final MavenArtifact artifact;
        final String url;
        final String displayName;
        /**
         * Extension point or extensions that are found inside this module.
         */
        final List<ExtensionSummary> extensions = new ArrayList<ExtensionSummary>();
        /**
         * Actions that are found inside this module.
         */
        final List<ActionSummary> actions = new ArrayList<ActionSummary>();

        protected Module(MavenArtifact artifact, String url, String displayName) {
            this.artifact = artifact;
            this.url = url;
            this.displayName = simplifyDisplayName(displayName);
        }

        private String simplifyDisplayName(String displayName) {
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

        JSONObject toJSON() {
            JSONObject o = new JSONObject();
            o.put("gav",artifact.getGavId());
            o.put("url",url);
            o.put("displayName",displayName);

            Set<ExtensionSummary> defs = new HashSet<ExtensionSummary>();

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


    }

    private Module addModule(Module m) {
        modules.put(m.artifact,m);
        return m;
    }

    public static void main(String[] args) throws Exception {
        ExtensionPointListGenerator app = new ExtensionPointListGenerator();
        CmdLineParser p = new CmdLineParser(app);
        p.parseArgument(args);
        app.run();
    }

    public void run() throws Exception {
        if (asciidocOutputDir ==null && sorcererDir==null && jsonFile==null && plugins ==null)
            throw new IllegalStateException("Nothing to do. Either -adoc, -json, -sorcerer, or -pipeline is needed");

        MavenRepositoryImpl r = new MavenRepositoryImpl();
        r.addRemoteRepository("public",
                new URL("http://repo.jenkins-ci.org/public/"));

        HudsonWar war;
        if (coreVersion == null) {
            war = r.getHudsonWar().firstEntry().getValue();
        } else {
            war = r.getHudsonWar().get(new VersionNumber(coreVersion));
        }
        discover(addModule(new Module(war.getCoreArtifact(),"http://github.com/jenkinsci/jenkins/","Jenkins Core") {
            @Override
            String getFormattedLink() {
                // TODO different target
                return "link:https://github.com/jenkinsci/jenkins/[Jenkins Core]";
            }

            @Override
            String getUrlName() {
                return JENKINS_CORE_URL_NAME;
            }
        }));

        processPlugins(r);

        if (jsonFile!=null) {
            JSONObject all = new JSONObject();
            for (Family f : families.values()) {
                if (f.definition==null)     continue;   // skip undefined extension points
                JSONObject o = JSONObject.fromObject(f.definition.json);

                JSONArray use = new JSONArray();
                for (ExtensionSummary impl : f.implementations)
                    use.add(impl.json);
                o.put("implementations", use);

                all.put(f.getName(),o);
            }

            // this object captures information about modules where extensions are defined/found.
            final JSONObject artifacts = new JSONObject();
            for (Module m : modules.values()) {
                artifacts.put(m.artifact.getGavId(),m.toJSON());
            }

            JSONObject container = new JSONObject();
            container.put("extensionPoints",all);
            container.put("artifacts",artifacts);

            FileUtils.writeStringToFile(jsonFile, container.toString(2));
        }

        if (asciidocOutputDir !=null) {
            generateAsciidocReport();
        }
    }

    private MavenRepositoryImpl createRepository() throws Exception {
        MavenRepositoryImpl r = new MavenRepositoryImpl();
        r.addRemoteRepository("java.net2",
                new File("updates.jenkins-ci.org"),
                new URL("http://maven.glassfish.org/content/groups/public/"));
        return r;
    }
    
    /**
     * Walks over the plugins, record {@link #modules} and call {@link #discover(Module)}.
     */
    private void processPlugins(MavenRepository r) throws Exception {
        ExecutorService svc = Executors.newFixedThreadPool(1);
        try {
            Set<Future> futures = new HashSet<Future>();
            for (final PluginHistory p : new ArrayList<PluginHistory>(r.listHudsonPlugins())/*.subList(0,200)*/) {
                if (!args.isEmpty()) {
                    if (!args.contains(p.artifactId))
                        continue;   // skip
                } else if ("python-wrapper".equals(p.artifactId)) {
                    // python-wrapper does not have extension points but just wrappers to help python plugins use extension points
                    // see https://issues.jenkins-ci.org/browse/INFRA-516
                    continue;   // skip them to remove noise
                }
                futures.add(svc.submit(new Runnable() {
                    public void run() {
                        try {
                            System.out.println(p.artifactId);
                            if (asciidocOutputDir !=null || jsonFile!=null) {
                                Plugin pi = new Plugin(p);
                                discover(addModule(new Module(p.latest(), pi.getPluginUrl(), pi.getName()) {
                                    @Override
                                    String getFormattedLink() {
                                        return "plugin:" + artifact.artifact.artifactId + "[" + displayName + "]";
                                    }

                                    @Override
                                    String getUrlName() {
                                        return artifact.artifact.artifactId;
                                    }
                                }));
                            }
                            if (plugins!=null) {
                                File hpi = p.latest().resolve();
                                FileUtils.copyFile(hpi, new File(plugins, hpi.getName()));
                            }
                        } catch (Exception e) {
                            System.err.println("Failed to process "+p.artifactId);
                            // TODO record problem with this plugin so we can report on it
                            e.printStackTrace();
                        }
                    }
                }));
            }
            for (Future f : futures) {
                f.get();
            }
        } finally {
            svc.shutdown();
        }
    }

    private void generateAsciidocReport() throws IOException {
        Map<Module,List<Family>> byModule = new TreeMap<>();
        for (Family f : families.values()) {
            if (f.definition==null)     continue;   // skip undefined extension points

            Module key = modules.get(f.definition.artifact);
            List<Family> value = byModule.get(key);
            if (value==null)    byModule.put(key,value=new ArrayList<Family>());
            value.add(f);
        }

        asciidocOutputDir.mkdirs();

        try (PrintWriter w = new PrintWriter(new File(asciidocOutputDir, "index.adoc"))) {
            IOUtils.copy(new InputStreamReader(getClass().getResourceAsStream("index-preamble.txt")), w);
            for (Entry<Module, List<Family>> e : byModule.entrySet()) {
                w.println();
                w.println("* link:" + e.getKey().getUrlName() + "[Extension points defined in " + e.getKey().displayName + "]");
            }
        }

        for (Entry<Module, List<Family>> e : byModule.entrySet()) {
            List<Family> fam = e.getValue();
            Module m = e.getKey();
            Collections.sort(fam);
            try (PrintWriter w = new PrintWriter(new File(asciidocOutputDir, m.getUrlName() + ".adoc"))) {
                IOUtils.copy(new InputStreamReader(getClass().getResourceAsStream("component-preamble.txt")), w);
                w.println("# Extension Points defined in " + m.displayName);
                w.println();
                w.println(m.getFormattedLink());
                for (Family f : fam) {
                    f.formatAsAsciidoc(w);
                }
            }
        }
    }

    private void discover(Module m) throws IOException, InterruptedException {
        if (sorcererDir!=null) {
            final File dir = new File(sorcererDir, m.artifact.artifact.artifactId);
            dir.mkdirs();
            sorcererGenerator.generate(m.artifact,dir);
        }

        if (asciidocOutputDir !=null || jsonFile!=null) {
            for (ClassOfInterest e : extractor.extract(m.artifact)) {
                synchronized (families) {
                    System.out.println("Found "+e);

                    if (e instanceof Extension) {
                        Extension ee = (Extension) e;
                        String key = ee.extensionPoint.getQualifiedName().toString();

                        Family f = families.get(key);
                        if (f==null)    families.put(key,f=new Family());

                        ExtensionSummary es = new ExtensionSummary(f, ee);
                        m.extensions.add(es);
                        if (ee.isDefinition()) {
                            assert f.definition == null;
                            f.definition = es;
                        } else {
                            f.implementations.add(es);
                        }
                    }else if(e instanceof Action){
                        m.actions.add(new ActionSummary((Action)e));
                    }
                }
            }
        }
    }
}
