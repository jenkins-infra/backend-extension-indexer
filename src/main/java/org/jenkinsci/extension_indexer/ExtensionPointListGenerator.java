package org.jenkinsci.extension_indexer;

import hudson.util.VersionNumber;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;
import org.apache.commons.io.IOUtils;
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
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Command-line tool to list up extension points and their implementations into a JSON file.
 *
 * @author Kohsuke Kawaguchi
 */
public class ExtensionPointListGenerator {
    private final Map<String,Family> families = new HashMap<String,Family>();
    private final Map<MavenArtifact,Module> modules = new HashMap<MavenArtifact, Module>();

    @Option(name="-adoc",usage="Generate the extension list index and write it out to the specified directory.")
    public File adocDir;

    @Option(name="-sorcerer",usage="Generate sorcerer reports")
    public File sorcererDir;

    @Option(name="-core",usage="Core version to use. If not set, default to newest")
    public String coreVersion;

    @Argument
    public List<String> args = new ArrayList<String>();

    private ExtensionPointsExtractor extractor = new ExtensionPointsExtractor();
    private SorcererGenerator sorcererGenerator = new SorcererGenerator();


    /**
     * Relationship between definition and implementations of the extension points.
     */
    public class Family implements Comparable {
        // from definition
        ExtensionSummary definition;
        private final List<ExtensionSummary> implementations = new ArrayList<ExtensionSummary>();

        public String getName() {
            return definition.extensionPoint;
        }

        public String getShortName() {
            if (getName().contains(".")) {
                return getName().substring(getName().lastIndexOf(".") + 1);
            }
            return getName();
        }

        void formatAsConfluencePage(PrintWriter w) {
            w.println();
            w.println("## " + getShortName());
            w.println("+" + definition.extensionPoint + "+");
            w.println();
            w.println(definition.documentation == null || formatJavadoc(definition.documentation).trim().length() == 0 ? "_This extension point has no Javadoc documentation._" : formatJavadoc(definition.documentation));
            w.println();
            w.println("**Implementations:**");
            w.println();
            for (ExtensionSummary e : implementations) {
                w.println();
                w.println((e.implementation == null ? "(Anonymous class)" : e.implementation) + " " + getShortSynopsis(e) + "::");
                w.println((e.documentation == null || formatJavadoc(e.documentation).trim().length() == 0 ? "_This implementation has no Javadoc documentation._" : formatJavadoc(e.documentation)));
            }
            if (implementations.isEmpty())
                w.println("_(no known implementations)_");
            w.println();
        }

        private String formatJavadoc(String javadoc) {
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

        private String getShortSynopsis(ExtensionSummary e) {
            final Module m = modules.get(e.artifact);
            if (m==null)
                throw new IllegalStateException("Unable to find module for "+e.artifact);
            return MessageFormat.format("(implemented in {0})", m.getWikiLink());
        }

        public int compareTo(Object that) {
            return this.getShortName().compareTo(((Family)that).getShortName());
        }
    }

    /**
     * Information about the module that we scanned extensions.
     */
    abstract class Module {
        final MavenArtifact artifact;
        final String url;
        final String displayName;

        protected Module(MavenArtifact artifact, String url, String displayName) {
            this.artifact = artifact;
            this.url = url;
            this.displayName = displayName;
        }

        /**
         * Returns a adoc formatted link to point to this module.
         */
        abstract String getWikiLink();

        abstract String getUrlName();

        JSONObject toJSON() {
            JSONObject o = new JSONObject();
            o.put("gav",artifact.getGavId());
            o.put("url",url);
            o.put("displayName",displayName);
            return o;
        }
    }


    public static void main(String[] args) throws Exception {
        ExtensionPointListGenerator app = new ExtensionPointListGenerator();
        CmdLineParser p = new CmdLineParser(app);
        p.parseArgument(args);
        app.run();
    }

