package org.jfxcore.fxml.resolve;

import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlTag;
import com.intellij.xml.XmlElementDescriptor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jfxcore.fxml.descriptors.Fxml2ClassTagDescriptor;
import org.jfxcore.fxml.descriptors.Fxml2PropertyTagDescriptor;

/**
 * Central helper that mirrors the FXML compiler's {@code ObjectToPropertyTransform} /
 * {@code Resolver.tryResolveProperty} logic for deciding whether a child XML tag represents
 * a Java class or a property of the parent element's class.
 *
 * <p>All five notations from the FXML property-notation spec are handled:
 * <ol>
 *   <li><b>Short element notation</b>: {@code <text>} inside {@code <Button>}:
 *       plain name that doesn't resolve to a class -> instance property on parent.</li>
 *   <li><b>Qualified element notation</b>: {@code <Button.text>} inside {@code <Button>}:
 *       prefix resolves to a class AND parent is a subtype -> instance property.</li>
 *   <li><b>Fully-qualified qualified notation</b>: {@code <javafx.scene.control.Button.text>}:
 *       same as above, but the prefix is a fully-qualified class name.</li>
 *   <li><b>Static property element notation</b>: {@code <VBox.margin>} inside {@code <Button>}:
 *       prefix resolves to a class but parent is NOT a subtype -> static property.</li>
 *   <li><b>Chained property element notation</b>: {@code <selectionModel.selectionMode>}:
 *       prefix doesn't resolve to a class -> progressively resolve as instance property chain.</li>
 * </ol>
 *
 * <p><b>Priority</b> (mirrors the compiler): class tag &gt; qualified/static property &gt;
 * chained instance property.  Class resolution (which beats everything) is done by callers
 * before invoking this class.
 */
public final class Fxml2TagResolver {

    private Fxml2TagResolver() {}

    /**
     * Attempts to build a {@link Fxml2PropertyTagDescriptor} for a tag whose name did NOT
     * resolve to a known Java class.  Handles all dotted and undotted property notations.
     *
     * @param tag       the child XML tag being resolved
     * @param localName the local (non-namespace) tag name
     * @param xmlFile   the containing FXML file (used for import resolution)
     * @return a property descriptor, or {@code null} if the tag is a root element
     */
    public static @NotNull XmlElementDescriptor resolveAsPropertyDescriptor(
            @NotNull XmlTag tag,
            @NotNull String localName,
            @NotNull XmlFile xmlFile) {

        PsiClass parentClass = resolveParentClass(tag);

        int lastDot = localName.lastIndexOf('.');
        if (lastDot > 0) {
            String prefixPart = localName.substring(0, lastDot);
            String propPart   = localName.substring(lastDot + 1);
            if (!propPart.isEmpty()) {
                PsiClass prefixClass = Fxml2ImportResolver.resolve(prefixPart, xmlFile);
                if (prefixClass != null) {
                    // Prefix resolved to a class.
                    // Check: is the parent class a subtype of the prefix class?
                    //   YES -> qualified instance property notation: <Button.text> inside <Button>
                    //   NO  -> static property notation:            <VBox.margin>  inside <Button>
                    if (parentClass != null && isSubtype(parentClass, prefixClass)) {
                        // Qualified instance property: resolve against parent class, isStatic=false
                        return new Fxml2PropertyTagDescriptor(parentClass, propPart, localName, false);
                    } else {
                        // Static property on prefixClass, isStatic=true
                        return new Fxml2PropertyTagDescriptor(prefixClass, propPart, localName, true);
                    }
                }
                // Prefix did NOT resolve to a class -> fall through to chained instance property
            }
        }

        // No dot, or dotted prefix is not a class:
        // treat as (possibly chained) instance property on the parent element's class.
        if (parentClass == null) {
            // Parent class unknown: be lenient to avoid false positives.
            return new Fxml2PropertyTagDescriptor(null, localName);
        }

        // Chained instance property (e.g. selectionModel.selectionMode):
        // walk each dot-segment to find the final ownerClass and propertyName for the descriptor,
        // so that getDeclaration() resolves to the right method (selectionMode on SelectionModel).
        if (lastDot > 0) {
            String[] segments = localName.split("\\.", -1);
            PsiClass current = parentClass;
            for (int i = 0; i < segments.length - 1; i++) {
                if (current == null) return new Fxml2PropertyTagDescriptor(null, localName);
                PsiElement decl = Fxml2PropertyResolver.resolveInstanceProperty(current, segments[i]);
                if (decl == null) return new Fxml2PropertyTagDescriptor(null, localName);
                current = Fxml2PropertyResolver.resolvePropertyType(decl);
            }
            String lastSeg = segments[segments.length - 1];
            return new Fxml2PropertyTagDescriptor(current, lastSeg, localName, false);
        }

        // Return a descriptor unconditionally (lenient): we always want to suppress IntelliJ's
        // own generic "Cannot resolve symbol" and let our annotator report errors instead.
        return new Fxml2PropertyTagDescriptor(parentClass, localName);
    }

