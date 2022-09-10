package org.jenkinsci.extension_indexer;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import com.sun.tools.javac.api.JavacTool;
import org.apache.commons.io.FilenameUtils;

import javax.lang.model.element.TypeElement;
import javax.lang.model.type.NoType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticListener;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Finds the defined extension points in a HPI.
 *
 * @author Robert Sandell
 * @author Kohsuke Kawaguchi
 */
@SuppressWarnings({"Since15"})
public class ExtensionPointsExtractor {
    public List<ClassOfInterest> extract(Module module) throws IOException, InterruptedException {
        return extract(module,SourceAndLibs.create(module));
    }

    public List<ClassOfInterest> extract(final Module module, final SourceAndLibs sal) throws IOException {
        StandardJavaFileManager fileManager = null;
        try {
            JavaCompiler javac1 = JavacTool.create();
            DiagnosticListener<JavaFileObject> errorListener = createErrorListener();
            fileManager = javac1.getStandardFileManager(errorListener, Locale.getDefault(), Charset.defaultCharset());


            fileManager.setLocation(StandardLocation.CLASS_PATH, sal.getClassPath());

            // annotation processing appears to cause the source files to be reparsed
            // (even though I couldn't find exactly where it's done), which causes
            // Tree symbols created by the original JavacTask.parse() call to be thrown away,
            // which breaks later processing.
            // So for now, don't perform annotation processing
            List<String> options = Arrays.asList("-proc:none");

            Iterable<? extends JavaFileObject> files = fileManager.getJavaFileObjectsFromFiles(sal.getSourceFiles());
            JavaCompiler.CompilationTask task = javac1.getTask(null, fileManager, errorListener, options, null, files);
            final JavacTask javac = (JavacTask) task;
            final Trees trees = Trees.instance(javac);
            final Elements elements = javac.getElements();
            final Types types = javac.getTypes();

            Iterable<? extends CompilationUnitTree> parsed = javac.parse();
            javac.analyze();

            final List<ClassOfInterest> r = new ArrayList<>();

            // discover all compiled types
            TreePathScanner<?,?> classScanner = new TreePathScanner<Void,Void>() {
                final TypeElement extensionPoint = elements.getTypeElement("hudson.ExtensionPoint");
                final TypeElement action = elements.getTypeElement("hudson.model.Action");

                public Void visitClass(ClassTree ct, Void ignored) {
                    TreePath path = getCurrentPath();
                    TypeElement e = (TypeElement) trees.getElement(path);
                    if (e != null) {
                        checkIfExtension(path, e, e);
                        checkIfAction(path, e);
                    }
                    return super.visitClass(ct, ignored);
                }

                /**
                 * If the class is an action, create a record for it.
                 */
                private void checkIfAction(TreePath path, TypeElement e) {
                    if (types.isSubtype(e.asType(), action.asType())) {
                        r.add(new Action(module, javac, trees, e, path, collectViews(e)));
                    }
                }

                /**
                 * Recursively ascend the type hierarchy toward {@link Object} and find all extension points
                 * {@code root} implement.
                 */
                private void checkIfExtension(TreePath pathToRoot, TypeElement root, TypeElement e) {
                    if (e==null)    return; // if the compilation fails, this can happen

                    for (TypeMirror i : e.getInterfaces()) {
                        if (types.asElement(i).equals(extensionPoint)){
                            r.add(new Extension(module, javac, trees, root, pathToRoot, e, collectViews(e)));
                        }
                        checkIfExtension(pathToRoot,root,(TypeElement)types.asElement(i));
                    }
                    TypeMirror s = e.getSuperclass();
                    if (!(s instanceof NoType))
                        checkIfExtension(pathToRoot,root,(TypeElement)types.asElement(s));
                }

                /**
                 * Collect views recursively going up the ancestors.
                 */
                private Map<String, String> collectViews(TypeElement clazz) {
                    Map<String, String> views;

                    TypeMirror s = clazz.getSuperclass();
                    if (!(s instanceof NoType))
                        views = collectViews((TypeElement)types.asElement(s));
                    else
                        views = new HashMap<>();

                    for (String v : sal.getViewFiles(clazz.getQualifiedName().toString())) {
                        // views defined in subtypes override those defined in the base type
                        views.put(FilenameUtils.getBaseName(v),v);
                    }

                    return views;
                }
            };

            for( CompilationUnitTree u : parsed )
                classScanner.scan(u,null);

            return r;
        } catch (AssertionError e) {
            // javac has thrown this exception for some input
            System.err.println("Failed to analyze "+module.gav);
            e.printStackTrace();
            return Collections.emptyList();
        } finally {
            if (fileManager!=null)
                fileManager.close();
            sal.close();
        }
    }

    private void populateViewMap(List<File> files, Map<String,String> views){
        for(File f: files) {
            String fqName = f.getAbsolutePath();
            int loc = fqName.indexOf("src");
            if (loc > 0) {
                String path = fqName.substring(loc + 4);
                String[] a = path.split("/");
                String name = a[a.length - 1];
                int i = name.lastIndexOf(".");
                if(i > 0){
                    name = name.substring(0,i);
                }

                if (views.get(name) == null) {
                    views.put(name, path);
                }
            } else {
                //We can't get here as jelly files are always stored inside src root
            }
        }
    }

    protected DiagnosticListener<JavaFileObject> createErrorListener() {
        return new DiagnosticListener<>() {
            public void report(Diagnostic<? extends JavaFileObject> diagnostic) {
                //TODO report
                System.out.println(diagnostic);
            }
        };
    }
}
