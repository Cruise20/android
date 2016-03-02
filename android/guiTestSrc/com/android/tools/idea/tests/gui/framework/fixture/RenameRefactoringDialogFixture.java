/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.tools.idea.tests.gui.framework.fixture;

import com.android.tools.lint.detector.api.TextFormat;
import com.intellij.refactoring.rename.RenameDialog;
import com.intellij.refactoring.ui.ConflictsDialog;
import com.intellij.ui.EditorTextField;
import org.fest.swing.core.Robot;
import org.fest.swing.core.matcher.JTextComponentMatcher;
import org.fest.swing.edt.GuiActionRunner;
import org.fest.swing.edt.GuiQuery;
import org.fest.swing.edt.GuiTask;
import org.fest.swing.timing.Condition;
import org.jetbrains.annotations.NotNull;

import javax.swing.text.JTextComponent;
import java.awt.event.KeyEvent;
import java.io.File;
import java.util.regex.Pattern;

import static com.android.tools.idea.tests.gui.framework.GuiTests.SHORT_TIMEOUT;
import static com.android.tools.idea.tests.gui.framework.GuiTests.findAndClickButton;
import static org.fest.swing.timing.Pause.pause;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class RenameRefactoringDialogFixture extends IdeaDialogFixture<RenameDialog> {
  @NotNull
  public static RenameRefactoringDialogFixture find(@NotNull Robot robot) {
    return new RenameRefactoringDialogFixture(robot, find(robot, RenameDialog.class));
  }

  private RenameRefactoringDialogFixture(@NotNull Robot robot, @NotNull DialogAndWrapper<RenameDialog> dialogAndWrapper) {
    super(robot, dialogAndWrapper);
  }

  @NotNull
  public RenameRefactoringDialogFixture setNewName(@NotNull final String newName) {
    final EditorTextField field = robot().finder().findByType(target(), EditorTextField.class);
    GuiActionRunner.execute(new GuiTask() {
      @Override
      protected void executeInEDT() throws Throwable {
        field.requestFocus();
      }
    });
    robot().pressAndReleaseKey(KeyEvent.VK_BACK_SPACE); // to make sure we don't append to existing item on Linux
    robot().enterText(newName);
    pause(new Condition("EditorTextField to show new name") {
      @Override
      public boolean test() {
        return newName.equals(field.getText());
      }
    }, SHORT_TIMEOUT);
    return this;
  }

  @NotNull
  public RenameRefactoringDialogFixture clickRefactor() {
    findAndClickButton(this, "Refactor");
    return this;
  }

  public static class ConflictsDialogFixture extends IdeaDialogFixture<ConflictsDialog> {
    protected ConflictsDialogFixture(@NotNull Robot robot, @NotNull DialogAndWrapper<ConflictsDialog> dialogAndWrapper) {
      super(robot, dialogAndWrapper);
    }

    @NotNull
    public static ConflictsDialogFixture find(@NotNull Robot robot) {
      return new ConflictsDialogFixture(robot, find(robot, ConflictsDialog.class));
    }

    @NotNull
    public ConflictsDialogFixture clickContinue() {
      findAndClickButton(this, "Continue");
      return this;
    }

    public String getHtml() {
      final JTextComponent component = robot().finder().find(target(), JTextComponentMatcher.any());
      return GuiActionRunner.execute(new GuiQuery<String>() {
        @Override
        protected String executeInEDT() throws Throwable {
          return component.getText();
        }
      });
    }

    public String getText() {
      String html = getHtml();
      String text = TextFormat.HTML.convertTo(html, TextFormat.TEXT).trim();
      return text.replace(File.separatorChar, '/');
    }

    public void requireMessageText(@NotNull String text) {
      assertEquals(text, getText());
    }

    public void requireMessageTextContains(@NotNull String text) {
      assertTrue(getText() + " does not contain expected message fragment " + text, getText().contains(text));
    }

    public void requireMessageTextMatches(@NotNull String regexp) {
      assertTrue(getText() + " does not match " + regexp, Pattern.matches(regexp, getText()));
    }
  }
}