    /**
     * Resolves a property element tag to the {@link PsiElement} declaration for Ctrl+click
     * navigation.  Handles all property notations symmetrically with
     * {@link #resolveAsPropertyDescriptor}.
     *
     * @param tag       the property tag
     * @param localName the local tag name
     * @param xmlFile   the containing FXML file
     * @return the navigation target (setter / getter / static setter), or {@code null}
     */
    public static @Nullable PsiElement resolvePropertyTagDeclaration(
            @NotNull XmlTag tag,
            @NotNull String localName,
            @NotNull XmlFile xmlFile) {

        PsiClass parentClass = resolveParentClass(tag);

        int lastDot = localName.lastIndexOf('.');
        if (lastDot > 0) {
            String prefixPart = localName.substring(0, lastDot);
            String propPart   = localName.substring(lastDot + 1);
            if (!propPart.isEmpty()) {
                PsiClass prefixClass = Fxml2ImportResolver.resolve(prefixPart, xmlFile);
                if (prefixClass != null) {
                    if (parentClass != null && isSubtype(parentClass, prefixClass)) {
                        // Qualified instance property: navigate to the instance property on parent
                        return Fxml2PropertyResolver.resolveInstanceProperty(parentClass, propPart);
                    } else {
                        // Static property
                        return Fxml2PropertyResolver.resolveStaticProperty(prefixClass, propPart);
                    }
                }
            }
            // Dotted prefix not a class -> chained instance property, fall through
        }

        if (parentClass == null) return null;

        // Chained instance property (no class prefix resolved): e.g. selectionModel.selectionMode
        // Walk each segment and resolve against the progressively-unwrapped type.
        if (lastDot > 0) {
            String[] segments = localName.split("\\.", -1);
            PsiClass current = parentClass;
            PsiElement lastDecl = null;
            for (String seg : segments) {
                if (current == null) return lastDecl;
                lastDecl = Fxml2PropertyResolver.resolveInstanceProperty(current, seg);
                if (lastDecl == null) return null;
                current = Fxml2PropertyResolver.resolvePropertyType(lastDecl);
            }
            return lastDecl;
        }

        return Fxml2PropertyResolver.resolveInstanceProperty(parentClass, localName);
    }


    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    /**
     * Obtains the {@link PsiClass} for the immediate parent XML tag by consulting
     * its descriptor.
     */
    public static @Nullable PsiClass resolveParentClass(@NotNull XmlTag tag) {
        XmlTag parentTag = tag.getParentTag();
        if (parentTag == null) return null;
        XmlElementDescriptor desc = parentTag.getDescriptor();
        if (desc instanceof Fxml2ClassTagDescriptor cd) {
            return cd.getPsiClass();
        }
        // If the parent itself is a property tag (e.g. items inside a list property),
        // we don't have enough type information here, so be lenient.
        return null;
    }

