package org.jenkinsci.extension_indexer;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
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

import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.IOUtils;
import org.kohsuke.args4j.Argument;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;

import net.sf.json.JSONArray;
import net.sf.json.JSONObject;

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
    private final Map<String,Family> families = new HashMap<>();
    /**
     * All the modules we scanned keyed by its {@link Module#artifact}
     */
    private final Map<String,Module> modules = Collections.synchronizedMap(new HashMap<>());

    @Option(name="-adoc",usage="Generate the extension list index and write it out to the specified directory.")
    public File asciidocOutputDir;

    @Option(name="-json",usage="Generate extension points, implementatoins, and their relationships in JSON")
    public File jsonFile;

    @Option(name="-plugins",usage="Collect *.hpi/jpi into this directory")
    public File pluginsDir;

    @Option(name="-updateCenterJson",usage="Update center's json")
    public String updateCenterJsonFile = "https://updates.jenkins.io/current/update-center.actual.json";

    @Argument
    public List<String> args = new ArrayList<>();

    private ExtensionPointsExtractor extractor = new ExtensionPointsExtractor();

    private Comparator<ExtensionSummary> IMPLEMENTATION_SORTER = new Comparator<>() {
        @Override
        public int compare(ExtensionSummary o1, ExtensionSummary o2) {
            int moduleOrder = o1.module.compareTo(o2.module);
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
    @SuppressFBWarnings(value = "EI_EXPOSE_REP2", justification = "Not worth refactor to hide internal representation")
    public class Family implements Comparable<Family> {
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
            if ("jenkins-core".equals(definition.module.artifactId)) {
                w.println("`jenkinsdoc:" + definition.extensionPoint + "[]`");
            } else {
                w.println("`jenkinsdoc:" + definition.module.artifactId + ":" + definition.extensionPoint + "[]`");
            }
            w.println();
            w.println(definition.documentation == null || formatJavadoc(definition.documentation).trim().isEmpty() ? "_This extension point has no Javadoc documentation._" : formatJavadoc(definition.documentation));
            w.println();
            w.println("**Implementations:**");
            w.println();
            for (ExtensionSummary e : implementations) {
                w.print("* " + e.module.getFormattedLink() + ": ");
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
            String artifactId = e.module.artifactId;
            if (artifactId.equals("jenkins-core")) {
                return "(link:https://github.com/jenkinsci/jenkins/blob/master/core/src/main/java/" + e.packageName.replace(".", "/") + "/" + e.topLevelClassName + ".java" + "[view on GitHub])";
            } else if (e.module instanceof Module.PluginModule) {
                String scmUrl = ((Module.PluginModule) e.module).scm;
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
            final Module m = e.module;
            if (m==null)
                throw new IllegalStateException("Unable to find module for "+e.module.artifactId);
            return m.getFormattedLink();
        }

        @Override
        public int compareTo(Family that) {
            return this.getShortName().compareTo(that.getShortName());
        }
    }

    private Module addModule(Module m) {
        modules.put(m.artifactId,m);
        return m;
    }

    public static void main(String[] args) throws Exception {
        ExtensionPointListGenerator app = new ExtensionPointListGenerator();
        CmdLineParser p = new CmdLineParser(app);
        p.parseArgument(args);
        app.run();
    }

    public JSONObject getJsonUrl(String url) throws IOException {
        try (
                InputStream is = new URL(url).openStream();
                InputStreamReader isr = new InputStreamReader(is, StandardCharsets.UTF_8);
                BufferedReader bufferedReader = new BufferedReader(isr)
            ) {
            String readLine;
            StringBuilder sb = new StringBuilder();
            while ((readLine = bufferedReader.readLine()) != null) {
                sb.append(readLine);
            }
            return JSONObject.fromObject(sb.toString());
        }
    }

    public void run() throws Exception {
        JSONObject updateCenterJson = getJsonUrl(updateCenterJsonFile);

        if (asciidocOutputDir ==null && jsonFile==null && pluginsDir ==null)
            throw new IllegalStateException("Nothing to do. Either -adoc, -json, or -pipeline is needed");

        discover(addModule(new Module.CoreModule(updateCenterJson.getJSONObject("core").getString("version"))));

        processPlugins(updateCenterJson.getJSONObject("plugins").values());

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
                artifacts.put(m.gav, m.toJSON());
            }

            JSONObject container = new JSONObject();
            container.put("extensionPoints",all);
            container.put("artifacts",artifacts);

            FileUtilsExt.writeStringToFile(jsonFile, container.toString(2));
        }

        if (asciidocOutputDir !=null) {
            generateAsciidocReport();
        }
    }

    /**
     * Walks over the plugins, record {@link #modules} and call {@link #discover(Module)}.
     * @param plugins
     */
    private void processPlugins(Collection<JSONObject> plugins) throws Exception {
        int availableProcessors = Runtime.getRuntime().availableProcessors();
        int nThreads = availableProcessors * 3;
        System.out.printf("Running with %d threads%n", nThreads);
        ExecutorService svc = Executors.newFixedThreadPool(nThreads);
        try {
            Set<Future<?>> futures = new HashSet<>();
            for (final JSONObject plugin : plugins) {
                final String artifactId = plugin.getString("name");
                if (!args.isEmpty()) {
                    if (!args.contains(artifactId))
                        continue;   // skip
                } else if ("python-wrapper".equals(artifactId)) {
                    // python-wrapper does not have extension points but just wrappers to help python plugins use extension points
                    // see https://issues.jenkins-ci.org/browse/INFRA-516
                    continue;   // skip them to remove noise
                }

                futures.add(svc.submit(new Runnable() {
                    public void run() {
                        try {
                            System.out.println(artifactId);
                            if (asciidocOutputDir !=null || jsonFile!=null) {
                                discover(addModule(new Module.PluginModule(plugin.getString("gav"), plugin.getString("url"), plugin.getString("title"), plugin.optString("scm"))));
                            }
                            if (pluginsDir!=null) {
                                FileUtilsExt.copyURLToFile(
                                        new URL(plugin.getString("url")),
                                        new File(pluginsDir, FilenameUtils.getName(plugin.getString("url")))
                                );
                            }
                        } catch (Exception e) {
                            System.err.println("Failed to process "+artifactId);
                            // TODO record problem with this plugin so we can report on it
                            e.printStackTrace();
                        }
                    }
                }));
            }
            for (Future<?> f : futures) {
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

            Module key = f.definition.module;
            List<Family> value = byModule.get(key);
            if (value==null)    byModule.put(key,value=new ArrayList<>());
            value.add(f);
        }

        Files.createDirectories(asciidocOutputDir.toPath());

        try (PrintWriter w = new PrintWriter(new File(asciidocOutputDir, "index.adoc"), StandardCharsets.UTF_8)) {
            IOUtils.copy(new InputStreamReader(getClass().getResourceAsStream("index-preamble.txt"), StandardCharsets.UTF_8), w);
            for (Entry<Module, List<Family>> e : byModule.entrySet()) {
                w.println();
                w.println("* link:" + e.getKey().getUrlName() + "[Extension points defined in " + e.getKey().displayName + "]");
            }
        }

        for (Entry<Module, List<Family>> e : byModule.entrySet()) {
            List<Family> fam = e.getValue();
            Module m = e.getKey();
            Collections.sort(fam);
            try (PrintWriter w = new PrintWriter(new File(asciidocOutputDir, m.getUrlName() + ".adoc"), StandardCharsets.UTF_8)) {
                IOUtils.copy(new InputStreamReader(getClass().getResourceAsStream("component-preamble.txt"), StandardCharsets.UTF_8), w);
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
        if (asciidocOutputDir !=null || jsonFile!=null) {
            for (ClassOfInterest e : extractor.extract(m)) {
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
