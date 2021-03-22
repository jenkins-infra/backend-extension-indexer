package org.jenkinsci.extension_indexer;

import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.IOUtils;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

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
    public void close() throws IOException {
    }

    public List<File> getClassPath() {
        return FileUtils.getFileIterator(libDir, "jar");
    }

    public List<File> getSourceFiles() {
        return FileUtils.getFileIterator(srcDir, "java");
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
        List<String> views = new ArrayList<String>();

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
            allViews = new ArrayList<String>();
            for (File jar : getClassPath()) {
                JarFile jf = null;
                try {
                    jf = new JarFile(jar);
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
                } finally {
                    if (jf!=null)
                        try {
                            jf.close();
                        } catch (IOException _) {
                        }
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

    public static SourceAndLibs create(Module module) throws IOException, InterruptedException {
        final File tempDir = File.createTempFile("jenkins","extPoint");
        tempDir.delete();
        tempDir.mkdirs();

        File srcdir = new File(tempDir,"src");
        File libdir = new File(tempDir,"lib");

        System.out.println("Fetching " + module.getSourcesUrl());

        File sourcesJar = File.createTempFile(module.artifactId, "sources");
        IOUtils.copy(module.getSourcesUrl().openStream(), FileUtils.openOutputStream(sourcesJar));
        FileUtils.unzip(sourcesJar, srcdir);

        System.out.println("Fetching " + module.getResolvedPomUrl());
        FileUtils.copyURLToFile(module.getResolvedPomUrl(), new File(srcdir, "pom.xml"));

        System.out.println("Downloading Dependacies");
        downloadDependencies(srcdir, libdir);

        return new SourceAndLibs(srcdir, libdir) {
            @Override
            public void close() throws IOException {
                FileUtils.deleteDirectory(tempDir);
            }
        };
    }

    private static void downloadDependencies(File pomDir, File destDir) throws IOException, InterruptedException {
        destDir.mkdirs();
        String process = "mvn";
        if (System.getenv("M2_HOME") != null) {
            process = System.getenv("M2_HOME") + "/bin/mvn";
        }
        List<String> command = new ArrayList<>();
        command.add(process);
        command.addAll(Arrays.asList("--settings", new File("maven-settings.xml").getAbsolutePath()));
        command.addAll(Arrays.asList("--update-snapshots",
                "--batch-mode",
                "dependency:copy-dependencies",
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

    private static final Set<String> VIEW_EXTENSIONS = new HashSet<String>(Arrays.asList("jelly", "groovy"));
}
