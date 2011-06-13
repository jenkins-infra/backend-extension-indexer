package org.jenkinsci.extension_indexer;

import hudson.plugins.jira.soap.ConfluenceSoapService;
import hudson.plugins.jira.soap.RemotePage;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;
import org.apache.commons.io.IOUtils;
import org.jvnet.hudson.confluence.Confluence;
import org.jvnet.hudson.update_center.ConfluencePluginList;
import org.jvnet.hudson.update_center.HudsonWar;
import org.jvnet.hudson.update_center.MavenArtifact;
import org.jvnet.hudson.update_center.MavenRepositoryImpl;
import org.jvnet.hudson.update_center.Plugin;
import org.jvnet.hudson.update_center.PluginHistory;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;

import javax.xml.rpc.ServiceException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.URL;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Properties;
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
    private final Map<MavenArtifact,Module> modules = new HashMap<MavenArtifact,Module>();

    @Option(name="-wiki",usage="Upload the result to the specified Wiki page")
    public String wikiPage;

    /**
     * Relationship between definition and implementations of the extension points.
     */
    public class Family {
        Extension definition;
        final List<Extension> implementations = new ArrayList<Extension>();

        public String getName() {
            return definition.extensionPoint.getQualifiedName().toString();
        }

        void formatAsConfluencePage(PrintWriter w) {
            w.println("h2." + definition.extensionPoint.getQualifiedName());
            w.println(getSynopsis(definition));
            w.println(definition.getConfluenceDoc());
            w.println();
            w.println("{expand:title=Implementations}");
            for (Extension e : implementations) {
                w.println("h3."+e.implementation.getQualifiedName());
                w.println(getSynopsis(e));
                w.println(e.getConfluenceDoc());
            }
            if (implementations.isEmpty())
                w.println("(No known implementation)");
            w.println("{expand}");
            w.println("");
        }

        private String getSynopsis(Extension e) {
            final Module m = modules.get(e.artifact);
            if (m==null)
                throw new IllegalStateException("Unable to find module for "+e.artifact);
            return MessageFormat.format("*Defined in*: {0}  ([javadoc|{1}@javadoc])\n",
                    m.getWikiLink(), e.extensionPoint.getQualifiedName());
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
        MavenRepositoryImpl r = new MavenRepositoryImpl();
        r.addRemoteRepository("java.net2",
                new File("updates.jenkins-ci.org"),
                new URL("http://maven.glassfish.org/content/groups/public/"));

        final ConfluencePluginList cpl = new ConfluencePluginList();

        HudsonWar war = r.getHudsonWar().firstEntry().getValue();
        final MavenArtifact core = war.getCoreArtifact();
        discover(core);
        modules.put(core, new Module(core,"http://github.com/jenkinsci/jenkins/","Jenkins Core") {
            @Override
            String getWikiLink() {
                return "[Jenkins Core|Building Jenkins]";
            }
        });

        processPlugins(r, cpl);

        JSONObject all = new JSONObject();
        for (Family f : families.values()) {
            if (f.definition==null)     continue;   // skip undefined extension points
            JSONObject o = f.definition.toJSON();

            JSONArray use = new JSONArray();
            for (Extension impl : f.implementations)
                use.add(impl.toJSON());
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

        File page = generateConfluencePage();
        uploadToWiki(page);
    }

    private void processPlugins(MavenRepositoryImpl r, final ConfluencePluginList cpl) throws Exception {
        ExecutorService svc = Executors.newFixedThreadPool(4);
        try {
            Set<Future> futures = new HashSet<Future>();
            for (final PluginHistory p : new ArrayList<PluginHistory>(r.listHudsonPlugins())/*.subList(0,200)*/) {
                futures.add(svc.submit(new Runnable() {
                    public void run() {
                        try {
                            System.out.println(p.artifactId);
                            synchronized (modules) {
                                Plugin pi = new Plugin(p,cpl);
                                modules.put(p.latest(), new Module(p.latest(),pi.getWiki(),pi.getTitle()) {
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

    private File generateConfluencePage() throws IOException {
        Map<Module,List<Family>> byModule = new LinkedHashMap<Module,List<Family>>();
        for (Family f : families.values()) {
            if (f.definition==null)     continue;   // skip undefined extension points

            Module key = modules.get(f.definition.artifact);
            List<Family> value = byModule.get(key);
            if (value==null)    byModule.put(key,value=new ArrayList<Family>());
            value.add(f);
        }

        File page = new File("extension-points.page");
        PrintWriter w = new PrintWriter(page);
        IOUtils.copy(new InputStreamReader(getClass().getResourceAsStream("preamble.txt")),w);
        for (Entry<Module, List<Family>> e : byModule.entrySet()) {
            w.println("h1.Extension Points in "+e.getKey().getWikiLink());
            for (Family f : e.getValue())
                f.formatAsConfluencePage(w);
        }
        w.close();
        return page;
    }

    private void uploadToWiki(File page) throws IOException, ServiceException {
        if (wikiPage==null) return;
        System.out.println("Uploading to " + wikiPage);
        ConfluenceSoapService service = Confluence.connect(new URL("https://wiki.jenkins-ci.org/"));

        Properties props = new Properties();
        File credential = new File(new File(System.getProperty("user.home")), ".jenkins-ci.org");
        if (!credential.exists())
            throw new IOException("You need to have userName and password in "+credential);
        props.load(new FileInputStream(credential));
        String token = service.login(props.getProperty("userName"),props.getProperty("password"));

        RemotePage p = service.getPage(token, "JENKINS", wikiPage);
        p.setContent(FileUtils.readFileToString(page));
        service.storePage(token,p);
    }

    private void discover(MavenArtifact a) throws IOException, InterruptedException {
        for (Extension e : new ExtensionPointsExtractor(a).extract()) {
            synchronized (families) {
                System.out.printf("Found %s as %s\n",
                        e.implementation.getQualifiedName(),
                        e.extensionPoint.getQualifiedName());

                String key = e.extensionPoint.getQualifiedName().toString();
                Family f = families.get(key);
                if (f==null)    families.put(key,f=new Family());

                if (e.isDefinition()) {
                    assert f.definition==null;
                    f.definition = e;
                } else {
                    f.implementations.add(e);
                }
            }
        }
    }
}
