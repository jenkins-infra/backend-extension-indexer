package org.jenkinsci.extension_indexer;

import com.sun.source.util.JavacTask;
import com.sun.source.util.TreePath;
import com.sun.source.util.Trees;
import net.sf.json.JSONObject;
import org.jvnet.hudson.update_center.MavenArtifact;

import javax.lang.model.element.TypeElement;

/**
 * @author Vivek Pandey
 */
public class Action extends BaseClass {

    Action(MavenArtifact artifact, JavacTask javac, Trees trees, TypeElement implementation, TreePath implPath, TypeElement action) {
        super(artifact, javac, trees, implementation, implPath, action);
    }

    @Override
    public JSONObject toJSON() {
        JSONObject i = super.toJSON();
        i.put("action",baseType.getQualifiedName().toString());
        return i;
    }
}
