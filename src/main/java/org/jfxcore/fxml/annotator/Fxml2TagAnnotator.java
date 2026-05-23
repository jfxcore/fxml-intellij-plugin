package org.jfxcore.fxml.annotator;

import com.intellij.lang.annotation.AnnotationHolder;
import com.intellij.lang.annotation.Annotator;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlTag;
import com.intellij.psi.xml.XmlToken;
import com.intellij.psi.xml.XmlTokenType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jfxcore.fxml.codeinsight.Fxml2AddImportFix;
import org.jfxcore.fxml.lang.Fxml2EmbeddedUtil;
import org.jfxcore.fxml.lang.Fxml2FileType;

import org.jfxcore.fxml.resolve.Fxml2ImportResolver;
import org.jfxcore.fxml.resolve.Fxml2NamedArgResolver;
import org.jfxcore.fxml.resolve.Fxml2PropertyResolver;
import org.jfxcore.fxml.resolve.Fxml2TagResolver;

import java.util.Set;

/**
 * Annotates unresolvable class and property element tags in FXML 2.0 files.
 *
 * <p>Because {@link org.jfxcore.fxml.lang.Fxml2XmlExtension.Fxml2TagNameReference#isSoft()}
 * returns {@code true}, IntelliJ's built-in XML reference inspection never fires for FXML 2.0 tags.
 * This annotator is therefore the sole source of tag-name errors, ensuring:
 * <ul>
 *   <li>Each error is reported exactly once (visiting the tag, not the start/end name tokens).</li>
 *   <li>Only the unresolvable part of the name is underlined.</li>
 *   <li>The error message names the type on which resolution actually failed.</li>
 * </ul>
 *
 * <p><b>No calls to {@link XmlTag#getDescriptor()} are made here.</b> That call re-enters the
 * descriptor/reference-provider pipeline while the highlighting pass is still running, causing
 * a {@code ProcessCanceledException} retry loop. All resolution is done directly via
 * {@link Fxml2ImportResolver} and {@link Fxml2PropertyResolver}.
 */
public final class Fxml2TagAnnotator implements Annotator {

    /** Known fx: element names, mirroring the compiler's Intrinsics list. */
    private static final Set<String> KNOWN_FX_ELEMENT_NAMES = Set.of(
            "Null", "True", "False", "subclass", "classModifier", "classParameters", "className",
            "context", "id", "value", "constant", "factory", "typeArguments", "itemType",
            "define", "Class", "Evaluate", "Observe", "Push", "Synchronize");

    @Override
    public void annotate(@NotNull PsiElement element, @NotNull AnnotationHolder holder) {
        if (!(element instanceof XmlTag tag)) return;
        if (!(tag.getContainingFile() instanceof XmlFile xmlFile)) return;
        if (!Fxml2FileType.isFxml2(xmlFile)) return;
        annotateTag(tag, xmlFile, holder);
    }

