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

#include <algorithm>
#include <cassert>
#include <cstdint>
#include <functional>
#include <iterator>
#include <memory>
#include <numeric>
#include <span>
#include <string>
#include <string_view>
#include <vector>
#include <algorithm>

#ifdef OS_WINDOWS
#include <basetsd.h>

#define ssize_t SSIZE_T
#endif // OS_WINDOWS

#ifdef TRUE
#undef TRUE
#endif // TRUE
#ifdef FALSE
#undef FALSE
#endif // FALSE

namespace cppbor {

enum MajorType : uint8_t {
    UINT = 0 << 5,
    NINT = 1 << 5,
    BSTR = 2 << 5,
    TSTR = 3 << 5,
    ARRAY = 4 << 5,
    MAP = 5 << 5,
    SEMANTIC = 6 << 5,
    SIMPLE = 7 << 5,
};

enum SimpleType {
    BOOLEAN,
    NULL_T,  // Only two supported, as yet.
};

enum SpecialAddlInfoValues : uint8_t {
    FALSE = 20,
    TRUE = 21,
    NULL_V = 22,
    ONE_BYTE_LENGTH = 24,
    TWO_BYTE_LENGTH = 25,
    FOUR_BYTE_LENGTH = 26,
    EIGHT_BYTE_LENGTH = 27,
    INDEFINITE_LENGTH = 31,
};

class Item;
class Uint;
class Nint;
class Int;
class Tstr;
class Bstr;
class Simple;
class Bool;
class Array;
class Map;
class Null;
class SemanticTag;
class EncodedItem;
class ViewTstr;
class ViewBstr;

/**
 * Returns the size of a CBOR header that contains the additional info value addlInfo.
 */
size_t headerSize(uint64_t addlInfo);

/**
 * Encodes a CBOR header with the specified type and additional info into the range [pos, end).
 * Returns a pointer to one past the last byte written, or nullptr if there isn't sufficient space
 * to write the header.
 */
uint8_t* encodeHeader(MajorType type, uint64_t addlInfo, uint8_t* pos, const uint8_t* end);

using EncodeCallback = std::function<void(uint8_t)>;

/**
 * Encodes a CBOR header with the specified type and additional info, passing each byte in turn to
 * encodeCallback.
 */
void encodeHeader(MajorType type, uint64_t addlInfo, EncodeCallback encodeCallback);

/**
 * Encodes a CBOR header witht he specified type and additional info, writing each byte to the
 * provided OutputIterator.
 */
template <typename OutputIterator,
          typename = std::enable_if_t<std::is_base_of_v<
                  std::output_iterator_tag,
                  typename std::iterator_traits<OutputIterator>::iterator_category>>>
void encodeHeader(MajorType type, uint64_t addlInfo, OutputIterator iter) {
    return encodeHeader(type, addlInfo, [&](uint8_t v) { *iter++ = v; });
}

/**
 * Item represents a CBOR-encodeable data item.  Item is an abstract interface with a set of virtual
 * methods that allow encoding of the item or conversion to the appropriate derived type.
 */
class Item {
  public:
    virtual ~Item() {}

    /**
     * Returns the CBOR type of the item.
     */
    virtual MajorType type() const = 0;

    // These methods safely downcast an Item to the appropriate subclass.
    virtual Int* asInt() { return nullptr; }
    const Int* asInt() const { return const_cast<Item*>(this)->asInt(); }
    virtual Uint* asUint() { return nullptr; }
    const Uint* asUint() const { return const_cast<Item*>(this)->asUint(); }
    virtual Nint* asNint() { return nullptr; }
    const Nint* asNint() const { return const_cast<Item*>(this)->asNint(); }
    virtual Tstr* asTstr() { return nullptr; }
    const Tstr* asTstr() const { return const_cast<Item*>(this)->asTstr(); }
    virtual Bstr* asBstr() { return nullptr; }
    const Bstr* asBstr() const { return const_cast<Item*>(this)->asBstr(); }
    virtual Simple* asSimple() { return nullptr; }
    const Simple* asSimple() const { return const_cast<Item*>(this)->asSimple(); }
    virtual Bool* asBool() { return nullptr; }
    const Bool* asBool() const { return const_cast<Item*>(this)->asBool(); }
    virtual Null* asNull() { return nullptr; }
    const Null* asNull() const { return const_cast<Item*>(this)->asNull(); }

    virtual Map* asMap() { return nullptr; }
    const Map* asMap() const { return const_cast<Item*>(this)->asMap(); }
    virtual Array* asArray() { return nullptr; }
    const Array* asArray() const { return const_cast<Item*>(this)->asArray(); }

    virtual ViewTstr* asViewTstr() { return nullptr; }
    const ViewTstr* asViewTstr() const { return const_cast<Item*>(this)->asViewTstr(); }
    virtual ViewBstr* asViewBstr() { return nullptr; }
    const ViewBstr* asViewBstr() const { return const_cast<Item*>(this)->asViewBstr(); }