    public void run() throws Exception {
        if (adocDir ==null && sorcererDir==null)
            throw new IllegalStateException("Nothing to do. Either -adoc or -sorcerer is needed");

        MavenRepositoryImpl r = new MavenRepositoryImpl();
        r.addRemoteRepository("public",
                new URL("http://repo.jenkins-ci.org/public/"));


        HudsonWar war;
        if (coreVersion == null) {
            war = r.getHudsonWar().firstEntry().getValue();
        } else {
            war = r.getHudsonWar().get(new VersionNumber(coreVersion));
        }
        final MavenArtifact core = war.getCoreArtifact();
        discover(core);
        modules.put(core, new Module(core,"http://github.com/jenkinsci/jenkins/","Jenkins Core") {
            @Override
            String getWikiLink() {
                return "link:https://github.com/jenkinsci/jenkins/[Jenkins Core]";
            }

            @Override
            String getUrlName() {
                return "core";
            }
        });

        processPlugins(r);

        if (adocDir !=null) {
            JSONObject all = new JSONObject();
            for (Family f : families.values()) {
                if (f.definition==null)     continue;   // skip undefined extension points
                JSONObject o = f.definition.json;

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

            FileUtils.writeStringToFile(new File("extension-points.json"), container.toString(2));

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
     * Walks over the plugins, record {@link #modules} and call {@link #discover(MavenArtifact)}.
     */
    private void processPlugins(MavenRepository r) throws Exception {
        ExecutorService svc = Executors.newFixedThreadPool(1);
        try {
            Set<Future> futures = new HashSet<Future>();
            for (final PluginHistory p : new ArrayList<PluginHistory>(r.listHudsonPlugins()).subList(0, 5)) {
                if (!args.isEmpty()) {
                    if (!args.contains(p.artifactId))
                        continue;   // skip
                }
                futures.add(svc.submit(new Runnable() {
                    public void run() {
                        try {
                            System.out.println(p.artifactId);
                            Plugin pi = new Plugin(p);
                            modules.put(p.latest(), new Module(p.latest(),pi.getPluginUrl(),pi.getName()) {
                                @Override
                                String getWikiLink() {
                                    return "link:" + url + "[" + displayName + ']';
                                }

                                @Override
                                String getUrlName() {
                                    return artifact.artifact.artifactId;
                                }
                            });
                            discover(p.latest());
                        } catch (Exception e) {
                            System.err.println("Failed to process "+p.artifactId);
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
        Map<Module,List<Family>> byModule = new LinkedHashMap<Module,List<Family>>();
        for (Family f : families.values()) {
            if (f.definition==null)     continue;   // skip undefined extension points

            Module key = modules.get(f.definition.artifact);
            List<Family> value = byModule.get(key);
            if (value==null)    byModule.put(key,value=new ArrayList<Family>());
            value.add(f);
        }

        adocDir.mkdirs();

        try (PrintWriter w = new PrintWriter(new File(adocDir, "index.adoc"))) {
            IOUtils.copy(new InputStreamReader(getClass().getResourceAsStream("index-preamble.txt")), w);
            for (Entry<Module, List<Family>> e : byModule.entrySet()) {
                w.println();
                w.println("* link:" + e.getKey().getUrlName() + "[Extension points defined in " + e.getKey().displayName + "]");
                //w.println(e.getKey().getWikiLink());
            }
        }

        for (Entry<Module, List<Family>> e : byModule.entrySet()) {
            List<Family> fam = e.getValue();
            Module m = e.getKey();
            Collections.sort(fam);
            try (PrintWriter w = new PrintWriter(new File(adocDir, m.getUrlName() + ".adoc"))) {
                IOUtils.copy(new InputStreamReader(getClass().getResourceAsStream("component-preamble.txt")), w);
                w.println("# Extension Points defined in " + m.displayName);
                w.println();
                w.println(m.getWikiLink());
                for (Family f : fam) {
                    f.formatAsConfluencePage(w);
                }
            }
        }
    }

    private void discover(MavenArtifact a) throws IOException, InterruptedException {
        if (sorcererDir!=null) {
            final File dir = new File(sorcererDir, a.artifact.artifactId);
            dir.mkdirs();
            sorcererGenerator.generate(a,dir);
        }
        if (adocDir !=null) {
            for (Extension e : extractor.extract(a)) {
                synchronized (families) {
                    System.out.printf("Found %s as %s\n",
                            e.implementation.getQualifiedName(),
                            e.extensionPoint.getQualifiedName());

                    String key = e.extensionPoint.getQualifiedName().toString();
                    Family f = families.get(key);
                    if (f==null)    families.put(key,f=new Family());

                    if (e.isDefinition()) {
                        assert f.definition==null;
                        f.definition = new ExtensionSummary(e);
                    } else {
                        f.implementations.add(new ExtensionSummary(e));
                    }
                }
            }
        }
    }
}
