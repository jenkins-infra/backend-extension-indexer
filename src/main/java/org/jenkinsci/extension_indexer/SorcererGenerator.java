package org.jenkinsci.extension_indexer;

import java.io.File;
import java.io.IOException;
import java.net.URL;

import org.jvnet.sorcerer.Analyzer;
import org.jvnet.sorcerer.Dependency;
import org.jvnet.sorcerer.FrameSetGenerator;
import org.jvnet.sorcerer.ParsedSourceSet;
import org.jvnet.sorcerer.util.DiagnosticPrinter;

/**
 * Generates source code xref with sorcerer.
 *
 * @author Kohsuke Kawaguchi
 */
public class SorcererGenerator {
    public void generate(Module module, File outputDir) throws IOException, InterruptedException {
        generate(module,SourceAndLibs.create(module),outputDir);
    }

    public void generate(Module module, SourceAndLibs sal, File outputDir) throws IOException, InterruptedException {
        Analyzer a = new Analyzer();

        a.addSourceFolder(sal.srcDir);
        for( File jar : sal.getClassPath() )
            a.addClasspath(jar);
        configureAnalyzer(a);


        ParsedSourceSet pss = a.analyze(new DiagnosticPrinter(System.out));
        pss.addDependency(new Dependency.Sorcerer("jenkins-core",new URL("http://sorcerer.jenkins-ci.org/")));

        // TODO: support i18n and use locale for HTML generation
        FrameSetGenerator fsg = new FrameSetGenerator(pss);
        fsg.setTitle(module.gav);
        fsg.generateAll(outputDir);
    }

    private void configureAnalyzer(Analyzer a) {
//        a.setSourceEncoding(encoding);
//        a.setLocale(locale);
        a.setTabWidth(4);
    }
}
