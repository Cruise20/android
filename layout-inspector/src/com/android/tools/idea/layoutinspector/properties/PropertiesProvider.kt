/*
 * Copyright (C) 2019 The Android Open Source Project
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
package com.android.tools.idea.layoutinspector.properties

import com.android.SdkConstants.ANDROID_URI
import com.android.tools.idea.layoutinspector.model.InspectorView
import com.android.tools.idea.layoutinspector.transport.InspectorClient
import com.android.tools.layoutinspector.proto.LayoutInspector
import com.android.tools.layoutinspector.proto.LayoutInspector.Property
import com.android.tools.layoutinspector.proto.LayoutInspector.Property.Type
import com.android.tools.property.panel.api.PropertiesTable
import com.google.common.collect.HashBasedTable
import com.google.common.collect.Table

private val INT32_FIELD_DESCRIPTOR = Property.getDescriptor().findFieldByNumber(Property.INT32_VALUE_FIELD_NUMBER)
private val INT64_FIELD_DESCRIPTOR = Property.getDescriptor().findFieldByNumber(Property.INT64_VALUE_FIELD_NUMBER)
private val DOUBLE_FIELD_DESCRIPTOR = Property.getDescriptor().findFieldByNumber(Property.DOUBLE_VALUE_FIELD_NUMBER)
private val FLOAT_FIELD_DESCRIPTOR = Property.getDescriptor().findFieldByNumber(Property.FLOAT_VALUE_FIELD_NUMBER)

class PropertiesProvider(private val model: InspectorPropertiesModel) {
  private val client: InspectorClient?
    get() = model.client

  fun requestProperties(view: InspectorView) {
    if (view.id.isEmpty()) {
      return
    }
    val id = view.id.toLong()
    val inspectorCommand = LayoutInspector.LayoutInspectorCommand.newBuilder()
      .setType(LayoutInspector.LayoutInspectorCommand.Type.GET_PROPERTIES)
      .setViewId(id)
      .build()
    client?.execute(inspectorCommand)
  }

  fun loadProperties(event: LayoutInspector.LayoutInspectorEvent, expectedView: InspectorView?): PropertiesTable<InspectorPropertyItem> {
    if (expectedView == null || expectedView.id.isEmpty() || event.properties.viewId != expectedView.id.toLong()) {
      return PropertiesTable.emptyTable()
    }
    val generator = Generator(event.properties, expectedView, model)
    return PropertiesTable.create(generator.generate())
  }

  private class Generator(
    private val properties: LayoutInspector.PropertyEvent,
    private val view: InspectorView,
    private val model: InspectorPropertiesModel
  ) {
    private val stringTable =  properties.stringList.associateBy({ it.id }, { it.str })
    private val table = HashBasedTable.create<String, String, InspectorPropertyItem>()
    // TODO: This probably should be retrieved from the module. Use the layout namespace for now:
    private val moduleNamespace = stringTable[properties.layout.namespace]
    private val layout = fromResource(properties.layout)

    fun generate(): Table<String, String, InspectorPropertyItem> {
      for (property in properties.propertyList) {
        val name = stringTable[property.name] ?: continue
        val isDeclared = property.source == properties.layout &&
                         property.source != LayoutInspector.Resource.getDefaultInstance()
        val source = fromResource(property.source)
        val value: String? = when (property.type) {
          Type.STRING -> stringTable[property.int32Value] ?: ""
          Type.BOOLEAN -> fromBoolean(property)?.toString()
          Type.BYTE,
          Type.CHAR,
          Type.GRAVITY,
          Type.INT_ENUM,
          Type.INT_FLAG,
          Type.INT16,
          Type.INT32 -> fromInt32(property)?.toString()
          Type.INT64 -> fromInt64(property)?.toString()
          Type.DOUBLE -> fromDouble(property)?.toString()
          Type.FLOAT -> fromFloat(property)?.toString()
          Type.RESOURCE -> fromResource(property.resourceValue)
          else -> ""
        }
        add(InspectorPropertyItem(ANDROID_URI, name, value, isDeclared, source, view, model))
      }
      return table
    }

    private fun fromBoolean(property: LayoutInspector.Property): Boolean? {
      val intValue = fromInt32(property) ?: return null
      return intValue != 0
    }

    private fun fromInt32(property: LayoutInspector.Property): Int? {
      val intValue = property.int32Value
      if (intValue == 0 && !property.hasField(INT32_FIELD_DESCRIPTOR)) {
        return null
      }
      return intValue
    }

    private fun fromInt64(property: LayoutInspector.Property): Long? {
      val intValue = property.int64Value
      if (intValue == 0L && !property.hasField(INT64_FIELD_DESCRIPTOR)) {
        return null
      }
      return intValue
    }

    private fun fromDouble(property: LayoutInspector.Property): Double? {
      val doubleValue = property.doubleValue
      if (doubleValue == 0.0 && !property.hasField(DOUBLE_FIELD_DESCRIPTOR)) {
        return null
      }
      return doubleValue
    }

    private fun fromFloat(property: LayoutInspector.Property): Float? {
      val floatValue = property.floatValue
      if (floatValue == 0.0f && !property.hasField(FLOAT_FIELD_DESCRIPTOR)) {
        return null
      }
      return floatValue
    }

    private fun fromResource(resource: LayoutInspector.Resource?): String? {
      if (resource == null) {
        return null
      }
      if (resource == properties.layout && layout != null) {
        return layout
      }
      val type = stringTable[resource.type] ?: return null
      val namespace = stringTable[resource.namespace] ?: return null
      val name = stringTable[resource.name] ?: return null
      if (namespace == moduleNamespace || namespace.isEmpty()) {
        return "@$type/$name"
      }
      return "@$namespace:$type/$name"
    }

    private fun add(item: InspectorPropertyItem) {
      table.put(item.namespace, item.name, item)
    }
  }
}