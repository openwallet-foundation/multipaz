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

#include "cppbor_parse.h"

#include <memory>
#include <sstream>
#include <stack>
#include <type_traits>
#include "cppbor.h"

#define CHECK(x) (void)(x)

namespace cppbor {

namespace {

const unsigned kMaxParseDepth = 1000;

std::string insufficientLengthString(size_t bytesNeeded, size_t bytesAvail,
                                     const std::string& type) {
    char buf[1024];
    snprintf(buf, sizeof(buf), "Need %zu byte(s) for %s, have %zu.", bytesNeeded, type.c_str(),
             bytesAvail);
    return std::string(buf);
}

template <typename T, typename = std::enable_if_t<std::is_unsigned_v<T>>>
std::tuple<bool, uint64_t, const uint8_t*> parseLength(const uint8_t* pos, const uint8_t* end,
                                                       ParseClient* parseClient) {
    if (pos + sizeof(T) > end) {
        parseClient->error(pos - 1, insufficientLengthString(sizeof(T), end - pos, "length field"));
        return {false, 0, pos};
    }

    const uint8_t* intEnd = pos + sizeof(T);
    T result = 0;
    do {
        result = static_cast<T>((result << 8) | *pos++);
    } while (pos < intEnd);
    return {true, result, pos};
}

std::tuple<const uint8_t*, ParseClient*> parseRecursively(const uint8_t* begin, const uint8_t* end,
                                                          bool emitViews, ParseClient* parseClient,
                                                          unsigned depth);

std::tuple<const uint8_t*, ParseClient*> handleUint(uint64_t value, const uint8_t* hdrBegin,
                                                    const uint8_t* hdrEnd,
                                                    ParseClient* parseClient) {
    std::unique_ptr<Item> item = std::make_unique<Uint>(value);
    return {hdrEnd,
            parseClient->item(item, hdrBegin, hdrEnd /* valueBegin */, hdrEnd /* itemEnd */)};
}

std::tuple<const uint8_t*, ParseClient*> handleNint(uint64_t value, const uint8_t* hdrBegin,
                                                    const uint8_t* hdrEnd,
                                                    ParseClient* parseClient) {
    if (value > std::numeric_limits<int64_t>::max()) {
        parseClient->error(hdrBegin, "NINT values that don't fit in int64_t are not supported.");
        return {hdrBegin, nullptr /* end parsing */};
    }
    std::unique_ptr<Item> item = std::make_unique<Nint>(-1 - static_cast<int64_t>(value));
    return {hdrEnd,
            parseClient->item(item, hdrBegin, hdrEnd /* valueBegin */, hdrEnd /* itemEnd */)};
}

std::tuple<const uint8_t*, ParseClient*> handleBool(uint64_t value, const uint8_t* hdrBegin,
                                                    const uint8_t* hdrEnd,
                                                    ParseClient* parseClient) {
    std::unique_ptr<Item> item = std::make_unique<Bool>(value == TRUE);
    return {hdrEnd,
            parseClient->item(item, hdrBegin, hdrEnd /* valueBegin */, hdrEnd /* itemEnd */)};
}

std::tuple<const uint8_t*, ParseClient*> handleNull(const uint8_t* hdrBegin, const uint8_t* hdrEnd,
                                                    ParseClient* parseClient) {
    std::unique_ptr<Item> item = std::make_unique<Null>();
    return {hdrEnd,
            parseClient->item(item, hdrBegin, hdrEnd /* valueBegin */, hdrEnd /* itemEnd */)};
}

template <typename T>
std::tuple<const uint8_t*, ParseClient*> handleString(uint64_t length, const uint8_t* hdrBegin,
                                                      const uint8_t* valueBegin, const uint8_t* end,
                                                      const std::string& errLabel,
                                                      ParseClient* parseClient) {
    ssize_t signed_length = static_cast<ssize_t>(length);
    if (end - valueBegin < signed_length || signed_length < 0) {
        parseClient->error(hdrBegin, insufficientLengthString(length, end - valueBegin, errLabel));
        return {hdrBegin, nullptr /* end parsing */};
    }

    std::unique_ptr<Item> item = std::make_unique<T>(valueBegin, valueBegin + length);
    return {valueBegin + length,
            parseClient->item(item, hdrBegin, valueBegin, valueBegin + length)};
}

class IncompleteItem {
  public:
    static IncompleteItem* cast(Item* item);