    /**
     * Resolves the {@link PsiClass} that owns the given {@code tag} as a child element,
     * <em>without</em> calling {@link XmlTag#getDescriptor()}.
     *
     * <p>The method walks up the ancestor chain to find the nearest ancestor whose local name
     * resolves directly to a Java class via imports.  It then follows the intervening
     * property-tag segments downward to arrive at the type that the immediate parent of
     * {@code tag} represents.  This is used by the annotator to determine the context class
     * for validating a child tag without re-entering the descriptor pipeline.
     *
     * <p>When the property chain is structurally valid (all segments resolve as properties) but
     * the final property's return type cannot be determined (e.g. a raw or generic type), the
     * method returns the last successfully resolved class rather than {@code null}.  This ensures
     * the annotator still reports an error for an unresolvable class tag name.
     *
     * <p>Returns {@code null} only when the parent context itself is unresolvable (the ancestor
     * class doesn't exist, or a property in the chain doesn't exist on its owner type).
     *
     * @param tag     the tag whose parent context we want to resolve
     * @param xmlFile the containing FXML file
     * @return the resolved context {@link PsiClass}, or {@code null} if unresolvable
     */
    public static @Nullable PsiClass resolveContextClass(@NotNull XmlTag tag, @NotNull XmlFile xmlFile) {
        XmlTag parentTag = tag.getParentTag();
        if (parentTag == null) return null;

        // Fast path: parent resolves directly as a class.
        PsiClass parentClass = Fxml2ImportResolver.resolve(parentTag.getLocalName(), xmlFile);
        if (parentClass != null) return parentClass;

        // Walk up until we find an ancestor that IS a class tag.
        // Collect the property-tag segments on the way up (in reverse order).
        java.util.Deque<String> propertyChain = new java.util.ArrayDeque<>();
        XmlTag cursor = parentTag;
        PsiClass ancestorClass = null;

        while (cursor != null) {
            String name = cursor.getLocalName();
            if (Fxml2ImportResolver.FXML2_NAMESPACE.equals(cursor.getNamespace())) {
                cursor = cursor.getParentTag();
                continue;
            }
            // Stop at the synthetic wrapper root for embedded FXML, it is not a real class tag.
            if (org.jfxcore.fxml.lang.Fxml2EmbeddedUtil.isWrapperRoot(cursor)) {
                break;
            }
            PsiClass resolved = Fxml2ImportResolver.resolve(name, xmlFile);
            if (resolved != null) {
                ancestorClass = resolved;
                break;
            }
            // This ancestor is a property segment, push its name onto the chain.
            propertyChain.push(name);
            cursor = cursor.getParentTag();
        }

        if (ancestorClass == null) return null;
        if (propertyChain.isEmpty()) return ancestorClass;

        // Walk the property chain downward from ancestorClass to arrive at the parent's type.
        PsiClass current = ancestorClass;
        while (!propertyChain.isEmpty()) {
            String seg = propertyChain.pop();

            // A dotted segment may be a static property tag (e.g. "Interaction.behaviors" where
            // "Interaction" resolves to a class and "behaviors" is a static accessor on it), or a
            // qualified instance property (e.g. "Button.text" where Button is the current type),
            // or an instance property chain (e.g. "selectionModel.selectionMode").
            // Try the class-prefix interpretation first; fall through to the instance-chain case.
            int lastDotIdx = seg.lastIndexOf('.');
            if (lastDotIdx > 0) {
                String prefixPart = seg.substring(0, lastDotIdx);
                String propPart   = seg.substring(lastDotIdx + 1);
                PsiClass prefixClass = Fxml2ImportResolver.resolve(prefixPart, xmlFile);
                if (prefixClass != null) {
                    PsiElement decl;
                    if (isSubtype(current, prefixClass)) {
                        // Qualified instance property: e.g. <Button.text> inside <Button>
                        decl = Fxml2PropertyResolver.resolveInstanceProperty(current, propPart);
                    } else {
                        // Static property: e.g. <Interaction.behaviors> inside <ListView>
                        decl = Fxml2PropertyResolver.resolveStaticProperty(prefixClass, propPart);
                    }
                    if (decl == null) return null;
                    PsiClass next = Fxml2PropertyResolver.resolvePropertyType(decl);
                    if (next == null) return current;
                    current = next;
                    continue;
                }
            }

            // No class prefix: treat as an instance property chain
            // (e.g. "selectionModel.selectionMode" pushed as a single dotted segment).
            String[] parts = seg.split("\\.", -1);
            for (String part : parts) {
                PsiElement decl = Fxml2PropertyResolver.resolveInstanceProperty(current, part);
                if (decl == null) return null;
                PsiClass next = Fxml2PropertyResolver.resolvePropertyType(decl);
                if (next == null) return current;
                current = next;
            }
        }
        return current;
    }


    /**
     * Returns {@code true} if {@code candidate} is the same class as, or a subtype of,
     * {@code superType}, using IntelliJ's PSI type hierarchy (mirrors the compiler's
     * {@code TypeInstance.subtypeOf}).
     */
    public static boolean isSubtype(@NotNull PsiClass candidate, @NotNull PsiClass superType) {
        if (candidate.equals(superType)) return true;
        String superFqn = superType.getQualifiedName();
        if (superFqn == null) return false;
        return candidate.isInheritor(superType, true);
    }
}
