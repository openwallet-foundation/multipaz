/*
 * Copyright 2023 The Android Open Source Project
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

package org.multipaz.documenttype

/**
 * Enumeration of the different types of Document Attributes
 *
 * Attributes in documents can have different types and this
 * enumeration contains a type-system generic enough to be
 * used across various document formats. This is useful for
 * wallet and reader user interfaces which wants to provide UI
 * for inputting or displaying documents attributes.
 */
sealed class DocumentAttributeType {
    data object Blob : DocumentAttributeType()
    data object String : DocumentAttributeType()
    data object Number : DocumentAttributeType()
    data object Date : DocumentAttributeType()
    data object DateTime : DocumentAttributeType()
    data object Picture : DocumentAttributeType()
    data object Boolean : DocumentAttributeType()
    data object ComplexType : DocumentAttributeType()
    data class StringOptions(val options: List<StringOption>) : DocumentAttributeType()
    data class IntegerOptions(val options: List<IntegerOption>) : DocumentAttributeType()
}