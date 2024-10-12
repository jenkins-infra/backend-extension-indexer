package org.jenkinsci.extension_indexer;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.IOUtils;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.Base64;
import java.net.URL;
import java.net.URLConnection;
import java.net.MalformedURLException;

/**
 * Extracted source files and dependency jar files for a Maven project.
 *
 * @author Kohsuke Kawaguchi
 */
public class SourceAndLibs implements Closeable {
    public final File srcDir;
    public final File libDir;

    /**
     * Lazily built list of all views in classpath.
     */
    private List<String> allViews;

    public SourceAndLibs(File srcDir, File libDir) {
        this.srcDir = srcDir;
        this.libDir = libDir;
    }

    /**
     * Frees any resources allocated for this.
     * In particular, delete the files if they are temporarily extracted.
     */
    @Override
    public void close() throws IOException {
    }

    public List<File> getClassPath() {
        return FileUtilsExt.getFileIterator(libDir, "jar");
    }

    public List<File> getSourceFiles() {
        return FileUtilsExt.getFileIterator(srcDir, "java");
    }

    /**
     * Give list of view files in a given package.
     *
     * @param pkg
     *      Package name like 'foo.bar'
     * @return
     *      All view files in the qualified form, such as 'foo/bar/abc.groovy'
     */
    public List<String> getViewFiles(String pkg) {
        List<String> views = new ArrayList<>();

        pkg = pkg.replace('.', '/');

        // views in source files
        File[] srcViews = new File(srcDir,pkg).listFiles();
        if (srcViews!=null) {
            for (File f : srcViews) {
                if (VIEW_EXTENSIONS.contains(FilenameUtils.getExtension(f.getPath()))) {
                    views.add(f.getName());
                }
            }
        }

        // views from dependencies
        if (allViews==null) {
            allViews = new ArrayList<>();
            for (File jar : getClassPath()) {
                try (JarFile jf = new JarFile(jar)) {
                    Enumeration<JarEntry> e = jf.entries();
                    while (e.hasMoreElements()) {
                        JarEntry je = e.nextElement();
                        String n = je.getName();
                        if (VIEW_EXTENSIONS.contains(FilenameUtils.getExtension(n))) {
                            allViews.add(n);
                        }
                    }
                } catch (IOException x) {
                    System.err.println("Failed to open "+jar);
                    x.printStackTrace();
                }
            }
        }

        // 'foo/bar/zot.jelly' is a view but 'foo/bar/xxx/yyy.jelly' is NOT a view for 'foo/bar'
        String prefix = pkg+'/';
        for (String v : allViews) {
            if (v.startsWith(prefix)) {
                String rest = v.substring(prefix.length());
                if (!rest.contains("/"))
                    views.add(v);
            }
        }

        return views;
    }

    private static byte[] auth;

    // Retrieve the auth from the artifact caching proxy Maven settings file
    private static byte[] getAuth() {
        if (auth == null && System.getenv("ARTIFACT_CACHING_PROXY_USERNAME") != null && System.getenv("ARTIFACT_CACHING_PROXY_PASSWORD") != null) {
            try {
                auth = Base64.getEncoder().encode((System.getenv("ARTIFACT_CACHING_PROXY_USERNAME") + ':' + System.getenv("ARTIFACT_CACHING_PROXY_PASSWORD")).getBytes("UTF-8"));
            } catch(UnsupportedEncodingException uee) {
                uee.printStackTrace();
            }
        }
        return auth;
    }

    private static URLConnection getURLConnection(URL url) throws MalformedURLException, IOException {
        String urlString = url.toString();
        URLConnection conn = new URL(urlString).openConnection();
        // If we're querying one of the artifact caching proxies we need to add authentication
        if (!urlString.startsWith("https://repo.jenkins-ci.org")) {
            conn.setRequestProperty("Accept-Charset", "UTF-8");
            conn.setRequestProperty("Accept-Encoding", "identity");
            conn.setRequestProperty("User-Agent", "backend-extension-indexer/0.1");
            conn.setRequestProperty("Authorization", "Basic " + new String(getAuth(), "UTF-8"));
        }
        return conn;
    }

    public static SourceAndLibs create(Module module) throws IOException, InterruptedException {
        final File tempDir = Files.createTempDirectory("jenkins-extPoint").toFile();
        File srcdir = new File(tempDir,"src");
        File libdir = new File(tempDir,"lib");

        System.out.println("Fetching " + module.getSourcesUrl());

        File sourcesJar = File.createTempFile(module.artifactId, "-sources.jar");
        try (InputStream is = getURLConnection(module.getSourcesUrl()).getInputStream(); OutputStream os = Files.newOutputStream(sourcesJar.toPath())) {
            IOUtils.copy(is, os);
        }
        FileUtilsExt.unzip(sourcesJar, srcdir);

        System.out.println("Fetching " + module.getResolvedPomUrl());
        try (InputStream is = getURLConnection(module.getResolvedPomUrl()).getInputStream(); OutputStream os = Files.newOutputStream(new File(srcdir, "pom.xml").toPath())) {
            IOUtils.copy(is, os);
        }

        System.out.println("Downloading Dependencies");
        downloadDependencies(srcdir, libdir);

        return new SourceAndLibs(srcdir, libdir) {
            @Override
            public void close() throws IOException {
                FileUtils.deleteDirectory(tempDir);
            }
        };
    }

    @SuppressFBWarnings(value = "COMMAND_INJECTION", justification = "Command injection is not a viable risk here")
    private static void downloadDependencies(File pomDir, File destDir) throws IOException, InterruptedException {
        Files.createDirectories(destDir.toPath());
        String process = "mvn";
        if (System.getenv("M2_HOME") != null) {
            process = System.getenv("M2_HOME") + "/bin/mvn";
        }
        List<String> command = new ArrayList<>();
        command.add(process);
        command.addAll(Arrays.asList("--settings", (System.getenv("MAVEN_SETTINGS") != null) ? System.getenv("MAVEN_SETTINGS") : new File("maven-settings.xml").getAbsolutePath()));
        command.addAll(Arrays.asList("--update-snapshots",
                "--batch-mode",
                "org.apache.maven.plugins:maven-dependency-plugin:3.8.0:copy-dependencies",
                "org.apache.maven.plugins:maven-dependency-plugin:3.8.0:copy",
                "-Dartifact=org.jenkins-ci.main:jenkins-core:2.479",
                "-DincludeScope=compile",
                "-DoutputDirectory=" + destDir.getAbsolutePath()));

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.environment().put("JAVA_HOME",System.getProperty("java.home"));
        builder.directory(pomDir);
        builder.redirectErrorStream(true);
        Process proc = builder.start();

        // capture the output, but only report it in case of an error
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        proc.getOutputStream().close();
        IOUtils.copy(proc.getInputStream(), output);
        proc.getErrorStream().close();
        proc.getInputStream().close();

        int result = proc.waitFor();
        if (result != 0) {
            System.out.write(output.toByteArray());
            throw new IOException("Maven didn't like this (exit code=" + result + ")! " + pomDir.getAbsolutePath());
        }
    }

    private static final Set<String> VIEW_EXTENSIONS = Set.of("jelly", "groovy");
}
