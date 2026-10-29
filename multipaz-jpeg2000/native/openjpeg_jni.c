/*
 * Copyright 2026 The Multipaz Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include "openjpeg.h"

#define MAX_IMAGE_DIMENSION 16384

typedef struct {
    const OPJ_BYTE *data;
    OPJ_SIZE_T size;
    OPJ_SIZE_T offset;
} MemStream;

static OPJ_SIZE_T mem_read(void *p_buffer, OPJ_SIZE_T p_nb_bytes, void *p_user_data) {
    MemStream *ms = (MemStream *)p_user_data;
    if (ms == NULL || ms->offset >= ms->size) {
        return (OPJ_SIZE_T)-1;
    }
    OPJ_SIZE_T avail = ms->size - ms->offset;
    OPJ_SIZE_T to_read = (p_nb_bytes < avail) ? p_nb_bytes : avail;
    if (to_read == 0) {
        return (OPJ_SIZE_T)-1;
    }
    memcpy(p_buffer, ms->data + ms->offset, to_read);
    ms->offset += to_read;
    return to_read;
}

static OPJ_OFF_T mem_skip(OPJ_OFF_T p_nb_bytes, void *p_user_data) {
    MemStream *ms = (MemStream *)p_user_data;
    if (ms == NULL) {
        return -1;
    }
    if (p_nb_bytes < 0) {
        OPJ_SIZE_T back = (OPJ_SIZE_T)(-p_nb_bytes);
        if (back > ms->offset) {
            return -1;
        }
        ms->offset -= back;
        return p_nb_bytes;
    }
    OPJ_SIZE_T skip = (OPJ_SIZE_T)p_nb_bytes;
    if (skip > ms->size - ms->offset) {
        ms->offset = ms->size;
    } else {
        ms->offset += skip;
    }
    return p_nb_bytes;
}

static OPJ_BOOL mem_seek(OPJ_OFF_T p_nb_bytes, void *p_user_data) {
    MemStream *ms = (MemStream *)p_user_data;
    if (ms == NULL || p_nb_bytes < 0 || (OPJ_SIZE_T)p_nb_bytes > ms->size) {
        return OPJ_FALSE;
    }
    ms->offset = (OPJ_SIZE_T)p_nb_bytes;
    return OPJ_TRUE;
}

static inline int clamp_u8(int val) {
    if (val < 0) return 0;
    if (val > 255) return 255;
    return val;
}

static inline int normalize_sample(const opj_image_comp_t *comp, size_t idx) {
    int val = comp->data[idx];
    uint32_t prec = comp->prec;
    if (comp->sgnd && prec > 0 && prec < 31) {
        val += (1 << (prec - 1));
    }
    if (prec > 8) {
        val >>= (prec - 8);
    } else if (prec > 0 && prec < 8) {
        int max_val = (1 << prec) - 1;
        val = (val * 255 + (max_val / 2)) / max_val;
    }
    return clamp_u8(val);
}

static inline int sample_at(const opj_image_comp_t *comp, uint32_t x, uint32_t y, uint32_t width, uint32_t height) {
    uint32_t cx = (comp->w == width) ? x : (uint32_t)(((uint64_t)x * comp->w) / width);
    uint32_t cy = (comp->h == height) ? y : (uint32_t)(((uint64_t)y * comp->h) / height);
    if (cx >= comp->w) cx = comp->w - 1;
    if (cy >= comp->h) cy = comp->h - 1;
    return normalize_sample(comp, (size_t)cy * comp->w + cx);
}

static int detect_format(const uint8_t *buf, size_t len, OPJ_CODEC_FORMAT *out_format) {
    static const uint8_t jp2_magic[12] = {
        0x00, 0x00, 0x00, 0x0C, 0x6A, 0x50, 0x20, 0x20, 0x0D, 0x0A, 0x87, 0x0A
    };
    static const uint8_t j2k_magic[4] = {
        0xFF, 0x4F, 0xFF, 0x51
    };
    if (len >= 12 && memcmp(buf, jp2_magic, 12) == 0) {
        *out_format = OPJ_CODEC_JP2;
        return 1;
    }
    if (len >= 4 && memcmp(buf, j2k_magic, 4) == 0) {
        *out_format = OPJ_CODEC_J2K;
        return 1;
    }
    return 0;
}

JNIEXPORT jintArray JNICALL
Java_org_multipaz_jpeg2000_OpenJpegJni_decodeNative(
    JNIEnv *env,
    jclass clazz,
    jbyteArray encoded_data,
    jintArray out_dimensions
) {
    (void)clazz;
    if (encoded_data == NULL || out_dimensions == NULL) {
        return NULL;
    }

    jsize data_len = (*env)->GetArrayLength(env, encoded_data);
    jsize dim_len = (*env)->GetArrayLength(env, out_dimensions);
    if (data_len < 4 || dim_len < 2) {
        return NULL;
    }

    jbyte *raw_bytes = (*env)->GetByteArrayElements(env, encoded_data, NULL);
    if (raw_bytes == NULL) {
        return NULL;
    }

    OPJ_CODEC_FORMAT codec_format;
    if (!detect_format((const uint8_t *)raw_bytes, (size_t)data_len, &codec_format)) {
        (*env)->ReleaseByteArrayElements(env, encoded_data, raw_bytes, JNI_ABORT);
        return NULL;
    }

    MemStream ms;
    ms.data = (const OPJ_BYTE *)raw_bytes;
    ms.size = (OPJ_SIZE_T)data_len;
    ms.offset = 0;

    opj_stream_t *stream = opj_stream_create(OPJ_J2K_STREAM_CHUNK_SIZE, OPJ_TRUE);
    if (stream == NULL) {
        (*env)->ReleaseByteArrayElements(env, encoded_data, raw_bytes, JNI_ABORT);
        return NULL;
    }
    opj_stream_set_user_data(stream, &ms, NULL);
    opj_stream_set_user_data_length(stream, ms.size);
    opj_stream_set_read_function(stream, mem_read);
    opj_stream_set_skip_function(stream, mem_skip);
    opj_stream_set_seek_function(stream, mem_seek);

    opj_codec_t *codec = opj_create_decompress(codec_format);
    if (codec == NULL) {
        opj_stream_destroy(stream);
        (*env)->ReleaseByteArrayElements(env, encoded_data, raw_bytes, JNI_ABORT);
        return NULL;
    }

    opj_dparameters_t parameters;
    opj_set_default_decoder_parameters(&parameters);

    opj_image_t *image = NULL;
    jintArray result_array = NULL;

    if (!opj_setup_decoder(codec, &parameters)) {
        goto cleanup;
    }
    if (!opj_read_header(stream, codec, &image) || image == NULL) {
        goto cleanup;
    }
    if (image->numcomps == 0) {
        goto cleanup;
    }
    if (image->comps[0].w == 0 || image->comps[0].h == 0 ||
        image->comps[0].w > MAX_IMAGE_DIMENSION || image->comps[0].h > MAX_IMAGE_DIMENSION) {
        goto cleanup;
    }
    if (!opj_decode(codec, stream, image)) {
        goto cleanup;
    }
    /* Note: opj_end_decompress is optional/informational if opj_decode already succeeded */
    (void)opj_end_decompress(codec, stream);

    uint32_t width = image->comps[0].w;
    uint32_t height = image->comps[0].h;
    if (width == 0 || height == 0 || width > MAX_IMAGE_DIMENSION || height > MAX_IMAGE_DIMENSION) {
        goto cleanup;
    }

    uint32_t used_comps = image->numcomps > 4 ? 4 : image->numcomps;
    for (uint32_t c = 0; c < used_comps; c++) {
        if (image->comps[c].data == NULL || image->comps[c].w == 0 || image->comps[c].h == 0 ||
            image->comps[c].prec == 0 || image->comps[c].prec > 30) {
            goto cleanup;
        }
    }

    size_t num_pixels = (size_t)width * (size_t)height;
    jint *pixels = (jint *)malloc(num_pixels * sizeof(jint));
    if (pixels == NULL) {
        goto cleanup;
    }

    int is_sycc = 0;
    if (image->numcomps >= 3) {
        if (image->color_space == OPJ_CLRSPC_SYCC) {
            is_sycc = 1;
        } else if (image->color_space != OPJ_CLRSPC_SRGB &&
                   (image->comps[1].dx > 1 || image->comps[1].dy > 1 ||
                    image->comps[2].dx > 1 || image->comps[2].dy > 1)) {
            is_sycc = 1;
        }
    }

    for (uint32_t y = 0; y < height; y++) {
        size_t row_offset = (size_t)y * width;
        for (uint32_t x = 0; x < width; x++) {
            int r, g, b, a = 255;
            if (image->numcomps >= 3) {
                int c0 = sample_at(&image->comps[0], x, y, width, height);
                int c1 = sample_at(&image->comps[1], x, y, width, height);
                int c2 = sample_at(&image->comps[2], x, y, width, height);
                if (is_sycc) {
                    int cb = c1 - 128;
                    int cr = c2 - 128;
                    r = clamp_u8(c0 + ((91881 * cr + 32768) >> 16));
                    g = clamp_u8(c0 - ((22554 * cb + 46802 * cr + 32768) >> 16));
                    b = clamp_u8(c0 + ((116130 * cb + 32768) >> 16));
                } else {
                    r = c0;
                    g = c1;
                    b = c2;
                }
                if (image->numcomps >= 4) {
                    a = sample_at(&image->comps[3], x, y, width, height);
                }
            } else {
                int gray = sample_at(&image->comps[0], x, y, width, height);
                r = gray;
                g = gray;
                b = gray;
                if (image->numcomps == 2) {
                    a = sample_at(&image->comps[1], x, y, width, height);
                }
            }
            pixels[row_offset + x] = (jint)(((uint32_t)a << 24) |
                                            ((uint32_t)r << 16) |
                                            ((uint32_t)g << 8) |
                                            (uint32_t)b);
        }
    }

    result_array = (*env)->NewIntArray(env, (jsize)num_pixels);
    if (result_array != NULL) {
        (*env)->SetIntArrayRegion(env, result_array, 0, (jsize)num_pixels, pixels);
        jint dims[2] = { (jint)width, (jint)height };
        (*env)->SetIntArrayRegion(env, out_dimensions, 0, 2, dims);
    }
    free(pixels);

cleanup:
    if (image != NULL) {
        opj_image_destroy(image);
    }
    if (codec != NULL) {
        opj_destroy_codec(codec);
    }
    if (stream != NULL) {
        opj_stream_destroy(stream);
    }
    (*env)->ReleaseByteArrayElements(env, encoded_data, raw_bytes, JNI_ABORT);
    return result_array;
}
