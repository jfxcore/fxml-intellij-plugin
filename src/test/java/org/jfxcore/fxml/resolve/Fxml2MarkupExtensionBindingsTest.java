package org.jfxcore.fxml.resolve;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class Fxml2MarkupExtensionBindingsTest {
    @Test
    void configurationBindingsPreserveTheirNestedSourceOffsets() {
        String content = "label='$literal'; values=1, ${model.count}; nested={Supplier value=$model.title}";
        var bindings = Fxml2MarkupExtensionBindings.parse(content, Map.of());
        assertEquals(List.of("model.count", "model.title"),
                bindings.stream().map(binding -> binding.expression().path()).toList());
        for (var binding : bindings) {
            int start = binding.offset() + binding.expression().pathOffset();
            assertEquals(binding.expression().path(), content.substring(start, start + binding.expression().path().length()));
        }
    }

    @Test
    void mappedPrefixesAndEveryBindingNotationUseTheSameTraversal() {
        String content = "value=%key; argument=>{model.count}; sync=#{model.title}";
        var bindings = Fxml2MarkupExtensionBindings.parse(content, Map.of('%', "test.Resource"));
        assertEquals(List.of("model.count", "model.title"),
                bindings.stream().map(binding -> binding.expression().path()).toList());
    }
}
