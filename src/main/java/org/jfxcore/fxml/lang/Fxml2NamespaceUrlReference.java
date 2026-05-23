package org.jfxcore.fxml.lang;

import com.intellij.ide.BrowserUtil;
import com.intellij.navigation.ItemPresentation;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiManager;
import com.intellij.psi.PsiReferenceBase;
import com.intellij.psi.impl.FakePsiElement;
import com.intellij.psi.xml.XmlAttributeValue;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.Icon;

/**
 * A soft {@link com.intellij.psi.PsiReference} on an FXML 2.0 namespace URI attribute value
 * (e.g. {@code "http://jfxcore.org/fxml/2.0"}).
 * Ctrl+click opens the corresponding documentation URL in the system browser.
 */
public final class Fxml2NamespaceUrlReference extends PsiReferenceBase<XmlAttributeValue> {

    private final String myUrl;

    public Fxml2NamespaceUrlReference(@NotNull XmlAttributeValue element, @NotNull String url) {
        super(element, new TextRange(1, element.getText().length() - 1), /* soft= */ true);
        this.myUrl = url;
    }

    @Override
    public @NotNull PsiElement resolve() {
        return new UrlNavigationTarget(getElement(), myUrl);
    }

    @Override
    public boolean isSoft() {
        return true;
    }

    public static final class UrlNavigationTarget extends FakePsiElement {

        private final PsiElement myParent;
        private final String myUrl;

        UrlNavigationTarget(@NotNull PsiElement parent, @NotNull String url) {
            this.myParent = parent;
            this.myUrl = url;
        }

        @Override
        public PsiElement getParent() {
            return myParent;
        }

        @Override
        public PsiManager getManager() {
            return myParent.getManager();
        }

        @Override
        public boolean canNavigate() {
            return true;
        }

        @Override
        public void navigate(boolean requestFocus) {
            BrowserUtil.browse(myUrl);
        }

        @Override
        public @NotNull ItemPresentation getPresentation() {
            return new ItemPresentation() {
                @Override
                public @NotNull String getPresentableText() {
                    return myUrl;
                }

                @Override
                public @Nullable Icon getIcon(boolean unused) {
                    return null;
                }
            };
        }

        @Override
        public String getName() {
            return myUrl;
        }
    }
}
