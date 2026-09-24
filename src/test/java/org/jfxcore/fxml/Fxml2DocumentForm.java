// Copyright (c) 2026, JFXcore. All rights reserved.
// Use of this source code is governed by the BSD-3-Clause license.

package org.jfxcore.fxml;

import com.intellij.lang.injection.InjectedLanguageManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiLanguageInjectionHost;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.xml.XmlFile;
import com.intellij.testFramework.fixtures.JavaCodeInsightTestFixture;
import org.jetbrains.annotations.NotNull;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/** Document forms in which the same FXML/2 behavior is supported. */
enum Fxml2DocumentForm {
    STANDALONE,
    JAVA,
    KOTLIN;

    void configure(@NotNull JavaCodeInsightTestFixture fixture,
                   @NotNull String prolog,
                   @NotNull String body) {
        String markup = """
                <?xml version="1.0" encoding="UTF-8"?>
                <?import javafx.scene.layout.BorderPane?>
                %s<BorderPane xmlns="http://javafx.com/javafx"
                            xmlns:fx="http://jfxcore.org/fxml/2.0">
                %s</BorderPane>
                """.formatted(prolog, body);

        switch (this) {
            case STANDALONE -> fixture.configureByText("TestView.fxml", markup);
            case JAVA -> fixture.configureByText("TestView.java", """
                    package test;
                    import org.jfxcore.markup.ComponentView;
                    import javafx.scene.layout.BorderPane;
                    @ComponentView(\"""
                    %s\""")
                    public class TestView extends BorderPane {
                        protected void initializeComponent() {}
                        public TestView() { initializeComponent(); }
                    }
                    """.formatted(markup.indent(4)));
            case KOTLIN -> fixture.configureByText("TestView.kt", """
                    package test
                    import org.jfxcore.markup.ComponentView
                    import javafx.scene.layout.BorderPane
                    @ComponentView(\"""
                    %s\""")
                    class TestView : BorderPane() {
                        protected fun initializeComponent() {}
                        init { initializeComponent() }
                    }
                    """.formatted(markup.indent(4)));
        }
    }

    @NotNull XmlFile markupFile(@NotNull JavaCodeInsightTestFixture fixture) {
        return ReadAction.compute(() -> {
            PsiFile file = fixture.getFile();
            if (file instanceof XmlFile xmlFile) return xmlFile;

            return injectedFragments(fixture).stream()
                    .map(fragment -> fragment.first)
                    .filter(XmlFile.class::isInstance)
                    .map(XmlFile.class::cast)
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("no FXML/2 markup fragment was injected"));
        });
    }

    @NotNull PsiLanguageInjectionHost host(@NotNull JavaCodeInsightTestFixture fixture) {
        return ReadAction.compute(() -> {
            PsiFile topLevelFile = InjectedLanguageManager.getInstance(fixture.getProject())
                    .getTopLevelFile(fixture.getFile());
            return PsiTreeUtil.findChildrenOfType(
                        topLevelFile, PsiLanguageInjectionHost.class).stream()
                .filter(PsiLanguageInjectionHost::isValidHost)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no document injection host was found"));
        });
    }

    @NotNull PsiFile payloadFile(@NotNull JavaCodeInsightTestFixture fixture) {
        return ReadAction.compute(() -> injectedFragments(fixture).stream()
                .map(fragment -> (PsiFile)fragment.first)
                .filter(file -> !(file instanceof XmlFile))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no resource payload fragment was injected")));
    }

    @NotNull TextRange hostRange(@NotNull PsiElement injectedElement,
                                 @NotNull TextRange injectedRange) {
        return ReadAction.compute(() -> InjectedLanguageManager.getInstance(injectedElement.getProject())
                .injectedToHost(injectedElement, injectedRange));
    }

    /** Returns every fragment injected into the document host, one entry per shred. */
    @NotNull List<Pair<PsiElement, TextRange>> injectedFragments(
            @NotNull JavaCodeInsightTestFixture fixture) {
        PsiLanguageInjectionHost host = host(fixture);
        List<Pair<PsiElement, TextRange>> fragments = InjectedLanguageManager
                .getInstance(host.getProject()).getInjectedPsiFiles(host);
        assertNotNull(fragments, "the document host has injected fragments");
        return fragments;
    }
}
