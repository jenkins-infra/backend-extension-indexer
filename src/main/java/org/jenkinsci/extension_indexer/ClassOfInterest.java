package org.jenkinsci.extension_indexer;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreePath;
import com.sun.source.util.Trees;
import net.sf.json.JSONObject;
import org.jsoup.Jsoup;
import org.jsoup.safety.Safelist;

import javax.lang.model.element.TypeElement;
import java.io.File;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Interesting thing that we pick up from plugin source code.
 * Common parts between {@link Extension} and {@link Action}.
 *
 * @author Vivek Pandey
 */
public abstract class ClassOfInterest {
    /**
     * Back reference to the module where this implementation was found.
     */
    public final Module module;

    /**
     * The compiler session where this information was determined.
     */
    public final JavacTask javac;

    /**
     * {@link TreePath} that leads to {@link #implementation}
     */
    public final TreePath implPath;

    /**
     * Class of interest whose metadata is described in this object.
     * <p>
     * This is an implementation of an extension point with {@code @Extension} on (for {@link Extension}
     * or it is a class that implements {@code Action}.
     */
    public final TypeElement implementation;

    /**
     * {@link Trees} object for {@link #javac}
     */
    public final Trees trees;

    /**
     * Jelly/groovy views associated to this class, including those defined for the ancestor types.
     *
     * Keyed by the view name (which is the base portion of the view file name). The value is the fully qualified
     * resource name.
     */
    public final Map<String, String> views;

    ClassOfInterest(Module module, JavacTask javac, Trees trees, TypeElement implementation, TreePath implPath, Map<String,String> views) {
        this.module = module;
        this.javac = javac;
        this.implPath = implPath;
        this.implementation = implementation;
        this.trees = trees;
        this.views = views;
    }


    /**
     * Returns the {@link ClassTree} representation of {@link #implementation}.
     */
    public ClassTree getClassTree() {
        return (ClassTree) implPath.getLeaf();
    }

    public CompilationUnitTree getCompilationUnit() {
        return implPath.getCompilationUnit();
    }

    /**
     * Gets the source file name that contains this definition, including directories
     * that match the package name portion.
     */
    public String getSourceFile() {
        ExpressionTree packageName = getCompilationUnit().getPackageName();
        String pkg = packageName == null ? "" : packageName.toString().replace('.', '/') + '/';

        String name = new File(getCompilationUnit().getSourceFile().getName()).getName();
        return pkg + name;
    }

    /**
     * Gets the line number in the source file where this implementation was defined.
     */
    public long getLineNumber() {
        return getCompilationUnit().getLineMap().getLineNumber(
                trees.getSourcePositions().getStartPosition(getCompilationUnit(), getClassTree()));
    }

    public String getJavadoc() {
        return javac.getElements().getDocComment(implementation);
    }

    /**
     * Javadoc excerpt converted to jenkins.io flavored Asciidoc markup.
     */
    public String getDocumentation() {
        String javadoc = getJavadoc();
        if (javadoc == null) return null;

        StringBuilder output = new StringBuilder(javadoc.length());
        for (String line : javadoc.split("\n")) {
            if (line.trim().length() == 0) break;

            if (line.trim().startsWith("@")) continue;

            {// replace @link
                Matcher m = LINK.matcher(line);
                StringBuilder sb = new StringBuilder();
                sb.append("+++");
                while (m.find()) {
                    String simpleName = m.group(1);
                    m.appendReplacement(sb, "+++ jenkinsdoc:"+simpleName+"[] +++");
                }
                m.appendTail(sb);
                sb.append("+++");
                line = sb.toString();
            }

            output.append(line).append(' ');
        }

        return Jsoup.clean(output.toString(), Safelist.basic());
    }

    /**
     * Returns the module Id of the plugin that it came from.
     */
    public String getArtifactId() {
        return module.artifactId;
    }

    private static final Pattern LINK = Pattern.compile("\\s*\\{@link ([^}]+)}\\s*");

    public String getImplementationName(){
        return implementation.getQualifiedName().toString();
    }

    public void addViews(Map<String, String> views){
        this.views.putAll(views);
    }

    /**
     * Returns true if there are jelly files
     */
    public boolean hasView(){
        return views.size() > 0;
    }


    public JSONObject toJSON(){
        JSONObject i = new JSONObject();
        i.put("className",getImplementationName());
        i.put("module",module.gav);
        i.put("javadoc",getJavadoc());
        i.put("documentation", getDocumentation());
        i.put("sourceFile",getSourceFile());
        i.put("lineNumber",getLineNumber());
        i.put("hasView", hasView());
        Set<Map<String,String>> vs = new HashSet<>();
        for (Map.Entry<String, String> entry : views.entrySet()) {
            Map<String,String> v = new HashMap<>();
            v.put("name", entry.getKey());
            v.put("source", entry.getValue());
            vs.add(v);
        }
        i.put("views",vs);
        return i;

    }
}
