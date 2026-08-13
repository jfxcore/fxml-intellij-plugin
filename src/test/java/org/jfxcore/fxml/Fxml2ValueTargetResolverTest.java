package org.jfxcore.fxml;

import com.intellij.openapi.application.ReadAction;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiParameter;
import com.intellij.psi.PsiType;
import com.intellij.psi.search.GlobalSearchScope;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jfxcore.fxml.resolve.Fxml2AttributeValueResolver;
import org.jfxcore.fxml.resolve.Fxml2ValueTargetResolver;
import org.jfxcore.fxml.resolve.Fxml2ValueTargetResolver.Arguments;
import org.jfxcore.fxml.resolve.Fxml2ValueTargetResolver.Items;
import org.jfxcore.fxml.resolve.Fxml2ValueTargetResolver.Scalar;
import org.jfxcore.fxml.resolve.Fxml2ValueTargetResolver.ValueTarget;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Tests that an attribute target is classified as taking a single value or a sequence of values.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Fxml2ValueTargetResolverTest extends Fxml2TestBase {

    @BeforeAll
    void addPropertyMocks() {
        getFixture().addClass("""
                package test;
                import java.util.List;
                public class SequenceControl {
                    public SequenceControl() {}
                    public void setItems(Object[] items) {}
                    public Object[] getItems() { return null; }
                    public void setNames(List<String> names) {}
                    public List<String> getNames() { return null; }
                }
                """);
        getFixture().addClass("""
                package test;
                import java.util.List;
                public class ElementControl<T> {
                    public ElementControl() {}
                    public void setElements(List<T> elements) {}
                    public List<T> getElements() { return null; }
                }
                """);
    }

    /**
     * The resolved target, reduced to plain data so that assertions do not need PSI access.
     *
     * @param kind           the target class
     * @param itemType       the canonical item type of a collecting target, or {@code null}
     * @param parameterTypes the canonical parameter types of an implicitly constructed target
     */
    private record TargetInfo(@NotNull Class<? extends ValueTarget> kind,
                              @Nullable String itemType,
                              @NotNull List<String> parameterTypes) {}

    /** Resolves the target of {@code propertyName} on {@code className} for a value of the given size. */
    private TargetInfo targetOf(String className, String propertyName, int itemCount) {
        return ReadAction.compute(() -> {
            GlobalSearchScope scope = GlobalSearchScope.allScope(getFixture().getProject());
            PsiClass ownerClass = JavaPsiFacade.getInstance(getFixture().getProject())
                    .findClass(className, scope);
            assertNotNull(ownerClass, className + " must resolve");
            PsiType propType = Fxml2AttributeValueResolver.propertyType(ownerClass, propertyName, List.of());
            assertNotNull(propType, propertyName + " must have a resolvable type");

            ValueTarget target = Fxml2ValueTargetResolver.resolveTarget(propType, itemCount, scope);
            return switch (target) {
                case Items(PsiType itemType) -> new TargetInfo(
                        Items.class, itemType != null ? itemType.getCanonicalText() : null, List.of());
                case Arguments(List<PsiParameter> parameters) -> new TargetInfo(
                        Arguments.class, null, parameters.stream().map(p -> p.getType().getCanonicalText()).toList());
                case Scalar(PsiType type) -> new TargetInfo(
                        Scalar.class, type.getCanonicalText(), List.of());
            };
        });
    }

    @Test
    void collectionPropertyCollectsItsElementType() {
        TargetInfo target = targetOf("javafx.scene.Node", "styleClass", 3);
        assertEquals(Items.class, target.kind());
        assertEquals("java.lang.String", target.itemType());
    }

    @Test
    void collectionPropertyOfNonStringElementsCollectsThatType() {
        TargetInfo target = targetOf("javafx.scene.shape.Polygon", "points", 4);
        assertEquals(Items.class, target.kind());
        assertEquals("java.lang.Double", target.itemType());
    }

    @Test
    void plainCollectionPropertyCollectsItsElementType() {
        TargetInfo target = targetOf("test.SequenceControl", "names", 2);
        assertEquals(Items.class, target.kind());
        assertEquals("java.lang.String", target.itemType());
    }

    @Test
    void arrayPropertyCollectsItsComponentType() {
        TargetInfo target = targetOf("test.SequenceControl", "items", 2);
        assertEquals(Items.class, target.kind());
        assertEquals("java.lang.Object", target.itemType());
    }

    @Test
    void unresolvedElementTypeLeavesTheItemTypeUnknown() {
        TargetInfo target = targetOf("test.ElementControl", "elements", 2);
        assertEquals(Items.class, target.kind());
        assertNull(target.itemType());
    }

    @Test
    void implicitlyConstructedPropertyTakesOneItemPerConstructorParameter() {
        TargetInfo single = targetOf("javafx.scene.layout.Region", "padding", 1);
        assertEquals(Arguments.class, single.kind());
        assertEquals(List.of("double"), single.parameterTypes());

        TargetInfo all = targetOf("javafx.scene.layout.Region", "padding", 4);
        assertEquals(Arguments.class, all.kind());
        assertEquals(List.of("double", "double", "double", "double"), all.parameterTypes());
    }

    @Test
    void implicitlyConstructedPropertyHasNoTargetForAnUnmatchedItemCount() {
        assertEquals(Scalar.class, targetOf("javafx.scene.layout.Region", "padding", 2).kind());
    }

    @Test
    void plainPropertyTakesTheWholeValue() {
        TargetInfo target = targetOf("javafx.scene.control.Label", "text", 2);
        assertEquals(Scalar.class, target.kind());
        assertEquals("java.lang.String", target.itemType());
    }
}