    virtual ~IncompleteItem() {}
    virtual void add(std::unique_ptr<Item> item) = 0;
    virtual std::unique_ptr<Item> finalize() && = 0;
};

class IncompleteArray : public Array, public IncompleteItem {
  public:
    explicit IncompleteArray(size_t size) : mSize(size) {}

    // If the "complete" size is known, return it, otherwise return the current size.
    size_t size() const override {
        return mSize == SIZE_MAX ? Array::size() : mSize;
    }

    void add(std::unique_ptr<Item> item) override {
        mEntries.push_back(std::move(item));
    }

    virtual std::unique_ptr<Item> finalize() && override {
        // Use Array explicitly so the compiler picks the correct ctor overload
        Array* thisArray = this;
        return std::make_unique<Array>(std::move(*thisArray));
    }

  private:
    size_t mSize;
};

class IncompleteMap : public Map, public IncompleteItem {
  public:
    explicit IncompleteMap(size_t size) : mSize(size) {}

    // If the "complete" size is known, return it, otherwise return the current size.
    size_t size() const override {
        return mSize == SIZE_MAX ? Map::size() : mSize;
    }

    void add(std::unique_ptr<Item> item) override {
        if (mKeyHeldForAdding) {
            mEntries.push_back({std::move(mKeyHeldForAdding), std::move(item)});
        } else {
            mKeyHeldForAdding = std::move(item);
        }
    }

    virtual std::unique_ptr<Item> finalize() && override {
        return std::make_unique<Map>(std::move(*this));
    }

  private:
    std::unique_ptr<Item> mKeyHeldForAdding;
    size_t mSize;
};

class IncompleteSemanticTag : public SemanticTag, public IncompleteItem {
  public:
    explicit IncompleteSemanticTag(uint64_t value) : SemanticTag(value) {}

    // We return the "complete" size, rather than the actual size.
    size_t size() const override { return 1; }

    void add(std::unique_ptr<Item> item) override { mTaggedItem = std::move(item); }

