package org.jfxcore.fxml.lang;

import com.intellij.lang.java.JavaLanguage;
import com.intellij.openapi.util.Key;
import com.intellij.psi.PsiClass;
import com.intellij.psi.util.PsiModificationTracker;
import com.intellij.psi.impl.source.xml.XmlElementDescriptorProvider;
import com.intellij.psi.util.CachedValue;
import com.intellij.psi.util.CachedValueProvider;
import com.intellij.psi.util.CachedValuesManager;
import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlTag;
import com.intellij.xml.XmlElementDescriptor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jfxcore.fxml.descriptors.Fxml2ClassTagDescriptor;
import org.jfxcore.fxml.resolve.Fxml2ImportResolver;
import org.jfxcore.fxml.resolve.Fxml2TagResolver;

/**
 * Provides {@link XmlElementDescriptor} instances for every tag in an FXML2 file.
 *
 * <p>Resolution priority (mirrors the fxml2 compiler's ObjectToPropertyTransform):
 * <ol>
 *   <li>fx:-namespace intrinsics -> permissive {@link Fxml2ClassTagDescriptor}.</li>
 *   <li>Tag name resolves to a Java class via imports -> {@link Fxml2ClassTagDescriptor}.</li>
 *   <li>Tag name resolves as a property of the parent class -> property descriptor.</li>
 *   <li>Otherwise (unresolvable name) -> permissive {@link Fxml2ClassTagDescriptor} with null
 *       PsiClass, so IntelliJ's XML attribute checker stays silent and only our tag annotator
 *       reports the "cannot be resolved" error.</li>
 * </ol>
 */
public final class Fxml2ElementDescriptorProvider implements XmlElementDescriptorProvider {

    /**
     * Per-tag cache for the resolved {@link XmlElementDescriptor}.
     * Invalidated when Java-language PSI changes (class additions, renames, member changes)
     * or when the containing FXML2 file itself changes (import declarations, structural edits).
     * This avoids unnecessary re-resolution when unrelated files such as CSS, resource bundles,
     * or other FXML2 files are edited.
     */
    private static final Key<CachedValue<XmlElementDescriptor>> DESCRIPTOR_CACHE =
            Key.create("fxml2.descriptor");

    @Override
    public @Nullable XmlElementDescriptor getDescriptor(XmlTag tag) {
        if (!Fxml2FileType.isFxml2(tag.getContainingFile())) {
            return null;
        }
        return descriptorFor(tag, (XmlFile) tag.getContainingFile());
    }

    /**
     * Returns the appropriate descriptor for {@code tag} inside an FXML2 file.
     * This is the single authoritative implementation; both {@link #getDescriptor(XmlTag)} and
     * {@link Fxml2ClassTagDescriptor#getElementDescriptor(XmlTag, XmlTag)} delegate here.
     * Results are cached per {@link XmlTag} and invalidated on any PSI modification.
     */
    public static @Nullable XmlElementDescriptor descriptorFor(@NotNull XmlTag tag, @NotNull XmlFile xmlFile) {
        return CachedValuesManager.getCachedValue(tag, DESCRIPTOR_CACHE, () -> {
            // Include the Kotlin modification tracker so that live edits to a Kotlin
            // code-behind (e.g. adding a property that becomes an FXML property-tag)
            // invalidate the per-tag descriptor immediately.
            PsiModificationTracker tracker =
                    PsiModificationTracker.getInstance(tag.getProject());
            com.intellij.lang.Language kotlinLang =
                    com.intellij.lang.Language.findLanguageByID("kotlin");
            Object[] deps = kotlinLang != null
                    ? new Object[]{xmlFile, tracker.forLanguage(JavaLanguage.INSTANCE),
                                   tracker.forLanguage(kotlinLang)}
                    : new Object[]{xmlFile, tracker.forLanguage(JavaLanguage.INSTANCE)};
            return CachedValueProvider.Result.create(descriptorForImpl(tag, xmlFile), deps);
        });
    }

    private static @Nullable XmlElementDescriptor descriptorForImpl(
            @NotNull XmlTag tag, @NotNull XmlFile xmlFile) {
        String localName = tag.getLocalName();

        // The synthetic wrapper root added for @ComponentView embedded FXML2; let the XML layer
        // treat it as an unknown element without interference from our descriptor logic.
        if (Fxml2EmbeddedUtil.isWrapperRoot(tag)) {
            return null;
        }

        // fx:-namespace tags are always handled as permissive class descriptors.
        if (localName.isEmpty() || Fxml2ImportResolver.FXML2_NAMESPACE.equals(tag.getNamespace())) {
            return new Fxml2ClassTagDescriptor(localName, tag);
        }

        // 1. Try to resolve as a Java class via imports.
        PsiClass resolvedClass = Fxml2ImportResolver.resolve(localName, xmlFile);
        if (resolvedClass != null) {
            return new Fxml2ClassTagDescriptor(localName, resolvedClass);
        }

        // 2. Try to resolve as a property of the parent: this is the compiler's
        //    ObjectToPropertyTransform path.  resolveAsPropertyDescriptor returns a
        //    Fxml2PropertyTagDescriptor only when it can actually anchor the name to
        //    a parent class; otherwise it still returns a lenient descriptor.
        //    We probe whether the name is a genuine property by checking the parent class.
        XmlElementDescriptor propertyDescriptor =
                Fxml2TagResolver.resolveAsPropertyDescriptor(tag, localName, xmlFile);

        // If resolveAsPropertyDescriptor found a real parent class context, use it.
        // We detect this by checking whether the resolved context class is non-null.
        PsiClass contextClass = Fxml2TagResolver.resolveContextClass(tag, xmlFile);
        if (contextClass != null) {
            // The parent context is known. Check if this name is actually a property.
            // For dotted names, resolveAsPropertyDescriptor already handles the resolution;
            // for simple names, check directly.
            boolean isProperty = localName.contains(".")
                    || isKnownProperty(contextClass, localName);
            if (isProperty) {
                return propertyDescriptor;
            }
        }

        // 3. Name is not a resolvable class and not a known property of the parent.
        //    Return a permissive class descriptor (null PsiClass) so IntelliJ's XML attribute
        //    checker is silent. The tag annotator will report the resolution error.
        return new Fxml2ClassTagDescriptor(localName, tag);
    }

    /**
     * Returns {@code true} if {@code name} resolves as an instance property, NamedArg
     * parameter, or static property notation on {@code parentClass}.
     */
    private static boolean isKnownProperty(@NotNull PsiClass parentClass,
                                            @NotNull String name) {
        // Instance property (getter/setter)?
        if (org.jfxcore.fxml.resolve.Fxml2PropertyResolver
                .resolveInstanceProperty(parentClass, name) != null) {
            return true;
        }
        // @NamedArg parameter name?
        if (org.jfxcore.fxml.resolve.Fxml2NamedArgResolver.hasNamedArgConstructor(parentClass)) {
            for (var ctor : parentClass.getConstructors()) {
                if (!ctor.hasModifierProperty(com.intellij.psi.PsiModifier.PUBLIC)) continue;
                if (!org.jfxcore.fxml.resolve.Fxml2NamedArgResolver
                        .isFullyAnnotatedNamedArgs(ctor)) continue;
                for (var param : ctor.getParameterList().getParameters()) {
                    if (name.equals(org.jfxcore.fxml.resolve.Fxml2NamedArgResolver
                            .namedArgValue(param))) return true;
                }
            }
        }
        return false;
    }
}
