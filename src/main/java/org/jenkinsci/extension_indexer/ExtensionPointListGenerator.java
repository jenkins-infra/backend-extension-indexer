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
@SuppressWarnings("Since15")
public class ExtensionPointListGenerator {
    private final Map<String,Family> families = new HashMap<String,Family>();
    private final Map<MavenArtifact,Module> modules = new HashMap<MavenArtifact,Module>();

    @Option(name="-wiki",usage="Generate the extension list index and write it out to the specified file.")
    public File wikiFile;

    @Option(name="-wikiUpload",usage="Upload the result to the specified Wiki page")
    public String wikiPage;

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

        void formatAsConfluencePage(PrintWriter w) {
            w.println("h2." + definition.extensionPoint);
            w.println(getSynopsis(definition));
            w.println(definition.confluenceDoc);
            w.println();
            w.println("{expand:title=Implementations}");
            for (ExtensionSummary e : implementations) {
                w.println("h3."+e.implementation);
                w.println(getSynopsis(e));
                w.println(e.confluenceDoc == null ? "_This extension point has no JavaDoc documentation._" : e.confluenceDoc);
            }
            if (implementations.isEmpty())
                w.println("(No known implementation)");
            w.println("{expand}");
            w.println("");
        }

        private String getSynopsis(ExtensionSummary e) {
            final Module m = modules.get(e.artifact);
            if (m==null)
                throw new IllegalStateException("Unable to find module for "+e.artifact);
            if ("Jenkins Core".equals(m.displayName)) {
                return MessageFormat.format("*Defined in*: {0}  ([javadoc|{1}@javadoc])\n",
                        m.getWikiLink(), e.extensionPoint);
            } else {
                return MessageFormat.format("*Defined in*: {0}\n", m.getWikiLink());
            }
        }

        public int compareTo(Object that) {
            return this.getName().compareTo(((Family)that).getName());
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
         * Returns a Confluence-format link to point to this module.
         */
        abstract String getWikiLink();

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
        if (wikiFile==null && sorcererDir==null)
            throw new IllegalStateException("Nothing to do. Either -wiki or -sorcerer is needed");

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
                return "[Jenkins Core|Building Jenkins]";
            }
        });

        processPlugins(r);

        if (wikiFile!=null) {
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

            generateConfluencePage();
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
        ExecutorService svc = Executors.newFixedThreadPool(4);
        try {
            Set<Future> futures = new HashSet<Future>();
            for (final PluginHistory p : new ArrayList<PluginHistory>(r.listHudsonPlugins()).subList(0,5)) {
                if (!args.isEmpty()) {
                    if (!args.contains(p.artifactId))
                        continue;   // skip
                }
                futures.add(svc.submit(new Runnable() {
                    public void run() {
                        try {
                            System.out.println(p.artifactId);
                            synchronized (modules) {
                                Plugin pi = new Plugin(p);
                                modules.put(p.latest(), new Module(p.latest(),pi.getPluginUrl(),pi.getName()) {
                                    @Override
                                    String getWikiLink() {
                                        return '['+displayName+']';
                                    }
                                });
                            }
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

    private void generateConfluencePage() throws IOException {
        Map<Module,List<Family>> byModule = new LinkedHashMap<Module,List<Family>>();
        for (Family f : families.values()) {
            if (f.definition==null)     continue;   // skip undefined extension points

            Module key = modules.get(f.definition.artifact);
            List<Family> value = byModule.get(key);
            if (value==null)    byModule.put(key,value=new ArrayList<Family>());
            value.add(f);
        }

        PrintWriter w = new PrintWriter(wikiFile);
        IOUtils.copy(new InputStreamReader(getClass().getResourceAsStream("preamble.txt")), w);
        for (Entry<Module, List<Family>> e : byModule.entrySet()) {
            w.println("h1.Extension Points in "+e.getKey().getWikiLink());
            List<Family> fam = e.getValue();
            Collections.sort(fam);
            for (Family f : fam)
                f.formatAsConfluencePage(w);
        }
        w.close();
    }

    private void discover(MavenArtifact a) throws IOException, InterruptedException {
        if (sorcererDir!=null) {
            final File dir = new File(sorcererDir, a.artifact.artifactId);
            dir.mkdirs();
            sorcererGenerator.generate(a,dir);
        }
        if (wikiFile!=null) {
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
