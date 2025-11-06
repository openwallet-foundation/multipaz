/*
 * Copyright 2019 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#pragma once

#include "cppbor.h"

namespace cppbor {

using ParseResult = std::tuple<std::unique_ptr<Item> /* result */, const uint8_t* /* newPos */,
                               std::string /* errMsg */>;

/**
 * Parse the first CBOR data item (possibly compound) from the range [begin, end).
 *
 * Returns a tuple of Item pointer, buffer pointer and error message.  If parsing is successful, the
 * Item pointer is non-null, the buffer pointer points to the first byte after the
 * successfully-parsed item and the error message string is empty.  If parsing fails, the Item
 * pointer is null, the buffer pointer points to the first byte that was unparseable (the first byte
 * of a data item header that is malformed in some way, e.g. an invalid value, or a length that is
 * too large for the remaining buffer, etc.) and the string contains an error message describing the
 * problem encountered.
 */
ParseResult parse(const uint8_t* begin, const uint8_t* end);

/**
 * Parse the first CBOR data item (possibly compound) from the range [begin, end).
 *
 * Returns a tuple of Item pointer, buffer pointer and error message.  If parsing is successful, the
 * Item pointer is non-null, the buffer pointer points to the first byte after the
 * successfully-parsed item and the error message string is empty.  If parsing fails, the Item
 * pointer is null, the buffer pointer points to the first byte that was unparseable (the first byte
 * of a data item header that is malformed in some way, e.g. an invalid value, or a length that is
 * too large for the remaining buffer, etc.) and the string contains an error message describing the
 * problem encountered.
 *
 * The returned CBOR data item will contain View* items backed by
 * std::string_view types over the input range.
 * WARNING! If the input range changes underneath, the corresponding views will
 * carry the same change.
 */
ParseResult parseWithViews(const uint8_t* begin, const uint8_t* end);

/**
 * Parse the first CBOR data item (possibly compound) from the byte vector.
 *
 * Returns a tuple of Item pointer, buffer pointer and error message.  If parsing is successful, the
 * Item pointer is non-null, the buffer pointer points to the first byte after the
 * successfully-parsed item and the error message string is empty.  If parsing fails, the Item
 * pointer is null, the buffer pointer points to the first byte that was unparseable (the first byte
 * of a data item header that is malformed in some way, e.g. an invalid value, or a length that is
 * too large for the remaining buffer, etc.) and the string contains an error message describing the
 * problem encountered.
 */
inline ParseResult parse(const std::vector<uint8_t>& encoding) {
    return parse(encoding.data(), encoding.data() + encoding.size());
}

/**
 * Parse the first CBOR data item (possibly compound) from the range [begin, begin + size).
 *
 * Returns a tuple of Item pointer, buffer pointer and error message.  If parsing is successful, the
 * Item pointer is non-null, the buffer pointer points to the first byte after the
 * successfully-parsed item and the error message string is empty.  If parsing fails, the Item
 * pointer is null, the buffer pointer points to the first byte that was unparseable (the first byte
 * of a data item header that is malformed in some way, e.g. an invalid value, or a length that is
 * too large for the remaining buffer, etc.) and the string contains an error message describing the
 * problem encountered.
 */
inline ParseResult parse(const uint8_t* begin, size_t size) {
    return parse(begin, begin + size);
}

/**
 * Parse the first CBOR data item (possibly compound) from the range [begin, begin + size).
 *
 * Returns a tuple of Item pointer, buffer pointer and error message.  If parsing is successful, the
 * Item pointer is non-null, the buffer pointer points to the first byte after the
 * successfully-parsed item and the error message string is empty.  If parsing fails, the Item
 * pointer is null, the buffer pointer points to the first byte that was unparseable (the first byte
 * of a data item header that is malformed in some way, e.g. an invalid value, or a length that is
 * too large for the remaining buffer, etc.) and the string contains an error message describing the
 * problem encountered.
 *
 * The returned CBOR data item will contain View* items backed by
 * std::string_view types over the input range.
 * WARNING! If the input range changes underneath, the corresponding views will
 * carry the same change.
 */