    // Like those above, these methods safely downcast an Item when it's actually a SemanticTag.
    // However, if you think you want to use these methods, you probably don't.  Typically, the way
    // you should handle tagged Items is by calling the appropriate method above (e.g. asInt())
    // which will return a pointer to the tagged Item, rather than the tag itself.  If you want to
    // find out if the Item* you're holding is to something with one or more tags applied, see
    // semanticTagCount() and semanticTag() below.
    virtual SemanticTag* asSemanticTag() { return nullptr; }
    const SemanticTag* asSemanticTag() const { return const_cast<Item*>(this)->asSemanticTag(); }

    /**
     * Returns the number of semantic tags prefixed to this Item.
     */
    virtual size_t semanticTagCount() const { return 0; }

    /**
     * Returns the semantic tag at the specified nesting level `nesting`, iff `nesting` is less than
     * the value returned by semanticTagCount().
     *
     * CBOR tags are "nested" by applying them in sequence.  The "rightmost" tag is the "inner" tag.
     * That is, given:
     *
     *     4(5(6("AES"))) which encodes as C1 C2 C3 63 414553
     *
     * The tstr "AES" is tagged with 6.  The combined entity ("AES" tagged with 6) is tagged with 5,
     * etc.  So in this example, semanticTagCount() would return 3, and semanticTag(0) would return
     * 5 semanticTag(1) would return 5 and semanticTag(2) would return 4.  For values of n > 2,
     * semanticTag(n) will return 0, but this is a meaningless value.
     *
     * If this layering is confusing, you probably don't have to worry about it. Nested tagging does
     * not appear to be common, so semanticTag(0) is the only one you'll use.
     */
    virtual uint64_t semanticTag(size_t /* nesting */ = 0) const { return 0; }

    /**
     * Returns true if this is a "compound" item, i.e. one that contains one or more other items.
     */
    virtual bool isCompound() const { return false; }

    bool operator==(const Item& other) const&;
    bool operator!=(const Item& other) const& { return !(*this == other); }

    /**
     * Returns the number of bytes required to encode this Item into CBOR.  Note that if this is a
     * complex Item, calling this method will require walking the whole tree.
     */
    virtual size_t encodedSize() const = 0;

    /**
     * Encodes the Item into buffer referenced by range [*pos, end).  Returns a pointer to one past
     * the last position written.  Returns nullptr if there isn't enough space to encode.
     */
    virtual uint8_t* encode(uint8_t* pos, const uint8_t* end) const = 0;

    /**
     * Encodes the Item by passing each encoded byte to encodeCallback.
     */
    virtual void encode(EncodeCallback encodeCallback) const = 0;

    /**
     * Clones the Item
     */
    virtual std::unique_ptr<Item> clone() const = 0;

    /**
     * Encodes the Item into the provided OutputIterator.
     */
    template <typename OutputIterator,
              typename = typename std::iterator_traits<OutputIterator>::iterator_category>
    void encode(OutputIterator i) const {
        return encode([&](uint8_t v) { *i++ = v; });
    }

    /**
     * Encodes the Item into a new std::vector<uint8_t>.
     */
    std::vector<uint8_t> encode() const {
        std::vector<uint8_t> retval;
        retval.reserve(encodedSize());
        encode(std::back_inserter(retval));
        return retval;
    }

    /**
     * Encodes the Item into a new std::string.
     */
    std::string toString() const {
        std::string retval;
        retval.reserve(encodedSize());
        encode([&](uint8_t v) { retval.push_back(v); });
        return retval;
    }

    /**
     * Encodes only the header of the Item.
     */
    inline uint8_t* encodeHeader(uint64_t addlInfo, uint8_t* pos, const uint8_t* end) const {
        return ::cppbor::encodeHeader(type(), addlInfo, pos, end);
    }

    /**
     * Encodes only the header of the Item.
     */
    inline void encodeHeader(uint64_t addlInfo, EncodeCallback encodeCallback) const {
        ::cppbor::encodeHeader(type(), addlInfo, encodeCallback);
    }
};

/**
 * EncodedItem represents a bit of already-encoded CBOR. Caveat emptor: It does no checking to
 * ensure that the provided data is a valid encoding, cannot be meaninfully-compared with other
 * kinds of items and you cannot use the as*() methods to find out what's inside it.
 */
class EncodedItem : public Item {
  public:
    explicit EncodedItem(std::vector<uint8_t> value) : mValue(std::move(value)) {}

    bool operator==(const EncodedItem& other) const& { return mValue == other.mValue; }

    // Type can't be meaningfully-obtained. We could extract the type from the first byte and return
    // it, but you can't do any of the normal things with an EncodedItem so there's no point.
    MajorType type() const override {
        assert(false);
        return static_cast<MajorType>(-1);
    }
    size_t encodedSize() const override { return mValue.size(); }
    uint8_t* encode(uint8_t* pos, const uint8_t* end) const override {
        if (end - pos < static_cast<ssize_t>(mValue.size())) return nullptr;
        return std::copy(mValue.begin(), mValue.end(), pos);
    }
    void encode(EncodeCallback encodeCallback) const override {
        std::for_each(mValue.begin(), mValue.end(), encodeCallback);
    }
    std::unique_ptr<Item> clone() const override { return std::make_unique<EncodedItem>(mValue); }

