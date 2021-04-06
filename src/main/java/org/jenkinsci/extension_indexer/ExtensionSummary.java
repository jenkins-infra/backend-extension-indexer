package org.jenkinsci.extension_indexer;

import net.sf.json.JSONObject;
import org.apache.commons.lang.StringUtils;
import org.jenkinsci.extension_indexer.ExtensionPointListGenerator.Family;

import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Captures key details of {@link Extension} but without keeping much of the work in memory.
 *
 * @author Kohsuke Kawaguchi
 * @see Extension
 */
public class ExtensionSummary {
    /**
     * Back reference to the module where this implementation was found.
     */
    public final Module module;

    public final String extensionPoint;

    public final String implementation;

    public final String documentation;

    public final JSONObject json;

    public final boolean hasView;

    public final Map<String,String> views;

    public final String packageName;

    public final String className;

    public final String topLevelClassName;

    /**
     * True for a definition of extension point, false for an implementation of extension point.
     */
    public final boolean isDefinition;

    /**
     * Family that this extension belongs to. Eithe {@link Family#definition} is 'this'
     * or {@link Family#implementations} includes 'this'
     */
    public final Family family;

    public ExtensionSummary(Family f, Extension e) {
        this.family = f;
        this.isDefinition = e.isDefinition();
        this.module = e.module;
        this.extensionPoint = e.extensionPoint.getQualifiedName().toString();
        this.implementation = e.implementation!=null ? e.implementation.getQualifiedName().toString() : null;
        this.documentation = e.getDocumentation();
        this.hasView = e.hasView();
        this.packageName = findPackageName(e.implementation);
        this.className = findClassName(e.implementation);
        this.topLevelClassName = findTopLevelClassName(e.implementation);
        this.views = e.views;
        this.json = e.toJSON();
    }

    private String findPackageName(TypeElement element) {
        Element parent = element.getEnclosingElement();
        while (!(parent.getKind().equals(ElementKind.PACKAGE))) {
            parent = parent.getEnclosingElement();
        }
        return ((PackageElement) parent).getQualifiedName().toString();
    }

    private String findTopLevelClassName(Element element) {
        while (!(element.getEnclosingElement().getKind().equals(ElementKind.PACKAGE))) {
            element = element.getEnclosingElement();
        }
        return element.getSimpleName().toString();
    }

    private String findClassName(Element element) {
        List<String> names = new ArrayList<>();
        while (!(element.getKind().equals(ElementKind.PACKAGE))) {
            names.add(0, element.getSimpleName().toString());
            element = element.getEnclosingElement();
        }
        if (names.contains(null)) {
            return null;
        }

        return StringUtils.join(names, ".");
    }
}
