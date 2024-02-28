package org.jenkinsci.extension_indexer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.sun.source.util.JavacTask;
import com.sun.source.util.TreePath;
import com.sun.source.util.Trees;
import org.junit.jupiter.api.Test;
import net.sf.json.JSONObject;

import javax.lang.model.element.TypeElement;
import java.util.HashMap;
import java.util.Map;

public class ActionSummaryTest {

    @Test
    public void testActionSummary() {
        // Mock the necessary dependencies
        Module module = mock(Module.class);
        JavacTask javac = mock(JavacTask.class);
        Trees trees = mock(Trees.class);
        TypeElement action = mock(TypeElement.class);
        TreePath implPath = mock(TreePath.class);
        Map<String, String> views = new HashMap<>();

        // Create a mock Action object for testing
        Action mockAction = new Action(module, javac, trees, action, implPath, views) {
            @Override
            public String getImplementationName() {
                return "MockAction";
            }

            @Override
            public boolean hasView() {
                return true;
            }

            @Override
            public JSONObject toJSON() {
                return new JSONObject().element("key", "value");
            }
        };

        // Create an ActionSummary object using the mock Action
        ActionSummary actionSummary = new ActionSummary(mockAction);

        // Verify the fields of the ActionSummary object
        assertEquals("MockAction", actionSummary.action);
        assertTrue(actionSummary.hasView);
        assertEquals("{\"key\":\"value\"}", actionSummary.json.toString());
    }
}
