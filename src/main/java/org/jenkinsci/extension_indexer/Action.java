package org.jenkinsci.extension_indexer;

import com.sun.source.util.JavacTask;
import com.sun.source.util.TreePath;
import com.sun.source.util.Trees;
import net.sf.json.JSONObject;

import javax.lang.model.element.TypeElement;
import java.util.Map;

/**
 * Instantiable {@code Action} subtype.
 *
 * @author Vivek Pandey
 */
public class Action extends ClassOfInterest {
    Action(Module module, JavacTask javac, Trees trees, TypeElement action, TreePath implPath, Map<String,String> views) {
        super(module, javac, trees, action, implPath, views);
    }

    public TypeElement getAction() {
        return implementation;
    }

    @Override
    public JSONObject toJSON() {
        JSONObject i = super.toJSON();
        i.put("action",implementation.getQualifiedName().toString());
        return i;
    }

    @Override
    public String toString() {
        return "Action "+implementation.getQualifiedName();
    }
}
