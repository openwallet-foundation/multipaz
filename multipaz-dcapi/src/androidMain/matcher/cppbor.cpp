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

#include "cppbor.h"

#include <inttypes.h>
#include <cstdint>

#include "cppbor_parse.h"

using std::string;
using std::vector;

#define CHECK(x) (void)(x)

namespace cppbor {

namespace {

template <typename T, typename Iterator, typename = std::enable_if<std::is_unsigned<T>::value>>
Iterator writeBigEndian(T value, Iterator pos) {
    for (unsigned i = 0; i < sizeof(value); ++i) {
        *pos++ = static_cast<uint8_t>(value >> (8 * (sizeof(value) - 1)));
        value = static_cast<T>(value << 8);
    }
    return pos;
}

template <typename T, typename = std::enable_if<std::is_unsigned<T>::value>>
void writeBigEndian(T value, std::function<void(uint8_t)>& cb) {
    for (unsigned i = 0; i < sizeof(value); ++i) {
        cb(static_cast<uint8_t>(value >> (8 * (sizeof(value) - 1))));
        value = static_cast<T>(value << 8);
    }
}

bool cborAreAllElementsNonCompound(const Item* compoundItem) {
    if (compoundItem->type() == ARRAY) {
        const Array* array = compoundItem->asArray();
        for (size_t n = 0; n < array->size(); n++) {
            const Item* entry = (*array)[n].get();
            switch (entry->type()) {
                case ARRAY:
                case MAP:
                    return false;
                default:
                    break;
            }
        }
    } else {
        const Map* map = compoundItem->asMap();
        for (auto& [keyEntry, valueEntry] : *map) {
            switch (keyEntry->type()) {
                case ARRAY:
                case MAP:
                    return false;
                default:
                    break;
            }
            switch (valueEntry->type()) {
                case ARRAY:
                case MAP:
                    return false;
                default:
                    break;
            }
        }
    }
    return true;
}

bool prettyPrintInternal(const Item* item, string& out, size_t indent, size_t maxBStrSize,
                         const vector<string>& mapKeysToNotPrint) {
    if (!item) {
        out.append("<NULL>");
        return false;
    }

    char buf[80];

    string indentString(indent, ' ');

    size_t tagCount = item->semanticTagCount();
    while (tagCount > 0) {
        --tagCount;
        snprintf(buf, sizeof(buf), "tag %" PRIu64 " ", item->semanticTag(tagCount));
        out.append(buf);
    }

    switch (item->type()) {
        case SEMANTIC:
            // Handled above.
            break;

        case UINT:
            snprintf(buf, sizeof(buf), "%" PRIu64, item->asUint()->unsignedValue());
            out.append(buf);
            break;

        case NINT:
            snprintf(buf, sizeof(buf), "%" PRId64, item->asNint()->value());
            out.append(buf);
            break;

        case BSTR: {
            const uint8_t* valueData;
            size_t valueSize;
            const Bstr* bstr = item->asBstr();
            if (bstr != nullptr) {
                const vector<uint8_t>& value = bstr->value();
                valueData = value.data();
                valueSize = value.size();
            } else {
                const ViewBstr* viewBstr = item->asViewBstr();
                assert(viewBstr != nullptr);

                valueData = viewBstr->view().data();
                valueSize = viewBstr->view().size();
            }

            if (valueSize > maxBStrSize) {
	      /*
                unsigned char digest[SHA_DIGEST_LENGTH];
                SHA_CTX ctx;
                SHA1_Init(&ctx);
                SHA1_Update(&ctx, valueData, valueSize);
                SHA1_Final(digest, &ctx);
                char buf2[SHA_DIGEST_LENGTH * 2 + 1];
                for (size_t n = 0; n < SHA_DIGEST_LENGTH; n++) {
                    snprintf(buf2 + n * 2, 3, "%02x", digest[n]);
                }
                snprintf(buf, sizeof(buf), "<bstr size=%zd sha1=%s>", valueSize, buf2);
                out.append(buf);
	      */
            } else {
                out.append("{");
                for (size_t n = 0; n < valueSize; n++) {
                    if (n > 0) {
                        out.append(", ");
                    }
                    snprintf(buf, sizeof(buf), "0x%02x", valueData[n]);
                    out.append(buf);
                }
                out.append("}");
            }
        } break;

        case TSTR:
            out.append("'");
            {
                // TODO: escape "'" characters
                if (item->asTstr() != nullptr) {
                    out.append(item->asTstr()->value().c_str());
                } else {
                    const ViewTstr* viewTstr = item->asViewTstr();
                    assert(viewTstr != nullptr);
                    out.append(viewTstr->view());
                }
            }
            out.append("'");
            break;

        case ARRAY: {
            const Array* array = item->asArray();
            if (array->size() == 0) {
                out.append("[]");
            } else if (cborAreAllElementsNonCompound(array)) {
                out.append("[");
                for (size_t n = 0; n < array->size(); n++) {
                    if (!prettyPrintInternal((*array)[n].get(), out, indent + 2, maxBStrSize,
                                             mapKeysToNotPrint)) {
                        return false;
                    }
                    out.append(", ");
                }
                out.append("]");
            } else {
                out.append("[\n" + indentString);
                for (size_t n = 0; n < array->size(); n++) {
                    out.append("  ");
                    if (!prettyPrintInternal((*array)[n].get(), out, indent + 2, maxBStrSize,
                                             mapKeysToNotPrint)) {
                        return false;
                    }
                    out.append(",\n" + indentString);
                }
                out.append("]");
            }
        } break;

        case MAP: {
            const Map* map = item->asMap();

            if (map->size() == 0) {
                out.append("{}");
            } else {
                out.append("{\n" + indentString);
                for (auto& [map_key, map_value] : *map) {
                    out.append("  ");

                    if (!prettyPrintInternal(map_key.get(), out, indent + 2, maxBStrSize,
                                             mapKeysToNotPrint)) {
                        return false;
                    }
                    out.append(" : ");
                    if (map_key->type() == TSTR &&
                        std::find(mapKeysToNotPrint.begin(), mapKeysToNotPrint.end(),
                                  map_key->asTstr()->value()) != mapKeysToNotPrint.end()) {
                        out.append("<not printed>");
                    } else {
                        if (!prettyPrintInternal(map_value.get(), out, indent + 2, maxBStrSize,
                                                 mapKeysToNotPrint)) {
                            return false;
                        }
                    }
                    out.append(",\n" + indentString);
                }
                out.append("}");
            }
        } break;

        case SIMPLE:
            const Bool* asBool = item->asSimple()->asBool();
            const Null* asNull = item->asSimple()->asNull();
            if (asBool != nullptr) {
                out.append(asBool->value() ? "true" : "false");
            } else if (asNull != nullptr) {
                out.append("null");
            } else {
                return false;
            }
            break;
    }

    return true;
}

}  // namespace

size_t headerSize(uint64_t addlInfo) {
    if (addlInfo < ONE_BYTE_LENGTH) return 1;
    if (addlInfo <= std::numeric_limits<uint8_t>::max()) return 2;
    if (addlInfo <= std::numeric_limits<uint16_t>::max()) return 3;
    if (addlInfo <= std::numeric_limits<uint32_t>::max()) return 5;
    return 9;
}

uint8_t* encodeHeader(MajorType type, uint64_t addlInfo, uint8_t* pos, const uint8_t* end) {
    size_t sz = headerSize(addlInfo);
    if (end - pos < static_cast<ssize_t>(sz)) return nullptr;
    switch (sz) {
        case 1:
            *pos++ = type | static_cast<uint8_t>(addlInfo);
            return pos;
        case 2:
            *pos++ = type | static_cast<MajorType>(ONE_BYTE_LENGTH);
            *pos++ = static_cast<uint8_t>(addlInfo);
            return pos;
        case 3:
            *pos++ = type | static_cast<MajorType>(TWO_BYTE_LENGTH);
            return writeBigEndian(static_cast<uint16_t>(addlInfo), pos);
        case 5:
            *pos++ = type | static_cast<MajorType>(FOUR_BYTE_LENGTH);
            return writeBigEndian(static_cast<uint32_t>(addlInfo), pos);
        case 9:
            *pos++ = type | static_cast<MajorType>(EIGHT_BYTE_LENGTH);
            return writeBigEndian(addlInfo, pos);
        default:
            CHECK(false);  // Impossible to get here.
            return nullptr;
    }
}

void encodeHeader(MajorType type, uint64_t addlInfo, EncodeCallback encodeCallback) {
    size_t sz = headerSize(addlInfo);
    switch (sz) {
        case 1:
            encodeCallback(type | static_cast<uint8_t>(addlInfo));
            break;
        case 2:
            encodeCallback(type | static_cast<MajorType>(ONE_BYTE_LENGTH));
            encodeCallback(static_cast<uint8_t>(addlInfo));
            break;
        case 3:
            encodeCallback(type | static_cast<MajorType>(TWO_BYTE_LENGTH));
            writeBigEndian(static_cast<uint16_t>(addlInfo), encodeCallback);
            break;
        case 5:
            encodeCallback(type | static_cast<MajorType>(FOUR_BYTE_LENGTH));
            writeBigEndian(static_cast<uint32_t>(addlInfo), encodeCallback);
            break;
        case 9:
            encodeCallback(type | static_cast<MajorType>(EIGHT_BYTE_LENGTH));
            writeBigEndian(addlInfo, encodeCallback);
            break;
        default:
            CHECK(false);  // Impossible to get here.
    }
}

bool Item::operator==(const Item& other) const& {
    if (type() != other.type()) return false;
    switch (type()) {
        case UINT:
            return *asUint() == *(other.asUint());
        case NINT:
            return *asNint() == *(other.asNint());
        case BSTR:
            if (asBstr() != nullptr && other.asBstr() != nullptr) {
                return *asBstr() == *(other.asBstr());
            }
            if (asViewBstr() != nullptr && other.asViewBstr() != nullptr) {
                return *asViewBstr() == *(other.asViewBstr());
            }
            // Interesting corner case: comparing a Bstr and ViewBstr with
            // identical contents. The function currently returns false for
            // this case.
            // TODO: if it should return true, this needs a deep comparison
            return false;
        case TSTR:
            if (asTstr() != nullptr && other.asTstr() != nullptr) {
                return *asTstr() == *(other.asTstr());
            }
            if (asViewTstr() != nullptr && other.asViewTstr() != nullptr) {
                return *asViewTstr() == *(other.asViewTstr());
            }
            // Same corner case as Bstr
            return false;
        case ARRAY:
            return *asArray() == *(other.asArray());
        case MAP:
            return *asMap() == *(other.asMap());
        case SIMPLE:
            return *asSimple() == *(other.asSimple());
        case SEMANTIC:
            return *asSemanticTag() == *(other.asSemanticTag());
        default:
            CHECK(false);  // Impossible to get here.
            return false;
    }
}

Nint::Nint(int64_t v) : mValue(v) {
    CHECK(v < 0);
}

bool Simple::operator==(const Simple& other) const& {
    if (simpleType() != other.simpleType()) return false;

    switch (simpleType()) {
        case BOOLEAN:
            return *asBool() == *(other.asBool());
        case NULL_T:
            return true;
        default:
            CHECK(false);  // Impossible to get here.
            return false;
    }
}

uint8_t* Bstr::encode(uint8_t* pos, const uint8_t* end) const {
    pos = encodeHeader(mValue.size(), pos, end);
    if (!pos || end - pos < static_cast<ptrdiff_t>(mValue.size())) return nullptr;
    return std::copy(mValue.begin(), mValue.end(), pos);
}

void Bstr::encodeValue(EncodeCallback encodeCallback) const {
    for (auto c : mValue) {
        encodeCallback(c);
    }
}

uint8_t* ViewBstr::encode(uint8_t* pos, const uint8_t* end) const {
    pos = encodeHeader(mView.size(), pos, end);
    if (!pos || end - pos < static_cast<ptrdiff_t>(mView.size())) return nullptr;
    return std::copy(mView.begin(), mView.end(), pos);
}

void ViewBstr::encodeValue(EncodeCallback encodeCallback) const {
    for (auto c : mView) {
        encodeCallback(static_cast<uint8_t>(c));
    }
}

uint8_t* Tstr::encode(uint8_t* pos, const uint8_t* end) const {
    pos = encodeHeader(mValue.size(), pos, end);
    if (!pos || end - pos < static_cast<ptrdiff_t>(mValue.size())) return nullptr;
    return std::copy(mValue.begin(), mValue.end(), pos);
}

void Tstr::encodeValue(EncodeCallback encodeCallback) const {
    for (auto c : mValue) {
        encodeCallback(static_cast<uint8_t>(c));
    }
}

uint8_t* ViewTstr::encode(uint8_t* pos, const uint8_t* end) const {
    pos = encodeHeader(mView.size(), pos, end);
    if (!pos || end - pos < static_cast<ptrdiff_t>(mView.size())) return nullptr;
    return std::copy(mView.begin(), mView.end(), pos);
}

void ViewTstr::encodeValue(EncodeCallback encodeCallback) const {
    for (auto c : mView) {
        encodeCallback(static_cast<uint8_t>(c));
    }
}

bool Array::operator==(const Array& other) const& {
    return size() == other.size()
           // Can't use vector::operator== because the contents are pointers.  std::equal lets us
           // provide a predicate that does the dereferencing.
           && std::equal(mEntries.begin(), mEntries.end(), other.mEntries.begin(),
                         [](auto& a, auto& b) -> bool { return *a == *b; });
}

uint8_t* Array::encode(uint8_t* pos, const uint8_t* end) const {
    pos = encodeHeader(size(), pos, end);
    if (!pos) return nullptr;
    for (auto& entry : mEntries) {
        pos = entry->encode(pos, end);
        if (!pos) return nullptr;
    }
    return pos;
}

void Array::encode(EncodeCallback encodeCallback) const {
    encodeHeader(size(), encodeCallback);
    for (auto& entry : mEntries) {
        entry->encode(encodeCallback);
    }
}

std::unique_ptr<Item> Array::clone() const {
    auto res = std::make_unique<Array>();
    for (size_t i = 0; i < mEntries.size(); i++) {
        res->add(mEntries[i]->clone());
    }
    return res;
}

bool Map::operator==(const Map& other) const& {
    return size() == other.size()
           // Can't use vector::operator== because the contents are pairs of pointers.  std::equal
           // lets us provide a predicate that does the dereferencing.
           && std::equal(begin(), end(), other.begin(), [](auto& a, auto& b) {
                  return *a.first == *b.first && *a.second == *b.second;
              });
}

uint8_t* Map::encode(uint8_t* pos, const uint8_t* end) const {
    pos = encodeHeader(size(), pos, end);
    if (!pos) return nullptr;
    for (auto& entry : mEntries) {
        pos = entry.first->encode(pos, end);
        if (!pos) return nullptr;
        pos = entry.second->encode(pos, end);
        if (!pos) return nullptr;
    }
    return pos;
}

void Map::encode(EncodeCallback encodeCallback) const {
    encodeHeader(size(), encodeCallback);
    for (auto& entry : mEntries) {
        entry.first->encode(encodeCallback);
        entry.second->encode(encodeCallback);
    }
}

bool Map::keyLess(const Item* a, const Item* b) {
    // CBOR map canonicalization rules are:

    // 1. If two keys have different lengths, the shorter one sorts earlier.
    if (a->encodedSize() < b->encodedSize()) return true;
    if (a->encodedSize() > b->encodedSize()) return false;

    // 2. If two keys have the same length, the one with the lower value in (byte-wise) lexical
    // order sorts earlier.  This requires encoding both items.
    auto encodedA = a->encode();
    auto encodedB = b->encode();

    return std::lexicographical_compare(encodedA.begin(), encodedA.end(),  //
                                        encodedB.begin(), encodedB.end());
}

void recursivelyCanonicalize(std::unique_ptr<Item>& item) {
    switch (item->type()) {
        case UINT:
        case NINT:
        case BSTR:
        case TSTR:
        case SIMPLE:
            return;

        case ARRAY:
            std::for_each(item->asArray()->begin(), item->asArray()->end(),
                          recursivelyCanonicalize);
            return;

        case MAP:
            item->asMap()->canonicalize(true /* recurse */);
            return;

        case SEMANTIC:
            // This can't happen.  SemanticTags delegate their type() method to the contained Item's
            // type.
            assert(false);
            return;
    }
}

Map& Map::canonicalize(bool recurse) & {
    if (recurse) {
        for (auto& entry : mEntries) {
            recursivelyCanonicalize(entry.first);
            recursivelyCanonicalize(entry.second);
        }
    }

    if (size() < 2 || mCanonicalized) {
        // Trivially or already canonical; do nothing.
        return *this;
    }

    std::sort(begin(), end(),
              [](auto& a, auto& b) { return keyLess(a.first.get(), b.first.get()); });
    mCanonicalized = true;
    return *this;
}

std::unique_ptr<Item> Map::clone() const {
    auto res = std::make_unique<Map>();
    for (auto& [key, value] : *this) {
        res->add(key->clone(), value->clone());
    }
    res->mCanonicalized = mCanonicalized;
    return res;
}

std::unique_ptr<Item> SemanticTag::clone() const {
    return std::make_unique<SemanticTag>(mValue, mTaggedItem->clone());
}

uint8_t* SemanticTag::encode(uint8_t* pos, const uint8_t* end) const {
    // Can't use the encodeHeader() method that calls type() to get the major type, since that will
    // return the tagged Item's type.
    pos = ::cppbor::encodeHeader(kMajorType, mValue, pos, end);
    if (!pos) return nullptr;
    return mTaggedItem->encode(pos, end);
}

void SemanticTag::encode(EncodeCallback encodeCallback) const {
    // Can't use the encodeHeader() method that calls type() to get the major type, since that will
    // return the tagged Item's type.
    ::cppbor::encodeHeader(kMajorType, mValue, encodeCallback);
    mTaggedItem->encode(encodeCallback);
}

size_t SemanticTag::semanticTagCount() const {
    size_t levelCount = 1;  // Count this level.
    const SemanticTag* cur = this;
    while (cur->mTaggedItem && (cur = cur->mTaggedItem->asSemanticTag()) != nullptr) ++levelCount;
    return levelCount;
}

uint64_t SemanticTag::semanticTag(size_t nesting) const {
    // Getting the value of a specific nested tag is a bit tricky, because we start with the outer
    // tag and don't know how many are inside.  We count the number of nesting levels to find out
    // how many there are in total, then to get the one we want we have to walk down levelCount -
    // nesting steps.
    size_t levelCount = semanticTagCount();
    if (nesting >= levelCount) return 0;

    levelCount -= nesting;
    const SemanticTag* cur = this;
    while (--levelCount > 0) cur = cur->mTaggedItem->asSemanticTag();

    return cur->mValue;
}

string prettyPrint(const Item* item, size_t maxBStrSize, const vector<string>& mapKeysToNotPrint) {
    string out;
    prettyPrintInternal(item, out, 0, maxBStrSize, mapKeysToNotPrint);
    return out;
}
string prettyPrint(const vector<uint8_t>& encodedCbor, size_t maxBStrSize,
                   const vector<string>& mapKeysToNotPrint) {
    auto [item, _, message] = parse(encodedCbor);
    if (item == nullptr) {
        return "";
    }

    return prettyPrint(item.get(), maxBStrSize, mapKeysToNotPrint);
}

}  // namespace cppbor
