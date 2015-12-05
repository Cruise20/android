/*
 * Copyright (C) 2015 The Android Open Source Project
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
package org.jetbrains.android.dom.animator;

import com.intellij.util.xml.DefinesXml;
import com.intellij.util.xml.SubTagList;
import org.jetbrains.android.dom.Styleable;

import java.util.List;

@Styleable("AnimatorSet")
@DefinesXml
public interface Set extends AnimatorElement {
  @SubTagList("objectAnimator")
  List<ObjectAnimator> getObjectAnimators();

  @SubTagList("animator")
  List<Animator> getAnimators();

  @SubTagList("set")
  List<Set> getSets();

  @SubTagList("propertyValuesHolder")
  List<PropertyValuesHolder> getPropertyValuesHolders();
}