  private:
    std::vector<uint8_t> mValue;
};

/**
 * Int is an abstraction that allows Uint and Nint objects to be manipulated without caring about
 * the sign.
 */
class Int : public Item {
  public:
    bool operator==(const Int& other) const& { return value() == other.value(); }

    virtual int64_t value() const = 0;
    using Item::asInt;
    Int* asInt() override { return this; }
};

/**
 * Uint is a concrete Item that implements CBOR major type 0.
 */
class Uint : public Int {
  public:
    static constexpr MajorType kMajorType = UINT;

    explicit Uint(uint64_t v) : mValue(v) {}

    bool operator==(const Uint& other) const& { return mValue == other.mValue; }

    MajorType type() const override { return kMajorType; }
    using Item::asUint;
    Uint* asUint() override { return this; }

    size_t encodedSize() const override { return headerSize(mValue); }

    int64_t value() const override { return mValue; }
    uint64_t unsignedValue() const { return mValue; }

    using Item::encode;
    uint8_t* encode(uint8_t* pos, const uint8_t* end) const override {
        return encodeHeader(mValue, pos, end);
    }
    void encode(EncodeCallback encodeCallback) const override {
        encodeHeader(mValue, encodeCallback);
    }

    std::unique_ptr<Item> clone() const override { return std::make_unique<Uint>(mValue); }

  private:
    uint64_t mValue;
};

/**
 * Nint is a concrete Item that implements CBOR major type 1.

 * Note that it is incapable of expressing the full range of major type 1 values, becaue it can only
 * express values that fall into the range [std::numeric_limits<int64_t>::min(), -1].  It cannot
 * express values in the range [std::numeric_limits<int64_t>::min() - 1,
 * -std::numeric_limits<uint64_t>::max()].
 */
class Nint : public Int {
  public:
    static constexpr MajorType kMajorType = NINT;

    explicit Nint(int64_t v);

    bool operator==(const Nint& other) const& { return mValue == other.mValue; }

    MajorType type() const override { return kMajorType; }
    using Item::asNint;
    Nint* asNint() override { return this; }
    size_t encodedSize() const override { return headerSize(addlInfo()); }

    int64_t value() const override { return mValue; }

    using Item::encode;
    uint8_t* encode(uint8_t* pos, const uint8_t* end) const override {
        return encodeHeader(addlInfo(), pos, end);
    }
    void encode(EncodeCallback encodeCallback) const override {
        encodeHeader(addlInfo(), encodeCallback);
    }

    std::unique_ptr<Item> clone() const override { return std::make_unique<Nint>(mValue); }

  private:
    uint64_t addlInfo() const { return -1ll - mValue; }

    int64_t mValue;
};

/**
 * Bstr is a concrete Item that implements major type 2.
 */
class Bstr : public Item {
  public:
    static constexpr MajorType kMajorType = BSTR;

    // Construct an empty Bstr
    explicit Bstr() {}

    // Construct from a vector
    explicit Bstr(std::vector<uint8_t> v) : mValue(std::move(v)) {}

    // Construct from a string
    explicit Bstr(const std::string& v)
        : mValue(reinterpret_cast<const uint8_t*>(v.data()),
                 reinterpret_cast<const uint8_t*>(v.data()) + v.size()) {}

    // Construct from a pointer/size pair
    explicit Bstr(const std::pair<const uint8_t*, size_t>& buf)
        : mValue(buf.first, buf.first + buf.second) {}

    // Construct from a pair of iterators
    template <typename I1, typename I2,
              typename = typename std::iterator_traits<I1>::iterator_category,
              typename = typename std::iterator_traits<I2>::iterator_category>
    explicit Bstr(const std::pair<I1, I2>& pair) : mValue(pair.first, pair.second) {}

    // Construct from an iterator range.
    template <typename I1, typename I2,
              typename = typename std::iterator_traits<I1>::iterator_category,
              typename = typename std::iterator_traits<I2>::iterator_category>
    Bstr(I1 begin, I2 end) : mValue(begin, end) {}

    bool operator==(const Bstr& other) const& { return mValue == other.mValue; }

    MajorType type() const override { return kMajorType; }
    using Item::asBstr;
    Bstr* asBstr() override { return this; }
    size_t encodedSize() const override { return headerSize(mValue.size()) + mValue.size(); }
    using Item::encode;
    uint8_t* encode(uint8_t* pos, const uint8_t* end) const override;
    void encode(EncodeCallback encodeCallback) const override {
        encodeHeader(mValue.size(), encodeCallback);
        encodeValue(encodeCallback);
    }

    const std::vector<uint8_t>& value() const { return mValue; }
    std::vector<uint8_t>&& moveValue() { return std::move(mValue); }

    std::unique_ptr<Item> clone() const override { return std::make_unique<Bstr>(mValue); }

  private:
    void encodeValue(EncodeCallback encodeCallback) const;

    std::vector<uint8_t> mValue;
};

/**
 * ViewBstr is a read-only version of Bstr backed by std::span
 */
class ViewBstr : public Item {
  public:
    static constexpr MajorType kMajorType = BSTR;

    // Construct an empty ViewBstr
    explicit ViewBstr() {}

    // Construct from a span of uint8_t values
    explicit ViewBstr(std::span<const uint8_t> v) : mView(std::move(v)) {}

