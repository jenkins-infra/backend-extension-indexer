package org.jenkinsci.extension_indexer;

import org.apache.commons.io.IOUtils;
import org.jvnet.hudson.update_center.MavenArtifact;

import javax.tools.StandardJavaFileManager;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Extracted source files and dependency jar files for a Maven project.
 *
 * @author Kohsuke Kawaguchi
 */
public class SourceAndLibs implements Closeable {
    public final File srcDir;
    public final File libDir;

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

    public List<File> getJellyFiles() {
        return FileUtils.getFileIterator(srcDir, "jelly");
    }

    // give list of jelly files in given packages
    public List<File> getJellyFiles(String fqClassName){
        List<File> jellyFiles = new ArrayList<File>();
        List<File> files = FileUtils.getFileIterator(srcDir, "jelly", "groovy");
        String fsFqClassName = fqClassName.replace('.', '/');
        for(File f: files){
            if(f.getAbsolutePath().startsWith(srcDir.getAbsolutePath()+"/"+fsFqClassName)){
                jellyFiles.add(f);
            }
        }
        return jellyFiles;
    }

    public static SourceAndLibs create(MavenArtifact artifact) throws IOException, InterruptedException {
        final File tempDir = File.createTempFile("jenkins","extPoint");
        tempDir.delete();
        tempDir.mkdirs();

        File srcdir = new File(tempDir,"src");
        File libdir = new File(tempDir,"lib");
        FileUtils.unzip(artifact.resolveSources(), srcdir);

        File pom = artifact.resolvePOM();
        FileUtils.copyFile(pom, new File(srcdir, "pom.xml"));
        downloadDependencies(srcdir,libdir);

        return new SourceAndLibs(srcdir, libdir) {
            @Override
            public void close() throws IOException {
                FileUtils.deleteDirectory(tempDir);
            }
        };
    }

    private static void downloadDependencies(File pomDir, File destDir) throws IOException, InterruptedException {
        destDir.mkdirs();
        ProcessBuilder builder = new ProcessBuilder("mvn",
                "dependency:copy-dependencies",
                "-DincludeScope=compile",
                "-DoutputDirectory=" + destDir.getAbsolutePath());
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
            throw new IOException("Maven didn't like this (exit code="+result+")! " + pomDir.getAbsolutePath());
        }
    }
}