    private static void annotateTag(
            @NotNull XmlTag tag, @NotNull XmlFile xmlFile, @NotNull AnnotationHolder holder) {
        // Synthetic wrapper root injected for @ComponentView embedded FXML 2.0; never annotate it.
        if (Fxml2EmbeddedUtil.isWrapperRoot(tag)) return;
        if (Fxml2ImportResolver.FXML2_NAMESPACE.equals(tag.getNamespace())) {
            String fxLocalName = tag.getLocalName();
            if (!fxLocalName.isEmpty() && !KNOWN_FX_ELEMENT_NAMES.contains(fxLocalName)) {
                XmlToken tok = findStartTagNameToken(tag);
                if (tok != null) {
                    holder.newAnnotation(HighlightSeverity.ERROR, "Unknown intrinsic: " + fxLocalName)
                          .range(tok)
                          .create();
                }
            }
            return;
        }

        String localName = tag.getLocalName();
        if (localName.isEmpty()) return;

        // If the name resolves directly to a class, it is a valid class tag, no error.
        if (Fxml2ImportResolver.resolve(localName, xmlFile) != null) return;

        XmlTag parentTag = tag.getParentTag();
        if (parentTag == null || Fxml2EmbeddedUtil.isWrapperRoot(parentTag)) {
            // Treat a wrapper-root parent the same as no parent: the tag is at the top
            // of the user markup in an embedded FXML 2.0 fragment.
            errorWithFix(holder, tag, "Cannot resolve symbol '" + localName + "'",
                    !localName.contains(".") ? new Fxml2AddImportFix(localName) : null);
            return;
        }

        // Children of <fx:context> are always class instantiations; report an error with an
        // auto-import fix when the class name cannot be resolved.
        if ("context".equals(parentTag.getLocalName())
                && Fxml2ImportResolver.FXML2_NAMESPACE.equals(parentTag.getNamespace())) {
            errorWithFix(holder, tag, "Cannot resolve symbol '" + localName + "'",
                    !localName.contains(".") ? new Fxml2AddImportFix(localName) : null);
            return;
        }

        // If the parent is an fx:-namespace element (e.g. <fx:define>, <fx:Evaluate>, etc.),
        // this tag is always a class-instantiation: skip property resolution.
        if (Fxml2ImportResolver.FXML2_NAMESPACE.equals(parentTag.getNamespace())) {
            return;
        }

        // For dotted names where the prefix resolves to a known class (e.g. "Interaction.behaviors",
        // "VBox.margin"), validate the property independently of the parent class (static property
        // resolution doesn't depend on the parent being resolved.
        int lastDot = localName.lastIndexOf('.');
        if (lastDot > 0) {
            String prefixPart = localName.substring(0, lastDot);
            String propPart   = localName.substring(lastDot + 1);
            PsiClass prefixClass = Fxml2ImportResolver.resolve(prefixPart, xmlFile);
            if (prefixClass != null) {
                // We have a known prefix class: resolve as static or instance property.
                // To determine static vs instance we need parentClass, but if it's null
                // (unresolved ancestor) we default to static resolution for the prefix class.
                PsiClass parentClass2 = Fxml2TagResolver.resolveContextClass(tag, xmlFile);
                PsiClass ownerClass;
                boolean isStatic;
                if (parentClass2 != null && Fxml2TagResolver.isSubtype(parentClass2, prefixClass)) {
                    ownerClass = parentClass2;
                    isStatic = false;
                } else {
                    ownerClass = prefixClass;
                    isStatic = true;
                }
                PsiElement decl = isStatic
                        ? Fxml2PropertyResolver.resolveStaticProperty(ownerClass, propPart)
                        : Fxml2PropertyResolver.resolveInstanceProperty(ownerClass, propPart);
                if (decl == null) {
                    errorOnSuffix(holder, tag, lastDot,
                            "'" + propPart + "' in " + ownerClass.getQualifiedName() + " cannot be resolved");
                }
                return;
            }
        }

        // Determine whether the immediate parent is a property tag or a class tag.
        boolean parentIsPropertyTag =
                Fxml2ImportResolver.resolve(parentTag.getLocalName(), xmlFile) == null
                && !Fxml2ImportResolver.FXML2_NAMESPACE.equals(parentTag.getNamespace());

        if (parentIsPropertyTag) {
            // Inside a property tag -> child is expected to be a class (value object).
            // Suppress only when the property tag itself is unresolved (cascade error avoidance):
            // if we can't determine the property's type, we can't know whether the child name
            // is valid, so stay silent to avoid false positives.
            // But when the parent property IS resolvable, the child must be a known class.
            PsiClass propertyContext = Fxml2TagResolver.resolveContextClass(tag, xmlFile);
            if (propertyContext != null) {
                // Parent property resolved: child name must be a valid class.
                errorWithFix(holder, tag, "Cannot resolve symbol '" + localName + "'",
                        !localName.contains(".") ? new Fxml2AddImportFix(localName) : null);
            }
            // propertyContext == null -> parent property is itself unresolved, stay silent.
            return;
        }

        // Resolve the context class for this tag's parent, walking up the ancestor chain.
        // Deliberately avoids getDescriptor(): see class javadoc.
        PsiClass parentClass = Fxml2TagResolver.resolveContextClass(tag, xmlFile);
        if (parentClass == null) {
            // Context is unresolvable: stay silent to avoid cascade errors.
            return;
        }

        if (lastDot > 0) {
            // prefixClass is null here (already handled above when non-null)
            int firstDot = localName.indexOf('.');
            int chainStart = 0;
            if (firstDot > 0) {
                String firstSeg = localName.substring(0, firstDot);
                PsiClass firstClass = Fxml2ImportResolver.resolve(firstSeg, xmlFile);
                if (firstClass != null && Fxml2TagResolver.isSubtype(parentClass, firstClass)) {
                    chainStart = firstDot + 1;
                }
            }
            reportChainError(holder, tag, localName, chainStart, parentClass);
            return;
        }

        // Plain name inside a class tag. Try as a property of the parent class.
        if (Fxml2PropertyResolver.resolveInstanceProperty(parentClass, localName) == null) {
            // Also accept @NamedArg parameter names used in element notation.
            if (Fxml2NamedArgResolver.hasNamedArgConstructor(parentClass)) {
                for (var ctor : parentClass.getConstructors()) {
                    if (!ctor.hasModifierProperty(com.intellij.psi.PsiModifier.PUBLIC)) continue;
                    if (!Fxml2NamedArgResolver.isFullyAnnotatedNamedArgs(ctor)) continue;
                    for (var param : ctor.getParameterList().getParameters()) {
                        if (localName.equals(Fxml2NamedArgResolver.namedArgValue(param))) return;
                    }
                }
            }
            errorWithFix(holder, tag, "Cannot resolve symbol '" + localName + "'",
                    new Fxml2AddImportFix(localName));
        }
    }