    // Construct from a string_view
    explicit ViewBstr(std::string_view v)
        : mView(reinterpret_cast<const uint8_t*>(v.data()), v.size()) {}

    // Construct from an iterator range
    template <typename I1, typename I2,
              typename = typename std::iterator_traits<I1>::iterator_category,
              typename = typename std::iterator_traits<I2>::iterator_category>
    ViewBstr(I1 begin, I2 end) : mView(begin, end) {}

    // Construct from a uint8_t pointer pair
    ViewBstr(const uint8_t* begin, const uint8_t* end)
        : mView(begin, std::distance(begin, end)) {}

    bool operator==(const ViewBstr& other) const& {
        return std::equal(mView.begin(), mView.end(), other.mView.begin(), other.mView.end());
    }

    MajorType type() const override { return kMajorType; }
    using Item::asViewBstr;
    ViewBstr* asViewBstr() override { return this; }
    size_t encodedSize() const override { return headerSize(mView.size()) + mView.size(); }
    using Item::encode;
    uint8_t* encode(uint8_t* pos, const uint8_t* end) const override;
    void encode(EncodeCallback encodeCallback) const override {
        encodeHeader(mView.size(), encodeCallback);
        encodeValue(encodeCallback);
    }

    const std::span<const uint8_t>& view() const { return mView; }

    std::unique_ptr<Item> clone() const override { return std::make_unique<ViewBstr>(mView); }

  private:
    void encodeValue(EncodeCallback encodeCallback) const;

    std::span<const uint8_t> mView;
};

/**
 * Tstr is a concrete Item that implements major type 3.
 */
class Tstr : public Item {
  public:
    static constexpr MajorType kMajorType = TSTR;

    // Construct from a string
    explicit Tstr(std::string v) : mValue(std::move(v)) {}

    // Construct from a string_view
    explicit Tstr(const std::string_view& v) : mValue(v) {}

    // Construct from a C string
    explicit Tstr(const char* v) : mValue(std::string(v)) {}

    // Construct from a pair of iterators
    template <typename I1, typename I2,
              typename = typename std::iterator_traits<I1>::iterator_category,
              typename = typename std::iterator_traits<I2>::iterator_category>
    explicit Tstr(const std::pair<I1, I2>& pair) : mValue(pair.first, pair.second) {}

    // Construct from an iterator range
    template <typename I1, typename I2,
              typename = typename std::iterator_traits<I1>::iterator_category,
              typename = typename std::iterator_traits<I2>::iterator_category>
    Tstr(I1 begin, I2 end) : mValue(begin, end) {}

    bool operator==(const Tstr& other) const& { return mValue == other.mValue; }

    MajorType type() const override { return kMajorType; }
    using Item::asTstr;
    Tstr* asTstr() override { return this; }
    size_t encodedSize() const override { return headerSize(mValue.size()) + mValue.size(); }
    using Item::encode;
    uint8_t* encode(uint8_t* pos, const uint8_t* end) const override;
    void encode(EncodeCallback encodeCallback) const override {
        encodeHeader(mValue.size(), encodeCallback);
        encodeValue(encodeCallback);
    }

    const std::string& value() const { return mValue; }
    std::string&& moveValue() { return std::move(mValue); }

    std::unique_ptr<Item> clone() const override { return std::make_unique<Tstr>(mValue); }

  private:
    void encodeValue(EncodeCallback encodeCallback) const;

    std::string mValue;
};

/**
 * ViewTstr is a read-only version of Tstr backed by std::string_view
 */
class ViewTstr : public Item {
  public:
    static constexpr MajorType kMajorType = TSTR;

    // Construct an empty ViewTstr
    explicit ViewTstr() {}

    // Construct from a string_view
    explicit ViewTstr(std::string_view v) : mView(std::move(v)) {}

    // Construct from an iterator range
    template <typename I1, typename I2,
              typename = typename std::iterator_traits<I1>::iterator_category,
              typename = typename std::iterator_traits<I2>::iterator_category>
    ViewTstr(I1 begin, I2 end) : mView(begin, end) {}

    // Construct from a uint8_t pointer pair
    ViewTstr(const uint8_t* begin, const uint8_t* end)
        : mView(reinterpret_cast<const char*>(begin),
                std::distance(begin, end)) {}

    bool operator==(const ViewTstr& other) const& { return mView == other.mView; }

    MajorType type() const override { return kMajorType; }
    using Item::asViewTstr;
    ViewTstr* asViewTstr() override { return this; }
    size_t encodedSize() const override { return headerSize(mView.size()) + mView.size(); }
    using Item::encode;
    uint8_t* encode(uint8_t* pos, const uint8_t* end) const override;
    void encode(EncodeCallback encodeCallback) const override {
        encodeHeader(mView.size(), encodeCallback);
        encodeValue(encodeCallback);
    }

    const std::string_view& view() const { return mView; }

    std::unique_ptr<Item> clone() const override { return std::make_unique<ViewTstr>(mView); }

  private:
    void encodeValue(EncodeCallback encodeCallback) const;

