package org.jenkinsci.extension_indexer;

import org.apache.commons.io.IOUtils;
import org.jvnet.hudson.update_center.MavenArtifact;

import javax.tools.StandardJavaFileManager;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;

/**
 * Create a local repository of the HPI/JPI for the plugins.
 */
public class HpiAssembler implements Closeable {
    public final File pluginDir;

    public HpiAssembler(String parent){
        pluginDir = new File(parent, "plugins");
        pluginDir.mkdirs();
    }

    public HpiAssembler(){
        pluginDir = new File("plugins");
        pluginDir.mkdirs();
    }

    public void create(MavenArtifact artifact) throws IOException, InterruptedException {
        File hpi = artifact.resolve();
        FileUtils.copyFile(hpi, new File(pluginDir, hpi.getName()));
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

    /**
     * Frees any resources allocated for this.
     * In particular, delete the files if they are temporarily extracted.
     */
    public void close() throws IOException {
        //FileUtils.deleteDirectory(pluginDir);
        System.out.println("Can't clean up the directory anymore.");
    }
}
