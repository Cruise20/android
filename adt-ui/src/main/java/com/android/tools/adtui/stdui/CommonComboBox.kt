/*
 * Copyright (C) 2018 The Android Open Source Project
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
package com.android.tools.adtui.stdui

import com.android.tools.adtui.model.stdui.CommonComboBoxModel
import com.android.tools.adtui.model.stdui.CommonTextFieldModel
import com.android.tools.adtui.model.stdui.ValueChangedListener
import com.intellij.util.ui.JBUI
import java.awt.event.MouseEvent
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JTextField
import javax.swing.plaf.UIResource
import javax.swing.plaf.basic.BasicComboBoxEditor

/**
 * ComboBox controlled by a [CommonComboBoxModel].
 */
open class CommonComboBox<E, out M : CommonComboBoxModel<E>>(model: M) : JComboBox<E>(model) {

  init {
    // Override the editor with a CommonTextField such that the text is also controlled by the model.
    @Suppress("LeakingThis")
    super.setEditor(CommonComboBoxEditor(model, this))

    setFromModel()

    model.addListener(ValueChangedListener {
      updateFromModel()
      repaint()
    })
  }

  protected open fun updateFromModel() {
    setFromModel()
  }

  private fun setFromModel() {
    isEnabled = model.enabled
    if (isEditable != model.editable) {
      super.setEditable(model.editable)
    }
  }

  override fun updateUI() {
    super.updateUI()
    installDefaultRenderer()
  }

  override fun getModel(): M {
    @Suppress("UNCHECKED_CAST")
    return super.getModel() as M
  }

  // Install a default renderer in order to adjust the left margin of the popup.
  private fun installDefaultRenderer() {
    val renderer = getRenderer()
    if (renderer == null || renderer is UIResource) {
      setRenderer(CommonComboBoxRenderer.UIResource())
    }
  }

  private class CommonComboBoxEditor<out M : CommonTextFieldModel>(model: M, comboBox: JComponent) : BasicComboBoxEditor() {
    init {
      editor = TextFieldForComboBox(model, comboBox)
      editor.border = JBUI.Borders.empty()
    }

    override fun createEditorComponent(): JTextField? {
      // Hack: return null here, and override the editor in init.
      // We do this because this method is called by the constructor of the super class,
      // and the model parameter is not available at this point.
      return null
    }
  }

  private class TextFieldForComboBox<out M : CommonTextFieldModel>(model: M, private val comboBox: JComponent) : CommonTextField<M>(model) {
    override fun getToolTipText(): String? {
      return comboBox.toolTipText
    }

    override fun getToolTipText(event: MouseEvent?): String? {
      return comboBox.getToolTipText(event)
    }
  }
}