    std::string_view mView;
};

/*
 * Array is a concrete Item that implements CBOR major type 4.
 *
 * Note that Arrays are not copyable.  This is because copying them is expensive and making them
 * move-only ensures that they're never copied accidentally.  If you actually want to copy an Array,
 * use the clone() method.
 */
class Array : public Item {
  public:
    static constexpr MajorType kMajorType = ARRAY;

    Array() = default;
    Array(const Array& other) = delete;
    Array(Array&&) = default;
    Array& operator=(const Array&) = delete;
    Array& operator=(Array&&) = default;

    bool operator==(const Array& other) const&;

    /**
     * Construct an Array from a variable number of arguments of different types.  See
     * details::makeItem below for details on what types may be provided.  In general, this accepts
     * all of the types you'd expect and doest the things you'd expect (integral values are addes as
     * Uint or Nint, std::string and char* are added as Tstr, bools are added as Bool, etc.).
     */
    template <typename... Args, typename Enable>
    Array(Args&&... args);

    /**
     * The above variadic constructor is disabled if sizeof(Args) != 1, so special
     * case an explicit Array constructor for creating an Array with one Item.
     */
    template <typename T, typename Enable>
    explicit Array(T&& v);

    /**
     * Append a single element to the Array, of any compatible type.
     */
    template <typename T>
    Array& add(T&& v) &;
    template <typename T>
    Array&& add(T&& v) &&;

    bool isCompound() const override { return true; }

    virtual size_t size() const { return mEntries.size(); }

    size_t encodedSize() const override {
        return std::accumulate(mEntries.begin(), mEntries.end(), headerSize(size()),
                               [](size_t sum, auto& entry) { return sum + entry->encodedSize(); });
    }

    using Item::encode;  // Make base versions visible.
    uint8_t* encode(uint8_t* pos, const uint8_t* end) const override;
    void encode(EncodeCallback encodeCallback) const override;

    const std::unique_ptr<Item>& operator[](size_t index) const { return get(index); }
    std::unique_ptr<Item>& operator[](size_t index) { return get(index); }

    const std::unique_ptr<Item>& get(size_t index) const { return mEntries[index]; }
    std::unique_ptr<Item>& get(size_t index) { return mEntries[index]; }

    MajorType type() const override { return kMajorType; }
    using Item::asArray;
    Array* asArray() override { return this; }

    std::unique_ptr<Item> clone() const override;

    auto begin() { return mEntries.begin(); }
    auto begin() const { return mEntries.begin(); }
    auto end() { return mEntries.end(); }
    auto end() const { return mEntries.end(); }

  protected:
    std::vector<std::unique_ptr<Item>> mEntries;
};

/*
 * Map is a concrete Item that implements CBOR major type 5.
 *
 * Note that Maps are not copyable.  This is because copying them is expensive and making them
 * move-only ensures that they're never copied accidentally.  If you actually want to copy a
 * Map, use the clone() method.
 */
class Map : public Item {
  public:
    static constexpr MajorType kMajorType = MAP;

    using entry_type = std::pair<std::unique_ptr<Item>, std::unique_ptr<Item>>;

    Map() = default;
    Map(const Map& other) = delete;
    Map(Map&&) = default;
    Map& operator=(const Map& other) = delete;
    Map& operator=(Map&&) = default;

    bool operator==(const Map& other) const&;

    /**
     * Construct a Map from a variable number of arguments of different types.  An even number of
     * arguments must be provided (this is verified statically). See details::makeItem below for
     * details on what types may be provided.  In general, this accepts all of the types you'd
     * expect and doest the things you'd expect (integral values are addes as Uint or Nint,
     * std::string and char* are added as Tstr, bools are added as Bool, etc.).
     */
    template <typename... Args, typename Enable>
    Map(Args&&... args);

    /**
     * Append a key/value pair to the Map, of any compatible types.
     */
    template <typename Key, typename Value>
    Map& add(Key&& key, Value&& value) &;
    template <typename Key, typename Value>
    Map&& add(Key&& key, Value&& value) &&;

    bool isCompound() const override { return true; }

    virtual size_t size() const { return mEntries.size(); }

    size_t encodedSize() const override {
        return std::accumulate(
                mEntries.begin(), mEntries.end(), headerSize(size()), [](size_t sum, auto& entry) {
                    return sum + entry.first->encodedSize() + entry.second->encodedSize();
                });
    }

    using Item::encode;  // Make base versions visible.
    uint8_t* encode(uint8_t* pos, const uint8_t* end) const override;
    void encode(EncodeCallback encodeCallback) const override;

    /**
     * Find and return the value associated with `key`, if any.
     *
     * If the searched-for `key` is not present, returns `nullptr`.
     *
     * Note that if the map is canonicalized (sorted), Map::get() performs a binary search.  If your
     * map is large and you're searching in it many times, it may be worthwhile to canonicalize it
     * to make Map::get() faster.  Any use of a method that might modify the map disables the
     * speedup.
     */
    template <typename Key, typename Enable>
    const std::unique_ptr<Item>& get(Key key) const;

    // Note that use of non-const operator[] marks the map as not canonicalized.
    auto& operator[](size_t index) {
        mCanonicalized = false;
        return mEntries[index];
    }
    const auto& operator[](size_t index) const { return mEntries[index]; }

