package org.jfxcore.fxml;

import org.jfxcore.fxml.annotator.Fxml2AttributeValueInspection;
import org.jfxcore.fxml.annotator.Fxml2FxAttributeInspection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * Tests related to resource-loading attribute values in FXML files.
 *
 * <p>The {@code fx:resource} intrinsic is not supported by the FXML compiler.
 * Resource loading is handled by the {@code ClassPathResource},
 * {@code StaticResource}, and {@code DynamicResource} markup extensions from the
 * {@code org.jfxcore.markup.resource} package.
 *
 * <p>The {@code @x} compact notation expands to {@code ClassPathResource}.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Fxml2ResourceTest extends Fxml2TestBase {

    @BeforeEach
    void enableInspections() {
        getFixture().enableInspections(new Fxml2AttributeValueInspection(), new Fxml2FxAttributeInspection());
        // Add mock markup-library classes so ClassPathResource can be resolved.
        getFixture().addClass("""
                package org.jfxcore.markup;
                public interface MarkupExtension {
                    interface Supplier<T> extends MarkupExtension {
                        @java.lang.annotation.Target(java.lang.annotation.ElementType.METHOD)
                        @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.CLASS)
                        @interface ReturnType { Class<?>[] value() default {}; }
                        T get(MarkupContext context) throws Exception;
                    }
                }
                """);
        getFixture().addClass("package org.jfxcore.markup; public interface MarkupContext {}");
        getFixture().addClass("""
                package org.jfxcore.markup.resource;
                import org.jfxcore.markup.MarkupExtension;
                import org.jfxcore.markup.MarkupContext;
                import javafx.beans.DefaultProperty;
                import javafx.beans.NamedArg;
                @DefaultProperty("value")
                public final class ClassPathResource implements MarkupExtension.Supplier<Object> {
                    public ClassPathResource(@NamedArg("value") String value) {}
                    @Override
                    @MarkupExtension.Supplier.ReturnType({String.class, java.net.URI.class, java.net.URL.class})
                    public Object get(MarkupContext context) { return null; }
                }
                """);
    }

    // -----------------------------------------------------------------------
    // @x compact notation: accepted (maps to ClassPathResource)
    // -----------------------------------------------------------------------

    /**
     * {@code @/path/to/image.jpg} on a URL-compatible property: the compiler treats
     * this as a ClassPathResource invocation.  The plugin does not yet implement full
     * ClassPathResource support, but the value must not be flagged as an
     * attribute-value error in the meantime.
     */
    @Test
    void atCompactNotationOnUrlPropertyProducesNoError() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.image.Image\njavafx.scene.image.ImageView",
                """
                  <ImageView>
                    <image><Image url="@/icons/icon.png"/></image>
                  </ImageView>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * {@code @/path/to/file} on a String-typed property should be accepted without error.
     */
    @Test
    void atCompactNotationOnStringPropertyProducesNoError() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.Label",
                """
                  <Label text="@/messages/hello.txt"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * {@code @'/path with spaces/file.txt'}: quoted form of the @-notation; must be accepted.
     */
    @Test
    void atCompactNotationWithSpacesInQuotesProducesNoError() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.Label",
                """
                  <Label text="@'/path with spaces/file.txt'"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    // -----------------------------------------------------------------------
    // {fx:resource ...} is no longer a valid keyword
    // -----------------------------------------------------------------------

    /**
     * {@code {fx:resource /path/to/image.jpg}}: the {@code fx:resource} intrinsic is not
     * a recognized binding keyword in the FXML compiler.  Using it must be flagged as an
     * unknown binding keyword error on the {@code fx:resource} token.
     */
    @Test
    void fxResourceAttributeNotation_isError() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.image.Image\njavafx.scene.image.ImageView",
                """
                  <ImageView>
                    <image><Image url="{%s /icons/icon.png}"/></image>
                  </ImageView>
                """.formatted(error("Unknown binding keyword 'fx:resource'", "fx:resource"))
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * {@code {fx:resource name=/path/to/image}}: the explicit {@code name=} form of
     * {@code fx:resource} is equally invalid and must produce the same error.
     */
    @Test
    void fxResourceNameProperty_isError() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.image.Image\njavafx.scene.image.ImageView",
                """
                  <ImageView>
                    <image><Image url="{%s name=/icons/icon.png}"/></image>
                  </ImageView>
                """.formatted(error("Unknown binding keyword 'fx:resource'", "fx:resource"))
        ));
        getFixture().checkHighlighting(false, false, false);
    }
}
