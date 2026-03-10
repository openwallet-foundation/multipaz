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
package org.multipaz.storage

import kotlinx.coroutines.CancellationException
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.CborMap
import org.multipaz.util.Logger
import kotlinx.io.buffered
import kotlinx.io.files.FileNotFoundException
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray
import org.multipaz.cbor.buildCborMap

/**
 * An storage engine implemented by storing data in a file.
 *
 * @param storageFile the file to store the data in.
 */
open class GenericStorageEngine(
    private val storageFile: Path,
) : StorageEngine {

    companion object {
        private const val TAG = "GenericStorageEngine"
    }

    /**
     * Function to transform data when loading or saving data.
     *
     * This can be used for encryption/decryption. By defualt this is the identity function.
     *
     * @param data the data to transform.
     * @param isLoading set to `true` when loading data, `false` when saving data.
     */
    open fun transform(data: ByteArray, isLoading: Boolean): ByteArray {
        return data
    }

    private var data: MutableMap<String, ByteArray>? = null

    private fun ensureData() {
        if (data != null) {
            return
        }
        val path = Path(storageFile)
        try {
            val nonTransformedData = SystemFileSystem.source(path).buffered().readByteArray()
            val encodedData = transform(nonTransformedData, true)
            data = mutableMapOf()
            if (encodedData.size > 0) {
                try {
                    val map = Cbor.decode(encodedData).asMap
                    for ((key, value) in map) {
                        data!!.put(key.asTstr, value.asBstr)
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Logger.w(TAG, "Error decoding data at $path - treating as zeroed data", e)
                }
            }
        } catch (_: FileNotFoundException) {
            // No problem, we all start from zero at some point...
            data = mutableMapOf()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            throw IllegalStateException("Error loading data", e)
        }
    }

    private fun saveData() {
        check(data != null)
        val nonTransformedData = Cbor.encode(
            buildCborMap {
                for ((key, value) in data!!) {
                    put(key, value)
                }
            }
        )
        val transformedData = transform(nonTransformedData, false)
        // TODO: would be better if something like mkstemp(3) was available... but it's not.
        val newPath = Path("$storageFile.tmp")
        val path = Path(storageFile)
        val sink = SystemFileSystem.sink(newPath).buffered()
        sink.write(transformedData)
        sink.flush()
        sink.close()
        SystemFileSystem.atomicMove(newPath, path)
    }

    override fun get(key: String): ByteArray? {
        ensureData()
        return data!![key]
    }

    override fun put(key: String, data: ByteArray) {
        ensureData()
        this.data!![key] = data
        saveData()
    }

    override fun delete(key: String) {
        ensureData()
        data!!.remove(key)
        saveData()
    }

    override fun deleteAll() {
        ensureData()
        data!!.clear()
        saveData()
    }

    override fun enumerate(): Collection<String> {
        ensureData()
        return data!!.keys
    }

}