    MajorType type() const override { return kMajorType; }
    using Item::asMap;
    Map* asMap() override { return this; }

    /**
     * Sorts the map in canonical order, as defined in RFC 7049. Use this before encoding if you
     * want canonicalization; cppbor does not canonicalize by default, though the integer encodings
     * are always canonical and cppbor does not support indefinite-length encodings, so map order
     * canonicalization is the only thing that needs to be done.
     *
     * @param recurse If set to true, canonicalize() will also walk the contents of the map and
     * canonicalize any contained maps as well.
     */
    Map& canonicalize(bool recurse = false) &;
    Map&& canonicalize(bool recurse = false) && {
        canonicalize(recurse);
        return std::move(*this);
    }

    bool isCanonical() { return mCanonicalized; }

    std::unique_ptr<Item> clone() const override;

    auto begin() {
        mCanonicalized = false;
        return mEntries.begin();
    }
    auto begin() const { return mEntries.begin(); }
    auto end() {
        mCanonicalized = false;
        return mEntries.end();
    }
    auto end() const { return mEntries.end(); }

    // Returns true if a < b, per CBOR map key canonicalization rules.
    static bool keyLess(const Item* a, const Item* b);

  protected:
    std::vector<entry_type> mEntries;

  private:
    bool mCanonicalized = false;
};

class SemanticTag : public Item {
  public:
    static constexpr MajorType kMajorType = SEMANTIC;

    template <typename T>
    SemanticTag(uint64_t tagValue, T&& taggedItem);
    SemanticTag(const SemanticTag& other) = delete;
    SemanticTag(SemanticTag&&) = default;
    SemanticTag& operator=(const SemanticTag& other) = delete;
    SemanticTag& operator=(SemanticTag&&) = default;

    bool operator==(const SemanticTag& other) const& {
        return mValue == other.mValue && *mTaggedItem == *other.mTaggedItem;
    }

    bool isCompound() const override { return true; }

    virtual size_t size() const { return 1; }

    // Encoding returns the tag + enclosed Item.
    size_t encodedSize() const override { return headerSize(mValue) + mTaggedItem->encodedSize(); }

    using Item::encode;  // Make base versions visible.
    uint8_t* encode(uint8_t* pos, const uint8_t* end) const override;
    void encode(EncodeCallback encodeCallback) const override;

    // type() is a bit special.  In normal usage it should return the wrapped type, but during
    // parsing when we haven't yet parsed the tagged item, it needs to return SEMANTIC.
    MajorType type() const override { return mTaggedItem ? mTaggedItem->type() : SEMANTIC; }
    using Item::asSemanticTag;
    SemanticTag* asSemanticTag() override { return this; }

    // Type information reflects the enclosed Item.  Note that if the immediately-enclosed Item is
    // another tag, these methods will recurse down to the non-tag Item.
    using Item::asInt;
    Int* asInt() override { return mTaggedItem->asInt(); }
    using Item::asUint;
    Uint* asUint() override { return mTaggedItem->asUint(); }
    using Item::asNint;
    Nint* asNint() override { return mTaggedItem->asNint(); }
    using Item::asTstr;
    Tstr* asTstr() override { return mTaggedItem->asTstr(); }
    using Item::asBstr;
    Bstr* asBstr() override { return mTaggedItem->asBstr(); }
    using Item::asSimple;
    Simple* asSimple() override { return mTaggedItem->asSimple(); }
    using Item::asMap;
    Map* asMap() override { return mTaggedItem->asMap(); }
    using Item::asArray;
    Array* asArray() override { return mTaggedItem->asArray(); }
    using Item::asViewTstr;
    ViewTstr* asViewTstr() override { return mTaggedItem->asViewTstr(); }
    using Item::asViewBstr;
    ViewBstr* asViewBstr() override { return mTaggedItem->asViewBstr(); }

    std::unique_ptr<Item> clone() const override;

    size_t semanticTagCount() const override;
    uint64_t semanticTag(size_t nesting = 0) const override;

  protected:
    SemanticTag() = default;
    SemanticTag(uint64_t value) : mValue(value) {}
    uint64_t mValue;
    std::unique_ptr<Item> mTaggedItem;
};

/**
 * Simple is abstract Item that implements CBOR major type 7.  It is intended to be subclassed to
 * create concrete Simple types.  At present only Bool is provided.
 */
class Simple : public Item {
  public:
    static constexpr MajorType kMajorType = SIMPLE;

    bool operator==(const Simple& other) const&;

    virtual SimpleType simpleType() const = 0;
    MajorType type() const override { return kMajorType; }

    Simple* asSimple() override { return this; }
};

/**
 * Bool is a concrete type that implements CBOR major type 7, with additional item values for TRUE
 * and FALSE.
 */
class Bool : public Simple {
  public:
    static constexpr SimpleType kSimpleType = BOOLEAN;

    explicit Bool(bool v) : mValue(v) {}

    bool operator==(const Bool& other) const& { return mValue == other.mValue; }

    SimpleType simpleType() const override { return kSimpleType; }
    Bool* asBool() override { return this; }

    size_t encodedSize() const override { return 1; }