    virtual std::unique_ptr<Item> finalize() && override {
        return std::make_unique<SemanticTag>(std::move(*this));
    }
};

IncompleteItem* IncompleteItem::cast(Item* item) {
    CHECK(item->isCompound());
    // Semantic tag must be check first, because SemanticTag::type returns the wrapped item's type.
    if (item->asSemanticTag()) {
#if __has_feature(cxx_rtti)
        CHECK(dynamic_cast<IncompleteSemanticTag*>(item));
#endif
        return static_cast<IncompleteSemanticTag*>(item);
    } else if (item->type() == ARRAY) {
#if __has_feature(cxx_rtti)
        CHECK(dynamic_cast<IncompleteArray*>(item));
#endif
        return static_cast<IncompleteArray*>(item);
    } else if (item->type() == MAP) {
#if __has_feature(cxx_rtti)
        CHECK(dynamic_cast<IncompleteMap*>(item));
#endif
        return static_cast<IncompleteMap*>(item);
    } else {
        CHECK(false);  // Impossible to get here.
    }
    return nullptr;
}

std::tuple<const uint8_t*, ParseClient*> handleEntries(size_t entryCount, const uint8_t* hdrBegin,
                                                       const uint8_t* pos, const uint8_t* end,
                                                       const std::string& typeName, bool emitViews,
                                                       ParseClient* parseClient, unsigned depth) {
    while (entryCount > 0) {
        --entryCount;
        if (pos == end) {
            parseClient->error(hdrBegin, "Not enough entries for " + typeName + ".");
            return {hdrBegin, nullptr /* end parsing */};
        }
        if (*pos == 0xFF) {
            // Next character is the "break" Stop Code
            ++pos;
            break;
        }
        std::tie(pos, parseClient) = parseRecursively(pos, end, emitViews, parseClient, depth + 1);
        if (!parseClient) return {hdrBegin, nullptr};
    }
    return {pos, parseClient};
}

std::tuple<const uint8_t*, ParseClient*> handleCompound(
        std::unique_ptr<Item> item, uint64_t entryCount, const uint8_t* hdrBegin,
        const uint8_t* valueBegin, const uint8_t* end, const std::string& typeName, bool emitViews,
        ParseClient* parseClient, unsigned depth) {
    parseClient =
            parseClient->item(item, hdrBegin, valueBegin, valueBegin /* don't know the end yet */);
    if (!parseClient) return {hdrBegin, nullptr};

    const uint8_t* pos;
    std::tie(pos, parseClient) = handleEntries(entryCount, hdrBegin, valueBegin, end, typeName,
                                               emitViews, parseClient, depth);
    if (!parseClient) return {hdrBegin, nullptr};

    return {pos, parseClient->itemEnd(item, hdrBegin, valueBegin, pos)};
}

std::tuple<const uint8_t*, ParseClient*> parseRecursively(const uint8_t* begin, const uint8_t* end,
                                                          bool emitViews, ParseClient* parseClient,
                                                          unsigned depth) {
    if (begin == end) {
        parseClient->error(
                begin,
                "Input buffer is empty. Begin and end cannot point to the same location.");
        return {begin, nullptr};
    }

    // Limit recursion depth to avoid overflowing the stack.
    if (depth > kMaxParseDepth) {
        parseClient->error(begin,
                           "Max depth reached.  Cannot parse CBOR structures with more "
                           "than " +
                                   std::to_string(kMaxParseDepth) + " levels.");
        return {begin, nullptr};
    }

    const uint8_t* pos = begin;

    MajorType type = static_cast<MajorType>(*pos & 0xE0);
    uint8_t tagInt = *pos & 0x1F;
    ++pos;

    bool success = true;
    uint64_t addlData;
    if ((type == ARRAY || type == MAP) && tagInt == INDEFINITE_LENGTH) {
        addlData = SIZE_MAX;
    } else if (tagInt < ONE_BYTE_LENGTH) {
        addlData = tagInt;
    } else if (tagInt > EIGHT_BYTE_LENGTH) {
        parseClient->error(
                begin,
                "Reserved additional information value or unsupported indefinite length item.");
        return {begin, nullptr};
    } else {
        switch (tagInt) {
            case ONE_BYTE_LENGTH:
                std::tie(success, addlData, pos) = parseLength<uint8_t>(pos, end, parseClient);
                break;

            case TWO_BYTE_LENGTH:
                std::tie(success, addlData, pos) = parseLength<uint16_t>(pos, end, parseClient);
                break;

            case FOUR_BYTE_LENGTH:
                std::tie(success, addlData, pos) = parseLength<uint32_t>(pos, end, parseClient);
                break;

            case EIGHT_BYTE_LENGTH:
                std::tie(success, addlData, pos) = parseLength<uint64_t>(pos, end, parseClient);
                break;

            default:
                CHECK(false);  //  It's impossible to get here
                break;
        }
    }

    if (!success) return {begin, nullptr};

    switch (type) {
        case UINT:
            return handleUint(addlData, begin, pos, parseClient);

        case NINT:
            return handleNint(addlData, begin, pos, parseClient);

        case BSTR:
            if (emitViews) {
                return handleString<ViewBstr>(addlData, begin, pos, end, "byte string", parseClient);
            } else {
                return handleString<Bstr>(addlData, begin, pos, end, "byte string", parseClient);
            }

        case TSTR:
            if (emitViews) {
                return handleString<ViewTstr>(addlData, begin, pos, end, "text string", parseClient);
            } else {
                return handleString<Tstr>(addlData, begin, pos, end, "text string", parseClient);
            }

        case ARRAY:
            return handleCompound(std::make_unique<IncompleteArray>(addlData), addlData, begin, pos,
                                  end, "array", emitViews, parseClient, depth);

        case MAP:
            return handleCompound(std::make_unique<IncompleteMap>(addlData), addlData * 2, begin,
                                  pos, end, "map", emitViews, parseClient, depth);

        case SEMANTIC:
            return handleCompound(std::make_unique<IncompleteSemanticTag>(addlData), 1, begin, pos,
                                  end, "semantic", emitViews, parseClient, depth);

        case SIMPLE:
            switch (addlData) {
                case TRUE:
                case FALSE:
                    return handleBool(addlData, begin, pos, parseClient);
                case NULL_V:
                    return handleNull(begin, pos, parseClient);
                default:
                    parseClient->error(begin, "Unsupported floating-point or simple value.");
                    return {begin, nullptr};
            }
    }
    CHECK(false);  // Impossible to get here.
    return {};
}

class FullParseClient : public ParseClient {
  public:
    virtual ParseClient* item(std::unique_ptr<Item>& item, const uint8_t*, const uint8_t*,
                              const uint8_t* end) override {
        if (mParentStack.empty() && !item->isCompound()) {
            // This is the first and only item.
            mTheItem = std::move(item);
            mPosition = end;
            return nullptr;  //  We're done.
        }

        if (item->isCompound()) {
            // Starting a new compound data item, i.e. a new parent.  Save it on the parent stack.
            // It's safe to save a raw pointer because the unique_ptr is guaranteed to stay in
            // existence until the corresponding itemEnd() call.
            mParentStack.push(item.get());
            return this;
        } else {
            appendToLastParent(std::move(item));
            return this;
        }
    }

