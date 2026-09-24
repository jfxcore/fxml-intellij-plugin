// Copyright (c) 2026, JFXcore. All rights reserved.
// Use of this source code is governed by the BSD-3-Clause license.

package org.jfxcore.fxml;

import com.intellij.lang.injection.InjectedLanguageManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.util.TextRange;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies the shared document-form fixture and its coordinate mapping. */
class Fxml2DocumentFormTest extends Fxml2TestBase {

    @BeforeAll
    void addTestClasses() {
        installComponentViewAnnotation();
    }

    @ParameterizedTest
    @EnumSource(Fxml2DocumentForm.class)
    void exposesMarkupPayloadHostAndHostRange(Fxml2DocumentForm form) {
        form.configure(getFixture(), """
                <?resource data.json application/json:{"ready": true}?>
                """, "");

        ReadAction.run(() -> {
            assertTrue(form.host(getFixture()).isValidHost());
            assertTrue(form.markupFile(getFixture()).getText().contains("<?resource data.json"));

            var payload = form.payloadFile(getFixture());
            assertEquals("{\"ready\": true}", payload.getText());

            TextRange hostRange = form.hostRange(payload, TextRange.create(0, payload.getTextLength()));
            String hostText = InjectedLanguageManager.getInstance(getFixture().getProject())
                    .getTopLevelFile(payload).getText();
            assertEquals("{\"ready\": true}", hostRange.substring(hostText));
        });
    }
}