    /**
     * Walks the dot-separated segments of {@code localName} starting at character offset
     * {@code startOffset}, resolving each as an instance property on the running type, and
     * reports an error on the first segment that fails to resolve, naming the type at that point.
     */
    private static void reportChainError(
            @NotNull AnnotationHolder holder,
            @NotNull XmlTag tag,
            @NotNull String localName,
            int startOffset,
            @NotNull PsiClass startClass) {

        String chain = localName.substring(startOffset);
        String[] segments = chain.split("\\.", -1);
        PsiClass current = startClass;
        int offset = startOffset; // absolute offset within localName of the current segment start

        for (int i = 0; i < segments.length; i++) {
            String seg = segments[i];
            PsiElement decl = Fxml2PropertyResolver.resolveInstanceProperty(current, seg);
            if (decl == null) {
                errorOnSuffix(holder, tag, offset > 0 ? offset - 1 : -1,
                        "'" + seg + "' in " + current.getQualifiedName() + " cannot be resolved");
                return;
            }
            PsiClass next = Fxml2PropertyResolver.resolvePropertyType(decl);
            if (next == null || i == segments.length - 1) return;
            current = next;
            offset += seg.length() + 1;
        }
    }

    // -----------------------------------------------------------------------
    // Annotation helpers
    // -----------------------------------------------------------------------


    /** Reports an error with an optional quick fix. */
    private static void errorWithFix(
            @NotNull AnnotationHolder holder, @NotNull XmlTag tag,
            @NotNull String message, @Nullable Fxml2AddImportFix fix) {
        XmlToken tok = findStartTagNameToken(tag);
        if (tok != null) {
            var builder = holder.newAnnotation(HighlightSeverity.ERROR, message).range(tok);
            if (fix != null) builder = builder.withFix(fix);
            builder.create();
        }
    }

    /**
     * Reports an error highlighting only the portion of the start-tag name token
     * that starts at {@code dotOffset + 1} (i.e., the segment after the last dot).
     * If {@code dotOffset < 0}, the whole token is highlighted.
     */
    private static void errorOnSuffix(
            @NotNull AnnotationHolder holder, @NotNull XmlTag tag,
            int dotOffset, @NotNull String message) {
        XmlToken tok = findStartTagNameToken(tag);
        if (tok == null) return;
        TextRange full = tok.getTextRange();
        TextRange range = dotOffset >= 0
                ? new TextRange(full.getStartOffset() + dotOffset + 1, full.getEndOffset())
                : full;
        holder.newAnnotation(HighlightSeverity.ERROR, message).range(range).create();
    }

    /** Finds the {@code XML_NAME} token that is the start-tag name of {@code tag}. */
    private static @Nullable XmlToken findStartTagNameToken(@NotNull XmlTag tag) {
        for (PsiElement child = tag.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof XmlToken t && t.getTokenType() == XmlTokenType.XML_NAME) {
                return t;
            }
        }
        return null;
    }
}