    virtual ParseClient* itemEnd(std::unique_ptr<Item>& item, const uint8_t*, const uint8_t*,
                                 const uint8_t* end) override {
        CHECK(item->isCompound() && item.get() == mParentStack.top());
        mParentStack.pop();
        IncompleteItem* incompleteItem = IncompleteItem::cast(item.get());
        std::unique_ptr<Item> finalizedItem = std::move(*incompleteItem).finalize();

        if (mParentStack.empty()) {
            mTheItem = std::move(finalizedItem);
            mPosition = end;
            return nullptr;  // We're done
        } else {
            appendToLastParent(std::move(finalizedItem));
            return this;
        }
    }

    virtual void error(const uint8_t* position, const std::string& errorMessage) override {
        mPosition = position;
        mErrorMessage = errorMessage;
    }

    std::tuple<std::unique_ptr<Item> /* result */, const uint8_t* /* newPos */,
               std::string /* errMsg */>
    parseResult() {
        std::unique_ptr<Item> p = std::move(mTheItem);
        return {std::move(p), mPosition, std::move(mErrorMessage)};
    }

  private:
    void appendToLastParent(std::unique_ptr<Item> item) {
        auto parent = mParentStack.top();
        IncompleteItem::cast(parent)->add(std::move(item));
    }

    std::unique_ptr<Item> mTheItem;
    std::stack<Item*> mParentStack;
    const uint8_t* mPosition = nullptr;
    std::string mErrorMessage;
};

}  // anonymous namespace

void parse(const uint8_t* begin, const uint8_t* end, ParseClient* parseClient) {
    parseRecursively(begin, end, false, parseClient, 0);
}

std::tuple<std::unique_ptr<Item> /* result */, const uint8_t* /* newPos */,
           std::string /* errMsg */>
parse(const uint8_t* begin, const uint8_t* end) {
    FullParseClient parseClient;
    parse(begin, end, &parseClient);
    return parseClient.parseResult();
}

void parseWithViews(const uint8_t* begin, const uint8_t* end, ParseClient* parseClient) {
    parseRecursively(begin, end, true, parseClient, 0);
}

std::tuple<std::unique_ptr<Item> /* result */, const uint8_t* /* newPos */,
           std::string /* errMsg */>
parseWithViews(const uint8_t* begin, const uint8_t* end) {
    FullParseClient parseClient;
    parseWithViews(begin, end, &parseClient);
    return parseClient.parseResult();
}

}  // namespace cppbor
