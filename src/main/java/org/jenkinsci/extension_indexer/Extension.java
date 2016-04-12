package org.jenkinsci.extension_indexer;

import com.sun.source.util.JavacTask;
import com.sun.source.util.TreePath;
import com.sun.source.util.Trees;
import net.sf.json.JSONObject;
import org.jvnet.hudson.update_center.MavenArtifact;

import javax.lang.model.element.TypeElement;
import java.util.Map;

/**
 * Information about the implementation of an extension point
 * (and extension point definition.)
 *
 * @author Kohsuke Kawaguchi
 * @see ExtensionSummary
 */
public final class Extension extends ClassOfInterest {

    /**
     * Extension point that's implemented.
     * (from which {@link #implementation} derives from.)
     */
    public final TypeElement extensionPoint;


    Extension(MavenArtifact artifact, JavacTask javac, Trees trees, TypeElement implementation, TreePath implPath, TypeElement extensionPoint, Map<String,String> views) {
        super(artifact, javac, trees, implementation, implPath, views);
        this.extensionPoint = extensionPoint;
    }

    /**
     * Returns true if this record is about a definition of an extension point
     * (as opposed to an implementation of a defined extension point.)
     */
    public boolean isDefinition() {
        return implementation.equals(extensionPoint);
    }

    /**
     * Gets the information captured in this object as JSON.
     */
    public JSONObject toJSON() {
        JSONObject i = super.toJSON();
        if (!isDefinition())
            i.put("extensionPoint",extensionPoint.getQualifiedName().toString());
        return i;
    }

    @Override
    public String toString() {
        return "Extension "+implementation.getQualifiedName()+" of "+extensionPoint.getQualifiedName();
    }
}