    using Item::encode;
    uint8_t* encode(uint8_t* pos, const uint8_t* end) const override {
        return encodeHeader(mValue ? TRUE : FALSE, pos, end);
    }
    void encode(EncodeCallback encodeCallback) const override {
        encodeHeader(mValue ? TRUE : FALSE, encodeCallback);
    }

    bool value() const { return mValue; }

    std::unique_ptr<Item> clone() const override { return std::make_unique<Bool>(mValue); }

  private:
    bool mValue;
};

/**
 * Null is a concrete type that implements CBOR major type 7, with additional item value for NULL
 */
class Null : public Simple {
  public:
    static constexpr SimpleType kSimpleType = NULL_T;

    explicit Null() {}

    SimpleType simpleType() const override { return kSimpleType; }
    Null* asNull() override { return this; }

    size_t encodedSize() const override { return 1; }

    using Item::encode;
    uint8_t* encode(uint8_t* pos, const uint8_t* end) const override {
        return encodeHeader(NULL_V, pos, end);
    }
    void encode(EncodeCallback encodeCallback) const override {
        encodeHeader(NULL_V, encodeCallback);
    }

    std::unique_ptr<Item> clone() const override { return std::make_unique<Null>(); }
};

/**
 * Returns pretty-printed CBOR for |item|
 *
 * If a byte-string is larger than |maxBStrSize| its contents will not be printed, instead the value
 * of the form "<bstr size=1099016 sha1=ef549cca331f73dfae2090e6a37c04c23f84b07b>" will be
 * printed. Pass zero for |maxBStrSize| to disable this.
 *
 * The |mapKeysToNotPrint| parameter specifies the name of map values to not print. This is useful
 * for unit tests.
 */
std::string prettyPrint(const Item* item, size_t maxBStrSize = 32,
                        const std::vector<std::string>& mapKeysToNotPrint = {});

/**
 * Returns pretty-printed CBOR for |value|.
 *
 * Only valid CBOR should be passed to this function.
 *
 * If a byte-string is larger than |maxBStrSize| its contents will not be printed, instead the value
 * of the form "<bstr size=1099016 sha1=ef549cca331f73dfae2090e6a37c04c23f84b07b>" will be
 * printed. Pass zero for |maxBStrSize| to disable this.
 *
 * The |mapKeysToNotPrint| parameter specifies the name of map values to not print. This is useful
 * for unit tests.
 */
std::string prettyPrint(const std::vector<uint8_t>& encodedCbor, size_t maxBStrSize = 32,
                        const std::vector<std::string>& mapKeysToNotPrint = {});

/**
 * Details. Mostly you shouldn't have to look below, except perhaps at the docstring for makeItem.
 */
namespace details {

template <typename T, typename V, typename Enable = void>
struct is_iterator_pair_over : public std::false_type {};

template <typename I1, typename I2, typename V>
struct is_iterator_pair_over<
        std::pair<I1, I2>, V,
        typename std::enable_if_t<std::is_same_v<V, typename std::iterator_traits<I1>::value_type>>>
    : public std::true_type {};

template <typename T, typename V, typename Enable = void>
struct is_unique_ptr_of_subclass_of_v : public std::false_type {};

template <typename T, typename P>
struct is_unique_ptr_of_subclass_of_v<T, std::unique_ptr<P>,
                                      typename std::enable_if_t<std::is_base_of_v<T, P>>>
    : public std::true_type {};

/* check if type is one of std::string (1), std::string_view (2), null-terminated char* (3) or pair
 *     of iterators (4)*/
template <typename T, typename Enable = void>
struct is_text_type_v : public std::false_type {};

template <typename T>
struct is_text_type_v<
        T, typename std::enable_if_t<
                   /* case 1 */  //
                   std::is_same_v<std::remove_cv_t<std::remove_reference_t<T>>, std::string>
                   /* case 2 */  //
                   || std::is_same_v<std::remove_cv_t<std::remove_reference_t<T>>, std::string_view>
                   /* case 3 */                                                 //
                   || std::is_same_v<std::remove_cv_t<std::decay_t<T>>, char*>  //
                   || std::is_same_v<std::remove_cv_t<std::decay_t<T>>, const char*>
                   /* case 4 */
                   || details::is_iterator_pair_over<T, char>::value>> : public std::true_type {};

/**
 * Construct a unique_ptr<Item> from many argument types. Accepts:
 *
 * (a) booleans;
 * (b) integers, all sizes and signs;
 * (c) text strings, as defined by is_text_type_v above;
 * (d) byte strings, as std::vector<uint8_t>(d1), pair of iterators (d2) or pair<uint8_t*, size_T>
 *     (d3); and
 * (e) Item subclass instances, including Array and Map.  Items may be provided by naked pointer
 *     (e1), unique_ptr (e2), reference (e3) or value (e3).  If provided by reference or value, will
 *     be moved if possible.  If provided by pointer, ownership is taken.
 * (f) null pointer;
 * (g) enums, using the underlying integer value.
 */
template <typename T>
std::unique_ptr<Item> makeItem(T v) {
    Item* p = nullptr;
    if constexpr (/* case a */ std::is_same_v<T, bool>) {
        p = new Bool(v);
    } else if constexpr (/* case b */ std::is_integral_v<T>) {  // b
        if (v < 0) {
            p = new Nint(v);
        } else {
            p = new Uint(static_cast<uint64_t>(v));
        }
    } else if constexpr (/* case c */  //
                         details::is_text_type_v<T>::value) {
        p = new Tstr(v);
    } else if constexpr (/* case d1 */  //
                         std::is_same_v<std::remove_cv_t<std::remove_reference_t<T>>,
                                        std::vector<uint8_t>>
                         /* case d2 */  //
                         || details::is_iterator_pair_over<T, uint8_t>::value
                         /* case d3 */  //
                         || std::is_same_v<std::remove_cv_t<std::remove_reference_t<T>>,
                                           std::pair<uint8_t*, size_t>>) {
        p = new Bstr(v);
    } else if constexpr (/* case e1 */  //
                         std::is_pointer_v<T> &&
                         std::is_base_of_v<Item, std::remove_pointer_t<T>>) {
        p = v;
    } else if constexpr (/* case e2 */  //
                         details::is_unique_ptr_of_subclass_of_v<Item, T>::value) {
        p = v.release();
    } else if constexpr (/* case e3 */  //
                         std::is_base_of_v<Item, T>) {
        p = new T(std::move(v));
    } else if constexpr (/* case f */ std::is_null_pointer_v<T>) {
        p = new Null();
    } else if constexpr (/* case g */ std::is_enum_v<T>) {
        return makeItem(static_cast<std::underlying_type_t<T>>(v));
    } else {
        // It's odd that this can't be static_assert(false), since it shouldn't be evaluated if one
        // of the above ifs matches.  But static_assert(false) always triggers.
        static_assert(std::is_same_v<T, bool>, "makeItem called with unsupported type");
    }
    return std::unique_ptr<Item>(p);
}

inline void map_helper(Map& /* map */) {}

template <typename Key, typename Value, typename... Rest>
inline void map_helper(Map& map, Key&& key, Value&& value, Rest&&... rest) {
    map.add(std::forward<Key>(key), std::forward<Value>(value));
    map_helper(map, std::forward<Rest>(rest)...);
}

}  // namespace details

template <typename... Args,
         /* Prevent implicit construction with a single argument. */
         typename = std::enable_if_t<(sizeof...(Args)) != 1>>
Array::Array(Args&&... args) {
    mEntries.reserve(sizeof...(args));
    (mEntries.push_back(details::makeItem(std::forward<Args>(args))), ...);
}

template <typename T,
         /* Prevent use as copy constructor. */
         typename = std::enable_if_t<
            !std::is_same_v<Array, std::remove_cv_t<std::remove_reference_t<T>>>>>
Array::Array(T&& v) {
    mEntries.push_back(details::makeItem(std::forward<T>(v)));
}

template <typename T>
Array& Array::add(T&& v) & {
    mEntries.push_back(details::makeItem(std::forward<T>(v)));
    return *this;
}

template <typename T>
Array&& Array::add(T&& v) && {
    mEntries.push_back(details::makeItem(std::forward<T>(v)));
    return std::move(*this);
}

template <typename... Args,
          /* Prevent use as copy ctor */ typename = std::enable_if_t<(sizeof...(Args)) != 1>>
Map::Map(Args&&... args) {
    static_assert((sizeof...(Args)) % 2 == 0, "Map must have an even number of entries");
    mEntries.reserve(sizeof...(args) / 2);
    details::map_helper(*this, std::forward<Args>(args)...);
}

template <typename Key, typename Value>
Map& Map::add(Key&& key, Value&& value) & {
    mEntries.push_back({details::makeItem(std::forward<Key>(key)),
                        details::makeItem(std::forward<Value>(value))});
    mCanonicalized = false;
    return *this;
}

template <typename Key, typename Value>
Map&& Map::add(Key&& key, Value&& value) && {
    this->add(std::forward<Key>(key), std::forward<Value>(value));
    return std::move(*this);
}

static const std::unique_ptr<Item> kEmptyItemPtr;

template <typename Key,
          typename = std::enable_if_t<std::is_integral_v<Key> || std::is_enum_v<Key> ||
                                      details::is_text_type_v<Key>::value>>
const std::unique_ptr<Item>& Map::get(Key key) const {
    auto keyItem = details::makeItem(key);

    if (mCanonicalized) {
        // It's sorted, so binary-search it.
        auto found = std::lower_bound(begin(), end(), keyItem.get(),
                                      [](const entry_type& entry, const Item* key) {
                                          return keyLess(entry.first.get(), key);
                                      });
        return (found == end() || *found->first != *keyItem) ? kEmptyItemPtr : found->second;
    } else {
        // Unsorted, do a linear search.
        auto found = std::find_if(
                begin(), end(), [&](const entry_type& entry) { return *entry.first == *keyItem; });
        return found == end() ? kEmptyItemPtr : found->second;
    }
}

template <typename T>
SemanticTag::SemanticTag(uint64_t value, T&& taggedItem)
    : mValue(value), mTaggedItem(details::makeItem(std::forward<T>(taggedItem))) {}

}  // namespace cppbor