inline ParseResult parseWithViews(const uint8_t* begin, size_t size) {
    return parseWithViews(begin, begin + size);
}

/**
 * Parse the first CBOR data item (possibly compound) from the value contained in a Bstr.
 *
 * Returns a tuple of Item pointer, buffer pointer and error message.  If parsing is successful, the
 * Item pointer is non-null, the buffer pointer points to the first byte after the
 * successfully-parsed item and the error message string is empty.  If parsing fails, the Item
 * pointer is null, the buffer pointer points to the first byte that was unparseable (the first byte
 * of a data item header that is malformed in some way, e.g. an invalid value, or a length that is
 * too large for the remaining buffer, etc.) and the string contains an error message describing the
 * problem encountered.
 */
inline ParseResult parse(const Bstr* bstr) {
    if (!bstr)
        return ParseResult(nullptr, nullptr, "Null Bstr pointer");
    return parse(bstr->value());
}

class ParseClient;

/**
 * Parse the CBOR data in the range [begin, end) in streaming fashion, calling methods on the
 * provided ParseClient when elements are found.
 */
void parse(const uint8_t* begin, const uint8_t* end, ParseClient* parseClient);

/**
 * Parse the CBOR data in the range [begin, end) in streaming fashion, calling methods on the
 * provided ParseClient when elements are found. Uses the View* item types
 * instead of the copying ones.
 */
void parseWithViews(const uint8_t* begin, const uint8_t* end, ParseClient* parseClient);

/**
 * Parse the CBOR data in the vector in streaming fashion, calling methods on the
 * provided ParseClient when elements are found.
 */
inline void parse(const std::vector<uint8_t>& encoding, ParseClient* parseClient) {
    return parse(encoding.data(), encoding.data() + encoding.size(), parseClient);
}

/**
 * A pure interface that callers of the streaming parse functions must implement.
 */
class ParseClient {
  public:
    virtual ~ParseClient() {}

    /**
     * Called when an item is found.  The Item pointer points to the found item; use type() and
     * the appropriate as*() method to examine the value.  hdrBegin points to the first byte of the
     * header, valueBegin points to the first byte of the value and end points one past the end of
     * the item.  In the case of header-only items, such as integers, and compound items (ARRAY,
     * MAP or SEMANTIC) whose end has not yet been found, valueBegin and end are equal and point to
     * the byte past the header.
     *
     * Note that for compound types (ARRAY, MAP, and SEMANTIC), the Item will have no content.  For
     * Map and Array items, the size() method will return a correct value, but the index operators
     * are unsafe, and the object cannot be safely compared with another Array/Map.
     *
     * The method returns a ParseClient*.  In most cases "return this;" will be the right answer,
     * but a different ParseClient may be returned, which the parser will begin using. If the method
     * returns nullptr, parsing will be aborted immediately.
     */
    virtual ParseClient* item(std::unique_ptr<Item>& item, const uint8_t* hdrBegin,
                              const uint8_t* valueBegin, const uint8_t* end) = 0;

    /**
     * Called when the end of a compound item (MAP or ARRAY) is found.  The item argument will be
     * the same one passed to the item() call -- and may be empty if item() moved its value out.
     * hdrBegin, valueBegin and end point to the beginning of the item header, the beginning of the
     * first contained value, and one past the end of the last contained value, respectively.
     *
     * Note that the Item will have no content.
     *
     * As with item(), itemEnd() can change the ParseClient by returning a different one, or end the
     * parsing by returning nullptr;
     */
    virtual ParseClient* itemEnd(std::unique_ptr<Item>& item, const uint8_t* hdrBegin,
                                 const uint8_t* valueBegin, const uint8_t* end) = 0;

    /**
     * Called when parsing encounters an error.  position is set to the first unparsed byte (one
     * past the last successfully-parsed byte) and errorMessage contains an message explaining what
     * sort of error occurred.
     */
    virtual void error(const uint8_t* position, const std::string& errorMessage) = 0;
};

}  // namespace cppbor
